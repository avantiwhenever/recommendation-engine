# Training the neural ranker

`train_neural_ranker.py` trains the small MLP served by
`recommender-service`'s `NeuralRankingStrategy` (via `OnnxRankingModelAdapter`
+ ONNX Runtime). This doc covers what it actually does, the real held-out
numbers it produces, and — honestly, per this project's culture (see the
sibling `search` project's own `WRITEUP.md`) — two methodological bugs found
and fixed while building it, since the first two runs produced misleadingly
perfect-looking numbers that turned out to be measuring the wrong thing.

## Reproduce

```sh
pip install -r requirements.txt
python3 train_neural_ranker.py --seed 42
```

Writes `../models/neural-ranker/model.onnx`. Deterministic given the same
seed (Python's `random` and `numpy`'s RNG are both seeded).

## Data & labels

From `data/clickstream.csv` (~171K events, ~5,000 users): for every
`(user, product)` pair the user ever interacted with, take the max event
weight across their events for that product (`view`=0.2, `click`=0.5,
`add_to_cart`=0.8, `purchase`=1.0). That's the graded implicit label.

**Negative sampling** — the part that went wrong twice:

1. **First attempt**: sampled negatives uniformly from all ~43K WANDS
   products. Result: 99.98% held-out pairwise accuracy. Looked great, was
   actually measuring almost nothing — ~97% of WANDS products never appear
   in the synthetic clickstream at all (sessions only draw from `label.csv`'s
   judged candidates per query), so a random negative was overwhelmingly "a
   product with zero clickstream footprint anywhere," and `popularity_log`
   alone trivially separates that.
2. **Second attempt** (hard negatives): restricted the sampling pool to
   products that *do* appear somewhere in the clickstream (nonzero
   popularity). Result: still 99.99%. Investigating further found the real
   bug wasn't the sampling pool — it was `base_score_proxy`.
3. **The actual bug**: `base_score_proxy` (feature 1, `sigmoid(1/position)`)
   was being computed from the real observed position for positive pairs,
   but negatives always got a fixed sentinel value lower than any real
   position could produce (since real positions are 1–15, `sigmoid(1/20)` is
   strictly below `sigmoid(1/15)`, the lowest a positive could score). That
   made `base_score_proxy` alone a **near-perfect trivial separator** between
   positive and negative pairs — not a meaningful signal, just "was this
   pair ever recorded with a real position at all."
4. **The fix**: negatives now get a position sampled from the *empirical
   distribution* of real positions in the dataset, so the feature can't
   trivially distinguish positives from negatives by construction alone.

Current sampling: hard negatives (nonzero-popularity products only) +
empirical-distribution position proxy, 1:1 positive:negative ratio per user.

## Features (order matters — must exactly match `NeuralRankingStrategy.buildFeatures` in Java)

| # | Feature | Train-time source | Serve-time source | Skew? |
|---|---|---|---|---|
| 0 | category match | product.csv category vs. user's most-frequent interacted top-level category, both from clickstream+product.csv | Same, from `ClickstreamRepositoryPort` + candidate's category | None |
| 1 | base_score_proxy | `sigmoid(1/position)`, position from clickstream's own `position` column (real for positives, empirically-sampled for negatives) | `sigmoid(search-service's real relevance score)` | **Yes, real skew** — there's no actual search call behind a training pair, so this is a proxy for "how well-ranked was this originally," not the same quantity as a live BM25/cosine score. Documented, not hidden. |
| 2 | popularity_log | `log1p(weighted event sum)` from clickstream | Same formula, same clickstream source | None |
| 3 | co_occurrence_log | `log1p(session co-occurrence with user's interaction history)` | Same formula, same clickstream source | None |
| 4 | avg_rating / 5.0 | product.csv | product.csv (via search-service's candidate metadata) | None |
| 5 | rating_count_log | `log1p(product.csv rating_count)` | Same | None |

Only feature 1 has genuine train/serve skew, and it's the weakest feature by
itself post-fix (see below) — the model doesn't appear to lean on it heavily.

## Model & held-out evaluation

Small MLP (`sklearn.neural_network.MLPRegressor`, hidden layers 16→8, ReLU),
trained with an 80/20 **user-level** split (not row-level, to avoid a user's
pairs leaking across train/holdout) — 4,000 train users, 1,000 held-out users,
276,284 total labeled pairs (138,142 positive / 138,142 negative).

**Metric**: pairwise ranking accuracy — for each held-out user, the fraction
of (positive, negative) product pairs the model scores in the correct order.
This is an AUC-style metric appropriate for graded, non-binary labels.

**Final numbers** (seed 42, after both fixes above):

| Model | Held-out pairwise accuracy |
|---|---|
| **Neural ranker (this model)** | **0.7972** |
| Popularity-only baseline | 0.7093 |
| Category-match-only baseline | 0.6751 |
| base_score_proxy-only baseline | 0.4667 (≈random — confirms the leakage fix worked) |
| Random baseline | 0.5000 |

The neural ranker beats every single-feature baseline, including the
strongest one (popularity), by combining signals — a real, if modest,
improvement. It is **not** a 99.98%-style number, and that's the honest
result: this is a small model on synthetic implicit feedback, not a
state-of-the-art recommender. `base_score_proxy` alone being ≈random
confirms the earlier leakage is actually gone, not just hidden differently.

## Known limitations

- `base_score_proxy`'s train/serve skew (above) is real and unresolved —
  fixing it properly would mean generating training pairs from actual
  `search-service` calls rather than clickstream-recorded positions, which
  wasn't in scope here.
- The synthetic clickstream's category-preference generation (each user
  drawing 70% of sessions from a preferred category — see the WANDS repo's
  `CLICKSTREAM.md`) makes `category_match` a stronger-than-real-world signal;
  a model trained here likely overweights category preference relative to
  what it would learn on noisier real user behavior.
- Negative sampling is still uniform-random within the "has clickstream
  footprint" pool, not true hard negatives (e.g., products shown on the same
  results page but not clicked) — that would be a meaningfully harder and
  more realistic task, left as a follow-up.
