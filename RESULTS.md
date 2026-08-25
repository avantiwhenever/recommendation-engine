# Recommender Evaluation Results

Offline evaluation of each recommendation strategy against 2568 held-out clickstream sessions (20% of users, seed 42), scored against **two independent ground truths** — read both tables' intro paragraphs before trusting either alone. Strategy features (popularity, co-occurrence) are point-in-time-correct: computed by replaying sessions in timestamp order, so a held-out session's features never include events from after that session. Regenerate with `./scripts/run-recommender-eval.sh`.

## Implicit clickstream eval

Ground truth: each session's strongest observed event per product (view=0, click=1, cart=2, purchase=3). **Circular** — the synthetic clickstream generator's session composition and click/cart/purchase probabilities are themselves a probabilistic function of the same WANDS `label.csv` grade used to construct the session's candidate order in the first place (see `WANDS/scripts/generate_clickstream.py`). A strategy scoring well here is partly just recovering the synthetic generator's own parameters, not demonstrating real recommendation quality — compare against the independent table below before drawing conclusions.

| Strategy | nDCG@5 | MRR | Recall@5 | Precision@5 | p95 latency (ms) |
|---|---|---|---|---|---|
| None | 0.5048 | 0.5796 | 0.6120 | 0.2301 | 0 |
| Popularity | 0.4765 | 0.5646 | 0.5770 | 0.2129 | 3 |
| Collaborative Filtering | 0.5013 | 0.5737 | 0.6109 | 0.2284 | 0 |
| Bandit Exploration | 0.4571 | 0.5184 | 0.5829 | 0.2191 | 0 |
| Neural Ranking | 0.3314 | 0.4042 | 0.4378 | 0.1652 | 0 |

## Independent WANDS relevance eval

Ground truth: Wayfair's original human relevance annotation (`label.csv`: Exact=2/Partial=1/Irrelevant=0) for each session's query — entirely independent of the clickstream generator's click-probability model, even though the candidate *ordering* was influenced by it. This is the table to trust for "is this strategy actually surfacing relevant products," at the cost of not crediting personalization at all: WANDS' judgments are query-level, not user-level, so a strategy that improves personalization without changing which products are objectively relevant to the query won't show a difference here.

| Strategy | nDCG@5 | MRR | Recall@5 | Precision@5 | p95 latency (ms) |
|---|---|---|---|---|---|
| None | 0.9673 | 0.9981 | 0.0608 | 0.9967 | 0 |
| Popularity | 0.9721 | 0.9981 | 0.0610 | 0.9900 | 3 |
| Collaborative Filtering | 0.9675 | 0.9981 | 0.0608 | 0.9928 | 0 |
| Bandit Exploration | 0.9458 | 0.9944 | 0.0588 | 0.9938 | 0 |
| Neural Ranking | 0.9638 | 0.9977 | 0.0608 | 0.9967 | 0 |

_Independent-eval Recall@5 is tiny (~0.06) compared to the clickstream eval's Recall@5 (~0.6) — not a bug, a scale mismatch: Recall's denominator is "all relevant items," and under WANDS' judgments that's every Exact/Partial product for the query (often 100+), while under the clickstream eval it's only the 8-15 products the synthetic generator ever showed in that session. Similarly, independent-eval Precision@5 is near-ceiling (~0.99) for nearly every strategy uniformly — expected, since the synthetic generator only ever samples session candidates from WANDS-judged (mostly Exact/Partial) products in the first place, so almost everything shown is already relevant by WANDS' standard regardless of ranking. nDCG@5/MRR (rank-order-sensitive, not just presence) are the metrics actually worth comparing between strategies in this table._

_Recall@5 is near-ceiling by construction for sessions whose candidate pool is close to size 5 (the synthetic clickstream generator uses page sizes 8-15) — nDCG@5 and Precision@5 are the more discriminative metrics here since they depend on rank order within the top 5, not just presence in an already-small, mostly-fully-retrieved pool._

_`Popularity` and `Collaborative Filtering` can inject products absent from a session's original candidates; neither eval has a signal on whether a user would have engaged with a product they were never shown, so injected products always score as irrelevant in both tables — their real-world value from serendipitous discovery is not captured by these numbers. See EvalCli's class Javadoc._

_`Bandit Exploration` scoring below `None` (the unmodified baseline) is expected, not a bug — it deliberately trades ranking quality for Thompson-Sampling exploration over category arms warm-started from real historical engagement (Etsy's OPAR pattern, Spotify's context-conditioned calibrated bandits — see BanditExploreStrategy's class Javadoc for both citations), the standard explore/exploit tradeoff. Neither offline eval can credit exploration's real purpose — surfacing under-exposed products over time — the same kind of offline/online eval gap noted above for the injecting strategies. Priors are fixed at construction from historical data, not updated from live reward — this project has no live traffic loop to update from; see the strategy's Javadoc._

_All p95 latencies round to 0ms — every strategy here is in-memory arithmetic over a small candidate list (no ONNX/gRPC/disk I/O in the hot path even for `Neural Ranking`'s forward pass), genuinely sub-millisecond rather than an unmeasured placeholder._
