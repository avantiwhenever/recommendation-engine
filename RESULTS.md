# Recommender Evaluation Results

Offline evaluation of each recommendation strategy against 2568 held-out clickstream sessions (20% of users, seed 42), scored against **two independent ground truths** — read both tables' intro paragraphs before trusting either alone. Strategy features (popularity, co-occurrence) are point-in-time-correct: computed by replaying sessions in timestamp order, so a held-out session's features never include events from after that session. Regenerate with `./scripts/run-recommender-eval.sh`.

## Implicit clickstream eval

Ground truth: each session's strongest observed event per product (view=0, click=1, cart=2, purchase=3). **Circular** — the synthetic clickstream generator's session composition and click/cart/purchase probabilities are themselves a probabilistic function of the same WANDS `label.csv` grade used to construct the session's candidate order in the first place (see `WANDS/scripts/generate_clickstream.py`). A strategy scoring well here is partly just recovering the synthetic generator's own parameters, not demonstrating real recommendation quality — compare against the independent table below before drawing conclusions.

| Strategy | nDCG@5 | MRR | Recall@5 | Precision@5 | p95 latency (ms) |
|---|---|---|---|---|---|
| None | 0.5048 | 0.5796 | 0.6120 | 0.2301 | 0 |
| Popularity | 0.4765 | 0.5646 | 0.5770 | 0.2129 | 2 |
| Collaborative Filtering | 0.4883 | 0.5671 | 0.5943 | 0.2206 | 0 |
| Bandit Exploration | 0.4552 | 0.5176 | 0.5830 | 0.2197 | 0 |
| Neural Ranking | 0.3343 | 0.4057 | 0.4403 | 0.1657 | 0 |
| Diverse Popularity | 0.4567 | 0.5598 | 0.5399 | 0.1994 | 0 |

## Independent WANDS relevance eval

Ground truth: Wayfair's original human relevance annotation (`label.csv`: Exact=2/Partial=1/Irrelevant=0) for each session's query — entirely independent of the clickstream generator's click-probability model, even though the candidate *ordering* was influenced by it. This is the table to trust for "is this strategy actually surfacing relevant products," at the cost of not crediting personalization at all: WANDS' judgments are query-level, not user-level, so a strategy that improves personalization without changing which products are objectively relevant to the query won't show a difference here.

| Strategy | nDCG@5 | MRR | Recall@5 | Precision@5 | p95 latency (ms) |
|---|---|---|---|---|---|
| None | 0.9673 | 0.9981 | 0.0608 | 0.9967 | 0 |
| Popularity | 0.9721 | 0.9981 | 0.0610 | 0.9900 | 2 |
| Collaborative Filtering | 0.9689 | 0.9981 | 0.0609 | 0.9905 | 0 |
| Bandit Exploration | 0.9447 | 0.9945 | 0.0589 | 0.9939 | 0 |
| Neural Ranking | 0.9615 | 0.9975 | 0.0607 | 0.9966 | 0 |
| Diverse Popularity | 0.9061 | 0.9981 | 0.0555 | 0.8916 | 0 |

_Independent-eval Recall@5 is tiny (~0.06) compared to the clickstream eval's Recall@5 (~0.6) — not a bug, a scale mismatch: Recall's denominator is "all relevant items," and under WANDS' judgments that's every Exact/Partial product for the query (often 100+), while under the clickstream eval it's only the 8-15 products the synthetic generator ever showed in that session. Similarly, independent-eval Precision@5 is near-ceiling (~0.99) for nearly every strategy uniformly — expected, since the synthetic generator only ever samples session candidates from WANDS-judged (mostly Exact/Partial) products in the first place, so almost everything shown is already relevant by WANDS' standard regardless of ranking. nDCG@5/MRR (rank-order-sensitive, not just presence) are the metrics actually worth comparing between strategies in this table._

_Recall@5 is near-ceiling by construction for sessions whose candidate pool is close to size 5 (the synthetic clickstream generator uses page sizes 8-15) — nDCG@5 and Precision@5 are the more discriminative metrics here since they depend on rank order within the top 5, not just presence in an already-small, mostly-fully-retrieved pool._

_`Popularity` and `Collaborative Filtering` can inject products absent from a session's original candidates; neither eval has a signal on whether a user would have engaged with a product they were never shown, so injected products always score as irrelevant in both tables — their real-world value from serendipitous discovery is not captured by these numbers. See EvalCli's class Javadoc._

_`Bandit Exploration` scoring below `None` (the unmodified baseline) is expected, not a bug — it deliberately trades ranking quality for Thompson-Sampling exploration over category arms warm-started from real historical engagement (Etsy's OPAR pattern, Spotify's context-conditioned calibrated bandits — see BanditExploreStrategy's class Javadoc for both citations), the standard explore/exploit tradeoff. Neither offline eval can credit exploration's real purpose — surfacing under-exposed products over time — the same kind of offline/online eval gap noted above for the injecting strategies. Priors are fixed at construction from historical data, not updated from live reward — this project has no live traffic loop to update from; see the strategy's Javadoc._

_All p95 latencies round to 0ms — every strategy here is in-memory arithmetic over a small candidate list (no ONNX/gRPC/disk I/O in the hot path even for `Neural Ranking`'s forward pass), genuinely sub-millisecond rather than an unmeasured placeholder._

## Off-policy (IPS) evaluation

The two tables above score each strategy's reranking as a static list against a grade — neither can estimate the *reward* (click/cart/purchase) users would actually have generated had a strategy's ranking been the one shown, since no user was ever shown it; only the clickstream's original logging-policy ranking was. This table closes that gap using Inverse Propensity Scoring (IPS), reweighting each *already-observed* outcome by the inverse probability the known cascade logging policy (`WANDS/CLICKSTREAM.md`'s exact click/cart/purchase model — not estimated) would have produced that outcome at the position it actually showed the item. This is unusually implementable here because, unlike almost every real production system, this project's synthetic clickstream has a fully known, documented logging policy. See Criteo's ["Offline A/B Testing for Recommender Systems"](https://arxiv.org/pdf/1801.07030) and Spotify Research's [counterfactual-evaluation work](https://research.atspotify.com/publications/towards-a-fair-marketplace-counterfactual-evaluation-of-the-trade-off-between-relevance-fairness-satisfaction-in-recommendation-systems), both cited in TODO.md item #4. Simplifying assumption, stated honestly: this is an *item-level* IPS estimator (each item's presence in the top-5 treated as an independent action), not a full listwise estimator — see `IpsEvaluator`'s class Javadoc.

| Strategy | Raw IPS estimate | Clipped IPS estimate (floor 1e-3) | Effective sample size | Scored items |
|---|---|---|---|---|
| None | 13.1409 | 13.1409 | 492.4 | 12719 |
| Popularity | 11.8597 | 11.8597 | 653.8 | 12719 |
| Collaborative Filtering | 12.6702 | 12.6702 | 541.1 | 12719 |
| Bandit Exploration | 14.4213 | 12.7886 | 464.1 | 12719 |
| Neural Ranking | 12.3425 | 12.0623 | 478.5 | 12719 |
| Diverse Popularity | 12.1502 | 10.5174 | 581.0 | 11481 |

_**Read the effective sample size (ESS) before trusting either estimate.** IPS's known failure mode is variance: a rare high-reward outcome (a purchase) logged for an item the policy was unlikely to produce that outcome for gets a tiny propensity and an enormous inverse weight, and can dominate the whole sum. ESS (Hájek/Kish: (Σw)²/Σw² over the clipped weights) estimates how many *effectively independent* samples the clipped estimate is really resting on — a value close to "scored items" means weights are fairly uniform and the estimate is stable; a value far below it means a handful of extreme-weight events are carrying the number, and it should be read as noisy/directional at best, not a precise point estimate. If the raw and clipped columns differ substantially, that's the clipping visibly doing its job, not a discrepancy to reconcile — the clipped number is the one to trust in that case._

_Reward scale (0.2/0.5/0.8/1.0 for view/click/cart/purchase) matches `TemporalClickstreamIndex`'s popularity weighting elsewhere in this codebase, for consistency — these are relative weights, not a probability or a currency amount, so compare estimates *between strategies* in this table, not against the 0-1 metrics in the tables above, which are on an unrelated scale._

_Injected products (Popularity/Collaborative Filtering can add items absent from a session's original candidates) contribute nothing here — an injected item has no logged position or observed outcome to reweight, the same honest gap already noted for the other two tables above._
