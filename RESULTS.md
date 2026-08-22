# Recommender Evaluation Results

Offline evaluation of each recommendation strategy against 2568 held-out clickstream sessions (20% of users, seed 42) — implicit relevance
grades derived from observed event severity per product per session (view=0, click=1, cart=2, purchase=3).
Regenerate with `./scripts/run-recommender-eval.sh`.

| Strategy | nDCG@5 | MRR | Recall@5 | Precision@5 | p95 latency (ms) |
|---|---|---|---|---|---|
| None | 0.5048 | 0.5796 | 0.6120 | 0.2301 | 0 |
| Popularity | 0.5301 | 0.5849 | 0.6417 | 0.2407 | 0 |
| Collaborative Filtering | 0.5837 | 0.5963 | 0.7296 | 0.2943 | 0 |
| Bandit Exploration | 0.4615 | 0.5363 | 0.5713 | 0.2162 | 0 |
| Neural Ranking | 0.5693 | 0.5923 | 0.7166 | 0.2937 | 0 |

_Recall@5 is near-ceiling by construction for sessions whose candidate pool is close to size 5 (the synthetic clickstream generator uses page sizes 8-15) — nDCG@5 and Precision@5 are the more discriminative metrics here since they depend on rank order within the top 5, not just presence in an already-small, mostly-fully-retrieved pool._

_`Popularity` and `Collaborative Filtering` can inject products absent from a session's original candidates; this offline eval has no signal on whether a user would have engaged with a product they were never shown, so injected products always score as irrelevant here — their real-world value from serendipitous discovery is not captured by these numbers. See EvalCli's class Javadoc._

_`Bandit Exploration` scoring below `None` (the unmodified baseline) is expected, not a bug — it deliberately trades ranking quality for exploration (occasionally promoting a lower-ranked candidate), the standard explore/exploit tradeoff per arXiv:2207.00109 and arXiv:2106.10898. This offline eval only measures exploitation quality on already-observed sessions, so it structurally can't credit exploration's real purpose — surfacing under-exposed products over time — the same kind of offline/online eval gap noted above for the injecting strategies._

_All p95 latencies round to 0ms — every strategy here is in-memory arithmetic over a small candidate list (no ONNX/gRPC/disk I/O in the hot path even for `Neural Ranking`'s forward pass), genuinely sub-millisecond rather than an unmeasured placeholder._
