# Training the ranking model

`train_neural_ranker.py` trains the model that `NeuralRankingStrategy` in `recommender-service` uses. Java runs the model with `OnnxRankingModelAdapter` and ONNX Runtime.

This document gives the method, the held-out results, and the known limitations.

## Reproduce the results

```sh
pip install -r requirements.txt
python3 train_neural_ranker.py --seed 42                # Train and export the model.
python3 train_neural_ranker.py --seed 42 --ci-seeds 5   # Also show the mean and spread over 5 seeds.
python3 test_feature_parity.py                          # Run the Python feature parity test.
```

The script writes `../models/neural-ranker/model.onnx`. With the same seed, the script always gives the same model. It sets the seed for the Python `random` module and for the `numpy` random generator.

## Data and labels

The source is `data/clickstream.csv`, with approximately 171,000 events from 5,000 users.

- **Positive labels.** The script makes one pair for each user and each product that the user interacted with. The label is the highest event weight of the pair: `view` 0.2, `click` 0.5, `add_to_cart` 0.8, `purchase` 1.0.
- **Negative labels.** For each user, the script selects an equal number of negative products (1 to 1).

### How the script selects negatives

- **Only products that have clickstream history.** Approximately 97% of the WANDS products never occur in the synthetic clickstream. A session uses only the products that `label.csv` judged for its query. If the script selected negatives from the full catalog, almost all negatives would have no clickstream history. Then the popularity feature alone would separate positives from negatives, and the model would learn nothing useful.
- **Random selection in that group.** The script selects the negatives at random from the products with clickstream history. It does not use "hard" negatives, for example products that the user saw but did not click. See [Known limitations](#known-limitations).
- **Realistic positions.** The `base_score_proxy` feature uses the position of the product (see the table below). For a positive pair, the script uses the real position. For a negative pair, it selects a position from the real distribution of positions in the data. The script does not use a fixed value for negatives. A value worse than all real positions (1 to 15) would make this feature an almost perfect separator.

## Features

The order of the features is important. A test makes sure that it does not change. The numbers start at 0, the same as the feature array in the code.

| # | Feature | Source in training | Source in serving | Difference |
|---|---|---|---|---|
| 0 | `category_match` | The product category against the user's most frequent top-level category, from the clickstream and `product.csv` | The same, from `ClickstreamRepositoryPort` and the candidate category | None |
| 1 | `base_score_proxy` | `sigmoid(1 / position)`, with the position from the clickstream | `sigmoid(search score)`, with the real score from `search-service` | **Yes.** A training pair has no real search call. Thus, this feature measures "how high was the original position", not the same value as a live search score. See [Known limitations](#known-limitations). |
| 2 | `popularity_log` | `log1p(weighted event sum)` from the clickstream | The same formula and source | None |
| 3 | `co_occurrence_log` | `log1p(session co-occurrence with the user's products)` | The same formula and source | None |
| 4 | `avg_rating / 5.0` | `product.csv` | `product.csv`, through the candidate data from `search-service` | None |
| 5 | `rating_count_log` | `log1p(rating_count)` from `product.csv` | The same | None |
| 6 | `session_category_overlap` | The part of the products in the same session (from `session_id`) that have the candidate's top-level category. The script uses one session for each pair (see `build_dataset`). | The part of `RecommendationContext.recentProductIds()` that has the candidate's top-level category | None. Both sides convert the session products to categories in the same way (`session_category_segments` and `sessionCategorySegments`). |

### A test makes sure that the features agree

`NeuralRankingStrategy.buildFeatures()` in Java and `build_features()` in Python are two separate implementations of the same formulas.

- `feature_parity_fixtures.csv` is a shared fixture file with known inputs and expected outputs.
- `test_feature_parity.py` (Python) and `FeatureParityTest.java` (Java) both read it. They check the 6 features that must be identical in training and serving.
- Feature 1 is different in training and serving by design. Thus, each side checks it against its own formula only.

If a change on one side breaks the agreement, a test fails.

## Training objective

The evaluation metric is pairwise accuracy: does the model give the positive product a higher score than the negative product?

The model in use is `xgboost.XGBRanker` with `objective="rank:ndcg"`. It trains on the same pairwise objective:

- It uses LambdaMART-style pairwise gradients.
- The weight of each pair is the change in NDCG (`|ΔNDCG|`) that a swap of the pair causes.
- Airbnb uses the same type of method. See [Applying Deep Learning to Airbnb Search](https://medium.com/airbnb-engineering/applying-deep-learning-to-airbnb-search-7ebd7230891f) (KDD 2019, [arXiv:1810.09591](https://arxiv.org/pdf/1810.09591)).

`rank:ndcg` needs integer relevance grades. Thus, the script converts the weights 0.2, 0.5, 0.8, and 1.0 to the integer grades 0 to 4 (`LABEL_TO_GRADE` in the script). The order of the labels does not change.

### ONNX export

`onnxmltools` has no converter for `XGBRanker`. It has converters only for `XGBClassifier`, `XGBRegressor`, `XGBRFClassifier`, and `XGBRFRegressor`.

- At prediction time, a ranker and a regressor calculate the same value: the sum of the leaf values of the trees. Only the training gradient is different.
- Thus, `export_xgb_ranker_to_onnx()` exports the trained model with the regressor converter.
- The output of the ONNX model is identical, bit for bit, to the output of the `.predict()` method of the scikit-learn API. The script checks this at the end of each run.
- `OnnxRankingModelAdapterTest` also loads the exported file through the real Java serving code.

## Held-out evaluation

- **Split.** The script divides the data by user, not by row: 80% for training and 20% for evaluation. Thus, the pairs of one user cannot be in both sets. This gives 4,000 training users and 1,000 held-out users.
- **Size.** There are 276,284 labeled pairs: 138,142 positive and 138,142 negative.
- **Metric.** For each held-out user, the metric is the part of the pairs (positive, negative) that the model puts in the correct order.

### Results

The table shows the mean and the standard deviation over 5 seeds (42 to 46, `--ci-seeds 5`), with all 7 features.

| Model | Held-out pairwise accuracy |
|---|---|
| **Linear (logistic regression, all 7 features)** | **0.8751 ± 0.0025** |
| Pointwise regression (MLP), for comparison | 0.8700 ± 0.0035 |
| **Pairwise XGBoost `rank:ndcg` (the model in use)** | **0.8687 ± 0.0019** |
| Popularity only | 0.7040 ± 0.0045 |
| Category match only | 0.6787 ± 0.0032 |
| `base_score_proxy` only | 0.4649 ± 0.0022 (approximately random, as expected for this feature alone) |
| Random | 0.5000 |

**The linear model is the most accurate.** A linear combination of the 7 features (0.8751) is better than the other two real models on each of the 5 seeds. The more complex models (the MLP and the gradient-boosted trees) give no real advantage on these features.

**Why the project uses XGBoost and not the linear model:**

- The linear model trains on a pointwise objective: logistic regression on `label > 0`. Its training objective does not agree with the pairwise metric, although its result on that metric is good.
- Thus, the correct conclusion is not "use the linear model". The correct conclusion is: "these 7 features do not need a complex model."
- The next useful test is a linear model with a pairwise loss, for example a RankNet-style loss. Do this test before you use the plain logistic regression model, or before you add more model capacity. This test is not done yet.

## Known limitations

- **`base_score_proxy` is different in training and serving.** The difference is real and not solved. To solve it, the script must make training pairs from real `search-service` calls, not from the clickstream positions. This is not in the scope of the project.
- **The category signal is too strong.** In the synthetic clickstream, each user takes 70% of their sessions from a preferred category (see `CLICKSTREAM.md` in the WANDS repository). Thus, `category_match` is a stronger signal than in real data. The model probably gives too much weight to category preference.
- **The negatives are not hard negatives.** The script selects negatives at random from the products with clickstream history. Hard negatives, for example products on the same results page that the user did not click, would make a more difficult and more realistic task. This is a possible next step.
- **The linear result can be a property of the synthetic data.** Real user behavior usually has more nonlinear interactions, for example between category preference and price sensitivity. The 7 features here are mostly independent aggregates. A larger feature set on real data can give a better result for the nonlinear models.
- **No feature measures how well a product matches the query.** Only `base_score_proxy` and the category match give an approximate signal. A query-product similarity feature will probably improve the ranking more than a different model type. [docs/EVALUATION.md](../docs/EVALUATION.md) shows the effect of this limit on the offline evaluation.
