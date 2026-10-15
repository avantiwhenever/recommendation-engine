# Recommendation strategies

This document explains the six recommendation strategies in `recommender-service`.
For the evaluation results, see [EVALUATION.md](EVALUATION.md).
For the sources, see [REFERENCES.md](REFERENCES.md).

## The request pipeline

All strategies use the same pipeline. Only step 4 changes between strategies.

1. `graphql-gateway` asks `search-service` for a wide pool of up to 200 candidates.
2. `search-service` combines vector search and BM25 keyword search with Reciprocal Rank Fusion (RRF).
3. The gateway removes products that have no ratings. It keeps the 50 candidates with the highest scores.
4. `recommender-service` applies the selected strategy to these 50 candidates.
5. The gateway returns the first `topK` results.

A strategy can re-order the candidates. Some strategies can also add products that search did not find.

## Summary

| Strategy | Signal | Adds products | Source class |
|---|---|---|---|
| None | Search score only | No | `PassthroughStrategy` |
| Popularity | Search score and clickstream popularity | Yes, up to 2 | `PopularityBoostStrategy` |
| Collaborative filtering | Item-to-item co-occurrence | Yes, up to 2 | `CollaborativeFilteringStrategy` |
| Bandit exploration | Category-level Thompson sampling | No | `BanditExploreStrategy` |
| Neural ranking | Trained XGBoost ranking model | No | `NeuralRankingStrategy` |
| Diverse popularity | Popularity, then MMR diversity | Yes, through Popularity | `DiversityAwareStrategy` |

## Shared signals

The strategies read from the clickstream through `ClickstreamRepositoryPort`.

- **Popularity score.** Each event adds a weight to its product: view 0.2, click 0.5, add to cart 0.8, purchase 1.0.
- **Interactions.** Clicks, add-to-cart events, and purchases are interactions. Views are not interactions, because each results page shows many products.
- **Item similarity.** Two products are similar when users interact with both in the same session. The similarity is the co-occurrence count divided by `sqrt(sessions with A × sessions with B)`. This is cosine similarity over session interaction sets.
- **User profile.** For each user, the system records the products and the top-level categories of their interactions.

## None (baseline)

This strategy returns the candidates without change.
Use it as the control group when you compare the other strategies.

## Popularity

This strategy combines search relevance with popularity.

1. It scales the search scores and the popularity scores of the candidates to the range 0 to 1 (min-max).
2. It calculates `0.6 × search score + 0.4 × popularity score` for each candidate.
3. It sorts the candidates by this value.
4. It adds up to 2 popular products from the full catalog that are not in the candidates.

## Collaborative filtering

This strategy promotes products that are similar to the products that the user interacted with.

- For each candidate, it finds the highest similarity to the user's history and to the current session.
- It adds `4.0 × history similarity + 10.0 × session similarity` to the candidate score. The session has a 2.5 times higher weight, because recent behavior shows current intent.
- It uses the highest similarity, not the average. One strong match is a good reason to recommend a product.
- It adds up to 2 related products that are not in the candidates.
- **Cold start.** A user with no history and no session gets the Popularity strategy. This is a documented fallback, not a silent pass-through.

## Bandit exploration

This strategy is a contextual multi-armed bandit. It changes which categories appear first, so users see more varied results.

- **Arms.** Each top-level product category (for example, "Furniture") is one arm. This keeps the number of arms small.
- **Priors.** At startup, the strategy calculates a Beta(α, β) prior for each arm from historical engagement of the 500 most popular products.
- **Context.** The requesting user's most frequent category gets a higher α for that request.
- **Selection.** For each request, the strategy takes a random sample from each arm's Beta distribution (Thompson sampling). The arm with the highest sample comes first.
- **Calibration.** One category can take a maximum of 60% of the first 5 positions. The remaining products of that category move lower in the list. They are not removed.
- **Order in an arm.** In each category, products keep their original order.

**Limit.** The priors do not change during operation. The project has no live traffic to give rewards.

## Neural ranking

This strategy scores each candidate with a pairwise learning-to-rank model.

- **Model.** XGBoost with the `rank:ndcg` objective, exported to ONNX. Java runs it with ONNX Runtime.
- **Training.** The model trains on the clickstream in Python. See [training/TRAINING.md](../training/TRAINING.md).
- **Features.** The model uses 7 features:

| # | Feature | Calculation |
|---|---|---|
| 1 | Category match | 1 if the product is in the user's top category, else 0 |
| 2 | Base score | Sigmoid of the search score |
| 3 | Popularity | `log(1 + popularity score)` |
| 4 | Co-occurrence | `log(1 + co-occurrence with the user's products)` |
| 5 | Average rating | Average rating divided by 5 |
| 6 | Review count | `log(1 + rating count)` |
| 7 | Session category overlap | Part of the session's products in the same category |

`FeatureParityTest` makes sure that the Java features and the Python training features are identical. A difference between them is a common cause of errors in production ranking models.

**Limit.** No feature measures how well a product matches the query. [EVALUATION.md](EVALUATION.md) shows the effect of this limit.

## Diverse popularity

This strategy is a decorator. It takes the output of the Popularity strategy and re-orders it for diversity. It can wrap any strategy.

It uses Maximal Marginal Relevance (MMR). At each position, it selects the product with the highest value of:

```
0.7 × relevance − 0.3 × highest similarity to the products already selected
```

The similarity between two products is the higher value of these two signals:

- **Category similarity.** 0.5 if the products have the same top-level category. 1.0 if they also have the same product class.
- **Embedding similarity.** The rank of one product in the other product's list of nearest neighbors in Pinecone.

The strategy uses the higher value because a product can have no embedding in the index. In that case, the category signal still applies.

**Performance.** The strategy sends one neighbor query for each candidate, not one for each pair. It runs these queries in parallel on 5 threads. [docs/PROJECT_STATE.md](PROJECT_STATE.md) records the measured latency and the reason for 5 threads.

## Add a strategy

1. Add the new name to each of these enums. The names must be identical, because the services map them by name.
   - `proto/recommender_service.proto` (`Strategy`)
   - `recommender-service`: `domain/Strategy.java`
   - `graphql-gateway`: `domain/RecommenderStrategy.java` and `graphql/schema.graphqls`
   - `web/src/graphql/types.ts` (`RecommenderStrategy` and `STRATEGIES`)
2. Write a class that implements `RecommendationStrategy`.
3. Add the class to the map in `domain/strategy/Strategies.java`.
4. Add a display name in `eval/ResultsMarkdownWriter.java`. The compiler shows an error until you do this.
5. Add a badge label in `web/src/utils/humanizeSource.ts`.
6. Write unit tests with `FakeClickstreamRepository`.

The offline evaluation includes the new strategy automatically.
