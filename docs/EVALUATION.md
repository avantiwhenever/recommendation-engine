# Evaluation

This document explains how the project measures the recommendation strategies, and what the results show.
[RESULTS.md](../RESULTS.md) has the full tables. The evaluation harness generates that file.

## Method

### Test data

- The harness uses the synthetic clickstream (see [DATA.md](DATA.md)).
- It keeps 20% of the users as a held-out set (random seed 42). This gives 2,568 held-out sessions.
- Each session has a query and the list of products that the user saw, in the original order. This list is the input for each strategy.

### No data from the future

The harness replays all sessions in time order.
It scores a held-out session with the data from earlier sessions only.
Then it adds that session to the data.
Thus, no strategy can use events that occur after the session that it scores.

### Three measurements

Each strategy runs one time for each session. The harness then scores the same output in three ways.

| Measurement | Ground truth | What it tells you | Weakness |
|---|---|---|---|
| **Clickstream** | The strongest event for each product in the session: view 0, click 1, add to cart 2, purchase 3 | Does the strategy put the products that users engaged with first? | Circular. The data generator used the human relevance grades to make the clicks. |
| **Human relevance** | Wayfair's human labels for the query: Exact 2, Partial 1, Irrelevant 0 | Does the strategy show relevant products? | It cannot measure personalization, because the labels are for each query, not for each user. |
| **Off-policy (IPS)** | Observed rewards, weighted by how probable each outcome was under the original order | What reward would users give if they saw this order? | High variance. The table shows an effective sample size to make this visible. |

A product that a strategy adds (a product that the user did not see) always gets a score of 0. Offline data cannot show if a user would like a product that they never saw. Thus, the results for Popularity, Collaborative filtering, and Diverse popularity are a lower limit.

### Metrics

| Metric | Meaning |
|---|---|
| nDCG@5 | Quality of the order of the first 5 results. Higher grades in higher positions give a higher score. |
| MRR | 1 divided by the position of the first relevant result |
| Recall@5 | The part of all relevant products that is in the first 5 results |
| Precision@5 | The part of the first 5 results that is relevant |

nDCG@5 and MRR are the most useful metrics to compare strategies. The other two depend more on the size of each session.

## Results summary

| Strategy | nDCG@5, clickstream | nDCG@5, human relevance | IPS estimate (clipped) |
|---|---|---|---|
| None (baseline) | **0.505** | 0.967 | **13.14** |
| Popularity | 0.477 | **0.972** | 11.86 |
| Collaborative filtering | 0.488 | 0.969 | 12.67 |
| Bandit exploration | 0.457 | 0.945 | 12.76 |
| Neural ranking | 0.333 | 0.961 | 12.06 |
| Diverse popularity | 0.457 | 0.906 | 10.52 |

### What the results show

- **No strategy is better than the baseline on the clickstream metric or on IPS.** The data generator ordered each session by the human relevance grades. Thus, the original order is already very good, and each change from it costs points.
- **Popularity and Collaborative filtering are a little better than the baseline on human relevance.** These two strategies move relevant products higher.
- **Bandit exploration and Diverse popularity are lower by design.** They give up some ranking quality to show more categories. Offline data cannot measure the long-term value of exploration or diversity.
- **Neural ranking has the lowest clickstream score.** The model has no feature that measures how well a product matches the query. It orders products by engagement signals instead. The next step is to add a query-product similarity feature.

These results are reported as measured. A good evaluation must show where a method does not work.

## Ranking model training

[training/TRAINING.md](../training/TRAINING.md) gives the full training method. The table shows the held-out pairwise accuracy, as the mean of 5 random seeds:

| Model | Pairwise accuracy |
|---|---|
| Logistic regression, all 7 features | 0.875 |
| Pointwise neural network (comparison only) | 0.870 |
| **XGBoost, pairwise `rank:ndcg` (the model in use)** | **0.869** |
| Popularity only | 0.704 |
| Category match only | 0.679 |

The linear model is a little more accurate than XGBoost on this task. Thus, these 7 features do not need a complex model. The project keeps XGBoost because its training objective is pairwise, the same as the metric. TRAINING.md gives the full discussion.

## Run the evaluation

```bash
./scripts/run-recommender-eval.sh
```

This command replaces `RESULTS.md`. The output is deterministic: the same data and code always give the same numbers. Only the latency column can change between runs.
