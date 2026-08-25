# Training the neural ranker

`train_neural_ranker.py` trains the model served by `recommender-service`'s
`NeuralRankingStrategy` (via `OnnxRankingModelAdapter` + ONNX Runtime). This
doc covers what it does, the real held-out numbers it produces, and —
honestly, per this project's culture (see the sibling `search` project's own
`WRITEUP.md`) — its known limitations.

## Reproduce

```sh
pip install -r requirements.txt
python3 train_neural_ranker.py --seed 42                 # train + export the model
python3 train_neural_ranker.py --seed 42 --ci-seeds 5     # also report a 5-seed confidence interval
python3 test_feature_parity.py                            # golden-vector feature-parity test (Python side)
```

Writes `../models/neural-ranker/model.onnx`. Deterministic given the same
seed (Python's `random` and `numpy`'s RNG are both seeded).

## Data & labels

From `data/clickstream.csv` (~171K events, ~5,000 users): for every
`(user, product)` pair the user ever interacted with, take the max event
weight across their events for that product (`view`=0.2, `click`=0.5,
`add_to_cart`=0.8, `purchase`=1.0). That's the graded implicit label.

**Negative sampling**:

- Sampled only from products that actually appear somewhere in the
  clickstream (nonzero popularity), not the full ~43K WANDS catalog. ~97% of
  WANDS products never appear in the synthetic clickstream at all (sessions
  only draw from `label.csv`'s judged candidates per query), so sampling
  uniformly from the whole catalog would make a random negative
  overwhelmingly "a product with zero clickstream footprint anywhere," which
  `popularity_log` alone would trivially separate.
- `base_score_proxy` (feature 1, `sigmoid(1/position)`) is computed from the
  real observed position for positive pairs, and from a position sampled
  from the *empirical distribution* of real positions in the dataset for
  negatives — not a fixed sentinel. A fixed sentinel lower than any real
  position could produce (real positions are 1-15) would make this feature
  alone a near-perfect trivial separator between positive and negative
  pairs, rather than a meaningful signal.

Current sampling: hard negatives (nonzero-popularity products only) +
empirical-distribution position proxy, 1:1 positive:negative ratio per user.

## Features (order matters — enforced by a test, not just a comment)

| # | Feature | Train-time source | Serve-time source | Skew? |
|---|---|---|---|---|
| 0 | category match | product.csv category vs. user's most-frequent interacted top-level category, both from clickstream+product.csv | Same, from `ClickstreamRepositoryPort` + candidate's category | None |
| 1 | base_score_proxy | `sigmoid(1/position)`, position from clickstream's own `position` column (real for positives, empirically-sampled for negatives) | `sigmoid(search-service's real relevance score)` | **Yes, real skew** — there's no actual search call behind a training pair, so this is a proxy for "how well-ranked was this originally," not the same quantity as a live cosine score. Documented, not hidden — see Known limitations. |
| 2 | popularity_log | `log1p(weighted event sum)` from clickstream | Same formula, same clickstream source | None |
| 3 | co_occurrence_log | `log1p(session co-occurrence with user's interaction history)` | Same formula, same clickstream source | None |
| 4 | avg_rating / 5.0 | product.csv | product.csv (via search-service's candidate metadata) | None |
| 5 | rating_count_log | `log1p(product.csv rating_count)` | Same | None |
| 6 | session_category_overlap | fraction of the training pair's real same-session products (via `clickstream.csv`'s `session_id`; one session chosen per pair — see `build_dataset`) sharing the candidate's top-level category | fraction of `RecommendationContext.recentProductIds()` sharing the candidate's top-level category | None — both sides resolve real session-scoped product IDs to category segments the same way (`session_category_segments` / `sessionCategorySegments`) |

**Feature parity is tested, not just documented.** `NeuralRankingStrategy.buildFeatures()`
(Java) and this script's `build_features()` (Python) are two independently-
maintained implementations of the same formulas. `feature_parity_fixtures.csv`
is a shared golden-vector fixture both `test_feature_parity.py` (Python) and
`recommender-service`'s `FeatureParityTest.java` (Java) read and assert
against for the 6 features that must be train/serve-identical (feature 1 is
intentionally excluded from cross-language equality, since its train-time
and serve-time formulas are different by design — each side is checked
against its *own* documented formula instead). An edit that breaks parity on
either side fails a test, not just a stale comment.

## Training objective: pairwise

The held-out evaluation metric is pairwise ranking accuracy — does the model
score the positive above the negative for each pair. The served model,
`xgboost.XGBRanker` with `objective="rank:ndcg"`, trains on that same
objective directly: LambdaMART-style, pairwise gradients weighted by the
`|ΔNDCG|` that swapping each pair would cause — the same family of technique
described in Airbnb's
[Applying Deep Learning to Airbnb Search](https://medium.com/airbnb-engineering/applying-deep-learning-to-airbnb-search-7ebd7230891f)
(KDD 2019, [arXiv:1810.09591](https://arxiv.org/pdf/1810.09591)). `rank:ndcg`
requires integer relevance grades rather than the continuous 0.2/0.5/0.8/1.0
weights — quantized to integer grades 0-4 (`LABEL_TO_GRADE` in the script),
which preserves the same relative ordering the labeling scheme already had,
just in the representation XGBoost's NDCG objective requires.

**ONNX export note**: `onnxmltools` has no direct `XGBRanker` converter
(only `XGBClassifier`/`XGBRegressor`/`XGBRFClassifier`/`XGBRFRegressor` are
registered) — a ranker's underlying tree ensemble predicts identically to a
regressor at inference time (same sum-of-leaf-values), only the *training*
gradient differs, so `export_xgb_ranker_to_onnx()` wraps the trained
booster and forces the regressor conversion path. Verified this produces
output bit-identical to the sklearn API's own `.predict()` (see the script's
own sanity check at the end of every run, and `OnnxRankingModelAdapterTest`'s
real-model tests, which load this exact exported file through the actual
Java serving path).

## Model & held-out evaluation

80/20 **user-level** split (not row-level, to avoid a user's pairs leaking
across train/holdout) — 4,000 train users, 1,000 held-out users, 276,284
total labeled pairs (138,142 positive / 138,142 negative).

**Metric**: pairwise ranking accuracy — for each held-out user, the fraction
of (positive, negative) product pairs the model scores in the correct order.

**Final numbers, 5-seed mean ± std** (seeds 42-46, `--ci-seeds 5`), 7 features:

| Model | Held-out pairwise accuracy (mean ± std) |
|---|---|
| **Linear (logistic regression, all 7 features)** | **0.8751 ± 0.0025** |
| Pointwise regression (MLP) comparison baseline | 0.8700 ± 0.0035 |
| **Pairwise XGBoost `rank:ndcg` (the model actually served)** | **0.8687 ± 0.0019** |
| Popularity-only baseline | 0.7040 ± 0.0045 |
| Category-match-only baseline | 0.6787 ± 0.0032 |
| base_score_proxy-only baseline | 0.4649 ± 0.0022 (≈random, as expected for a proxy feature alone) |
| Random baseline | 0.5000 |

**Reported honestly**: a simple linear combination of all 7 features
(0.8751) is consistently the best of the three real models across every one
of the 5 seeds tested — it beats both the pairwise XGBoost ranker actually
being served (0.8687) and the pointwise regression comparison baseline
(0.8700). None of the tested models' extra capacity (MLP nonlinearity,
gradient-boosted tree splits) earns a real advantage over a linear decision
boundary on this feature set.

**Why the served model is the pairwise XGBoost ranker, not the linear model
that scored higher**: the linear baseline is trained *pointwise* (logistic
regression on `label > 0`), so serving it wouldn't give this strategy a
training objective that matches its own held-out pairwise metric, even
though it happens to score well under that metric anyway. The honest
takeaway isn't "ship the linear model" — it's "these 7 features don't
currently justify a complex model, and a properly pairwise-trained linear
model (e.g. a linear model under a RankNet-style pairwise loss) would be a
reasonable next experiment before either shipping the plain logistic-
regression baseline or investing further in tree/network capacity." Left as
a follow-up, not resolved here.

## Known limitations

- `base_score_proxy`'s train/serve skew (above) is real and unresolved —
  fixing it properly would mean generating training pairs from actual
  `search-service` calls rather than clickstream-recorded positions, which
  isn't in scope here.
- The synthetic clickstream's category-preference generation (each user
  drawing 70% of sessions from a preferred category — see the WANDS repo's
  `CLICKSTREAM.md`) makes `category_match` a stronger-than-real-world signal;
  a model trained here likely overweights category preference relative to
  what it would learn on noisier real user behavior.
- Negative sampling is uniform-random within the "has clickstream footprint"
  pool, not true hard negatives (e.g., products shown on the same results
  page but not clicked) — that would be a meaningfully harder and more
  realistic task, left as a follow-up.
- **The linear-vs-nonlinear finding above is itself a signal about the
  synthetic data, not necessarily about real user behavior**: real implicit
  feedback typically has more nonlinear interaction structure (e.g.,
  category preference interacting with price sensitivity) than this
  project's 7 hand-picked, largely-independent aggregate features capture —
  a more expressive feature set on real data might show a different, more
  favorable result for the nonlinear models tested here.
- No semantic-similarity feature exists at all — none of the 7 features
  capture query-product relevance beyond the `base_score_proxy`/
  category-match proxies, which is likely a bigger lever on real ranking
  quality than the choice between linear/MLP/tree-based models tested here.
