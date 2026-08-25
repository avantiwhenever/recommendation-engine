# TODO — fixing and replacing what's actually built

This is not a wishlist of new features bolted onto a working system. Every
item below **replaces or corrects something already in the codebase that
doesn't hold up** — found by a staff-engineer-level critical review, then
matched against real, cited engineering techniques from companies that
actually build search/recsys at scale. See `docs/PROJECT_STATE.md`'s "M7"
section for a summary of what the review found and what fixing it changed.

Ordered by severity: **P0 items fix things that are mislabeled or
methodologically broken** (the code does something other than what its name
and docs claim). **P1 items close real capability gaps** that make this a
stronger, more honest system. **P2 items are architecture/contract
cleanups** that fell out of the review but aren't urgent correctness bugs.

Each item names the exact file to touch, what's wrong with it today, and a
cited reference for the replacement technique where one exists.

---

## P0 — fix things that are mislabeled or methodologically broken

### 1. ✅ DONE — `BanditExploreStrategy` is not a bandit — replace with a real one
**File**: `recommender-service/src/main/java/com/avanti/recengine/recommender/domain/strategy/BanditExploreStrategy.java`

**Result**: rebuilt as Thompson Sampling over per-category arms (Beta(α,β)
priors warm-started from real historical engagement via
`ClickstreamRepositoryPort`), context-conditioned on the requesting user's
category profile, with a windowed calibration cap (not a whole-response
percentage cap — that turns out to be mathematically vacuous over a fixed
candidate pool, a bug caught during implementation, not shipped). Verified
with a 1000-trial statistical test confirming historically-favored
categories win their slot in >85% of trials, plus a context-conditioning
test and a 200-seed calibration-invariant check — not just a single
deterministic-seed snapshot, which is what made the old implementation
possible to mislabel in the first place. Honestly documented limitation:
priors are fixed at construction, not updated from live reward, since this
project has no live traffic loop to update from.

**What's wrong**: no arms, no value estimates, no reward signal, no update
loop, and it never reads its own `RecommendationContext` parameter. It's a
randomized adjacent-position swap with probability 0.15 — structurally
indistinguishable from noise injection. Calling this "epsilon-greedy" is a
category error: epsilon-greedy means "explore randomly, otherwise exploit
the best *current value estimate*" — there's no value estimate here to
exploit.

**Replace with**: model each candidate's top-level category as a bandit
arm (bounds the arm space to something tractable), maintain per-arm reward
estimates from clickstream engagement, select via UCB1 or Thompson
Sampling, and condition selection on `context.userId()`'s category profile
(already available via `ClickstreamRepositoryPort.userProfile()`, just
unused by this strategy today).

**References**:
- Etsy — [OPAR: Online Personalized Attribute-based Re-ranker](https://www.etsy.com/codeascraft/building-a-platform-for-serving-recommendations-at-etsy) — arm-per-attribute pattern
- Spotify Research (2025) — [Calibrated Recommendations with Contextual Bandits on Spotify Homepage](https://research.atspotify.com/2025/9/calibrated-recommendations-with-contextual-bandits-on-spotify-homepage) — context-conditioned selection + a calibration constraint so exploration doesn't just randomly reshuffle
- arXiv:2207.00109 (already cited in the codebase) — real bandit theory the current implementation doesn't actually apply

---

### 2. ✅ DONE — `CollaborativeFilteringStrategy` is unnormalized co-occurrence counting, not item-item CF
**Files**: `recommender-service/src/main/java/com/avanti/recengine/recommender/domain/strategy/CollaborativeFilteringStrategy.java`, `adapter/out/clickstream/CsvClickstreamRepositoryAdapter.java`

**Result**: took option (a), the adjusted-cosine normalization — added
`ClickstreamRepositoryPort.itemSimilarity(a, b) = coOccurrence / sqrt(marginalCount(a) * marginalCount(b))`,
switched the strategy's boost and its injection ranking (`relatedProducts`)
to use it instead of raw `log1p(coOccurrenceCount)`. Verified with a test
built specifically to demonstrate the fix: a "popular-but-unrelated" pair
(10,000-session marginals, 40 raw co-occurrence) vs. a "niche-but-affinitive"
pair (80-session marginals, 30 raw co-occurrence) — raw counts favor the
popular pair (40 > 30), but normalized similarity correctly favors the
niche pair 0.0335 vs 0.0040, an 8.4x reversal. The skip-gram embedding
option (b) was not attempted — the cheap normalization fix was sufficient
to correct the specific bug identified. Citation corrected too: the
strategy no longer cites the online/bandit-flavored CF papers (which
assume reward updating this static strategy doesn't do) as if they
justified this technique; now cites Sarwar, Karypis, Konstan & Riedl
(WWW 2001), the actual source of the normalization used.

**What's wrong**: `coOccurrenceCount` is a raw pairwise count with zero
normalization by either item's marginal popularity —
`boost = CO_OCCURRENCE_WEIGHT * Math.log1p(coOccurrence)` just log-damps
the raw count. Two globally popular products will score high on
"co-occurrence" even with no genuine affinity beyond both being popular —
exactly the failure mode real item-item similarity metrics (cosine, PMI,
adjusted-cosine) exist to correct.

**Replace with**: either (a) the cheap fix — normalize by
`sqrt(popularity_a * popularity_b)` (adjusted cosine), or (b) the real
upgrade — learned product embeddings trained via skip-gram over
clickstream session sequences (each session's product sequence treated
like a sentence), stored alongside the existing Pinecone index or as a
second in-memory matrix (43K items is small enough).

**References**:
- Airbnb (KDD 2018) — [Real-time Personalization using Embeddings for Search Ranking at Airbnb](https://medium.com/airbnb-engineering/listing-embeddings-for-similar-listing-recommendations-and-real-time-personalization-in-search-601172f7603e) ([ACM paper](https://dl.acm.org/doi/10.1145/3219819.3219885)) — skip-gram over session click sequences
- Classic reference for the cheap fix: adjusted-cosine item-item similarity (Sarwar et al., the technique this strategy's docstring implicitly claims but doesn't implement)

---

### 3. ✅ DONE — Neural ranker's training loss doesn't match its own eval metric
**Files**: `training/train_neural_ranker.py`, `training/TRAINING.md`

**Result**: switched to `xgboost.XGBRanker(objective="rank:ndcg")`, a
genuinely pairwise objective. Added the missing linear-baseline comparison
and 5-seed confidence intervals. Honest finding, consistent across all 5
seeds: **a plain logistic-regression linear combination of the same 6
features (0.8046 ± 0.0035 mean held-out pairwise accuracy) beats both the
new pairwise XGBoost model (0.7995 ± 0.0037) and the old pointwise MLP it
replaced (0.8018 ± 0.0040)**. Fixing the objective mismatch didn't produce
a new best model — it revealed that neither tested model's extra capacity
earns a measurable advantage over a linear boundary on this feature set.
XGBoost was kept in service anyway, specifically because its training
objective is the one that actually matches what's measured (this task's
whole point), not because it scored highest — the linear model, despite
its higher number, was itself trained pointwise and wouldn't have resolved
the objective-mismatch problem either. See `training/TRAINING.md` for the
full account.

**What's wrong**: trains `sklearn.MLPRegressor` via **pointwise** regression
against a graded implicit label, but `TRAINING.md`'s own held-out metric is
**pairwise** ranking accuracy (0.7972). The thing being optimized isn't the
thing being measured — pointwise regression spends gradient on getting
every absolute score right, when what the eval (and the actual
recommendation task) cares about is relative order.

**Replace with**: a genuinely pairwise training objective — swap to
XGBoost's `rank:pairwise`/`rank:ndcg` objective, or hand-roll a pairwise
hinge loss over the same (positive, negative) pairs the eval already
constructs. Also run the missing baseline: a linear combination of all 6
features (not just single-feature ablations) to confirm the MLP's
nonlinearity is earning its complexity, and report a confidence interval
on the headline number (single-seed 0.7972 vs. 0.7093 has no reported
variance).

**References**:
- Airbnb (KDD 2019) — [Applying Deep Learning to Airbnb Search](https://medium.com/airbnb-engineering/applying-deep-learning-to-airbnb-search-7ebd7230891f) ([arXiv:1810.09591](https://arxiv.org/pdf/1810.09591)) — LambdaRank: pairwise loss weighted by ΔNDCG, concentrating learning pressure on getting the top of the ranking right
- LinkedIn — [GLMix / Photon ML](https://engineering.linkedin.com/blog/2016/06/open-sourcing-photon-ml) ([GDMix follow-up](https://engineering.linkedin.com/blog/2020/gdmix--a-deep-ranking-personalization-framework)) — fixed-effect global model + small per-category random-effect correction, a realistic middle ground given this project's data volume can't support real per-user models

---

### 4. ✅ DONE — Offline eval ground truth is circular — add an independent eval path
**Files**: `recommender-service/src/main/java/com/avanti/recengine/recommender/eval/EvalCli.java`, `RESULTS.md`

**Result — the most consequential fix in this whole list**: added a second
ground truth (WANDS `label.csv`'s original human judgments, via a new
`WandsLabelLoader`) scored against the exact same reranked candidates as
the existing clickstream-derived eval, reported as two separate tables in
`RESULTS.md`. Also fixed the point-in-time leak from item #5 in the same
pass (a new `TemporalClickstreamIndex` replays sessions in timestamp
order, so a held-out session's features never see future events).

**This overturned the previous headline result.** With the temporal leak
fixed alone (still the circular clickstream ground truth), Neural Ranking
collapsed from 0.5692 to 0.3312 nDCG@5 — well below baseline — and
Collaborative Filtering's apparent win shrank to a statistical wash (0.5013
vs. baseline's 0.5048). Against the independent WANDS-judgment ground
truth, no strategy shows a clear win over baseline on the metrics that are
actually comparable (nDCG@5, MRR) — all five cluster around 0.94–0.97.
**"Collaborative filtering is the best strategy" does not hold up** — it
was an artifact of temporal leakage plus circular ground truth. Current
`RESULTS.md` states this explicitly.

**Update — the IPS/counterfactual estimator (this item's second half) is
now also done**: `recommender-service/.../eval/IpsEvaluator.java` computes
an item-level Inverse Propensity Scoring estimate per strategy, using the
exact, documented cascade click-model constants from
`avantiwhenever/WANDS`'s `CLICKSTREAM.md` (not estimated) — implementable
here specifically because, unlike almost any real production system, this
project's synthetic logging policy is fully known. Reports both a raw and
a propensity-clipped (floor 1e-3) estimate plus a Hájek/Kish effective-
sample-size diagnostic, so the honest instability of IPS (a rare high-
reward event with a tiny propensity can dominate the sum) is visible
rather than hidden behind a single clean-looking number. See RESULTS.md's
"Off-policy (IPS) evaluation" section for the real numbers and the ESS
caveat that should accompany them.

**What's wrong** (the most consequential finding in the review): the
synthetic clickstream's session composition, click/cart/purchase
probabilities are all direct functions of the same WANDS relevance grade
the eval later scores against. `None` baseline's nDCG@5 is already
substantially explained by `1/position` correlating with `grade` by
construction. `RESULTS.md`'s strategy ranking (CF > Neural > Popularity >
None > Bandit) should currently be read as "which strategy best recovers
the synthetic generator's own parameters," not "which strategy is best."

**Replace/add**: a second, independent eval path scored against WANDS'
**original human relevance judgments** (`label.csv`'s Exact/Partial/
Irrelevant grades directly, not the clickstream-derived implicit labels),
plus a proper off-policy/counterfactual (IPS) estimator — unusually
implementable here because, unlike almost every real production system,
this project's synthetic clickstream generator has a **known, exact,
documented logging policy** (the cascade click model in
`avantiwhenever/WANDS`'s `CLICKSTREAM.md`).

**References**:
- Netflix — [Using Interleaving in Online Experiments to Accelerate Algorithm Innovation at Netflix](https://netflixtechblog.com/using-interleaving-in-online-experiments-to-accelerate-algorithm-innovation-at-netflix-a04ee392ec55) — the "score against an independent ground truth" principle, adapted here since live interleaving needs real traffic this project doesn't have
- Spotify Research — [Towards a Fair Marketplace: Counterfactual Evaluation of the Trade-off Between Relevance, Fairness & Satisfaction](https://research.atspotify.com/publications/towards-a-fair-marketplace-counterfactual-evaluation-of-the-trade-off-between-relevance-fairness-satisfaction-in-recommendation-systems)
- Criteo — [Offline A/B Testing for Recommender Systems](https://arxiv.org/pdf/1801.07030) — IPS estimator reweighting logged rewards by inverse propensity

---

### 5. ✅ DONE — Feature duplication between Java (serving) and Python (training) — already caused one bug
**Files**: `recommender-service/.../domain/strategy/NeuralRankingStrategy.java` (`buildFeatures`), `training/train_neural_ranker.py`

**Result**: took the golden-vector-test option — `training/feature_parity_fixtures.csv`
is a shared fixture (5 hand-computed cases) read by both a new
`FeatureParityTest.java` (calls `NeuralRankingStrategy.buildFeatures`
directly) and a new `test_feature_parity.py`, so a future change to either
side that breaks parity is caught by both `mvn test` and the Python test
suite, from one shared source of truth, instead of relying on a comment
table nobody re-reads. One real bug caught during implementation: the
first tolerance (1e-9) failed on `popularity_log` — not a logic error,
`buildFeatures` returns `float[]` (32-bit), so exact double-precision
equality was never achievable; loosened to 1e-6 with a comment explaining
why. The point-in-time-correctness half of this item was folded into item
#4's `TemporalClickstreamIndex` fix (same eval-side files, done together).

**What's wrong**: `TRAINING.md` maintains a hand-written "must exactly
match" comment table between the Java feature builder and the Python
trainer's feature builder — two independently maintained implementations
of the same 6 features. This exact gap already caused the real
`base_score_proxy` train/serve skew bug documented in `TRAINING.md`.
Separately, `ClickstreamRepositoryPort` loads the *entire* clickstream CSV
into memory at startup with no time boundary — `popularityScore`/
`coOccurrenceCount` for a given eval session are computed from data that
includes events happening *after* that session, a point-in-time-
correctness leak structurally similar to the two bugs already found and
fixed in training.

**Replace/add**: a single shared feature-spec (a YAML/JSON file both sides
read, or at minimum a golden-vector CI test that runs identical inputs
through both the Java and Python feature builders and diffs the outputs);
and restrict `EvalCli`'s aggregation to events strictly before each
session's timestamp when computing features for eval.

**References**:
- Uber — [Meet Michelangelo: Uber's Machine Learning Platform](https://www.uber.com/en-JP/blog/michelangelo-machine-learning-platform/) ([Scaling Michelangelo](https://www.uber.com/en-JP/blog/scaling-michelangelo/)) — the core lesson isn't the distributed infra, it's one canonical feature definition shared by training and serving
- DoorDash — [Building a Declarative Real-Time Feature Engineering Framework (Riviera)](https://careersatdoordash.com/blog/building-a-declarative-real-time-feature-engineering-framework/) — point-in-time correctness as a named, enforced property

---

## P1 — close real capability gaps

### 6. ✅ DONE — `search-service` is dense-retrieval-only — no lexical fallback
**File**: `search-service/src/main/java/com/avanti/recengine/search/`

**Result**: added a hand-rolled, in-memory Okapi BM25 index (`domain/lexical/BM25Index.java`,
no Elasticsearch/Lucene/Solr — this project's whole premise is Pinecone-only,
no self-hosted search server, so the lexical index is a lightweight embedded
one built once at startup from `product.csv`, not a second server dependency),
fused with the existing Pinecone dense retrieval via Reciprocal Rank Fusion
(`domain/RrfFusion.java`, same formula as the sibling `search` project's own
RRF fusion, ported as this project's own copy). `SearchProductsService` now
widens both sub-retrievers to a shared candidate pool before fusing, rather
than fusing two already-truncated top-K lists. Verified live: a query for an
exact, distinctive brand token ("baldwin prestige alcott") now correctly
surfaces the branded product at #1, with visibly RRF-fused scores rather than
raw cosine similarity — confirmed via a real GraphQL query against the live
stack, not just a passing unit test.

**What's wrong**: pure `bge-small-en-v1.5` embedding search, no BM25/lexical
path, no hybrid fusion, no query understanding. Known-worse than hybrid on
exact-match queries (SKUs, model numbers, brand names) — the sibling
`search` project itself concedes this by building RRF hybrid fusion. This
repo's docs frame the gap as "a different problem" rather than a real
retrieval-quality regression relative to the sibling project.

**Fix**: port the sibling `search` project's lexical + RRF fusion approach,
or at minimum add a keyword-match fallback path for queries that look
exact-match-shaped.

**Reference**: no new citation needed — the fix already exists and is
documented in the sibling `/Users/avanti/codebase/search` repo's own
README and `WRITEUP.md`.

---

### 7. ✅ DONE — No diversity mechanism anywhere — a top-5 can be 5 near-duplicates
**Files**: `recommender-service/.../domain/strategy/DiversityAwareStrategy.java` (new), wired as `Strategy.DIVERSE_POPULARITY`

**Result**: a real `DiversityAwareStrategy` decorator — wraps any existing
strategy, runs it first, then a greedy MMR (maximal marginal relevance) pass:
`mmr = λ·relevance − (1−λ)·maxSimilarity(candidate, alreadySelected)`, with
`λ` a constructor parameter (default 0.7) and similarity a category/
product-class proxy. Wired into the system end-to-end as a new selectable
`Strategy.DIVERSE_POPULARITY` value (proto enum, domain enum in both
services, GraphQL schema enum, `RecommenderConfig` wiring) — not just a
library class nobody calls. Verified live: a `DIVERSE_POPULARITY` query for
"coffee table" visibly injects an office chair and a bed frame into what
would otherwise be an all-coffee-table top-8, and scores measurably lower
than plain `Popularity` in both eval tables (0.9061 vs 0.9721 nDCG@5,
independent eval) — the honest, expected cost of the diversity tradeoff, not
a bug. Honestly documented gap: similarity is category/class-based, not
real embedding cosine distance — swapping in item #8's `VectorSimilarityPort`
is a clear next step, not implemented here.

**What's wrong**: all 5 strategies optimize a single scalar score per item
with no diversity penalty. No category/brand diversity constraint exists
anywhere in the ranking path.

**Add**: an MMR-style (maximal marginal relevance) post-processing pass —
a `DiversityAwareStrategy` decorator wrapping any existing strategy's
output, penalizing candidates too similar (by category, or by Pinecone
embedding cosine distance once #8 below exists) to items already selected
higher in the list.

**Reference**: Netflix — [Netflix Recommendations: Beyond the 5 stars, Part 1](http://techblog.netflix.com/2012/04/netflix-recommendations-beyond-5-stars.html) / [Part 2](http://techblog.netflix.com/2012/06/netflix-recommendations-beyond-5-stars.html)

---

### 8. ✅ DONE (port built; not yet consumed by a strategy) — `recommender-service` has zero Pinecone access — no real second retrieval source
**Files**: `recommender-service/.../port/out/VectorSimilarityPort.java` (new), `.../adapter/out/pinecone/PineconeVectorSimilarityAdapter.java` (new), `config/RecommenderConfig.java`

**Result**: `VectorSimilarityPort.similarProductIds(productId, topK)`, backed
by a `PineconeVectorSimilarityAdapter` that reuses `rec-support`'s
`PineconeVectorStore` (extended with a `queryById` method — Pinecone's
server-side "find neighbors of this already-stored vector" call, no
client-side re-embedding needed) against the exact same `wands-products`
index `search-service` populates — a direct infrastructure dependency, per
the original architecture plan's own carve-out for this case, not a gRPC
call to `search-service`. `RecommenderConfig` now wires a `PineconeVectorStore`
bean (read-only consumer, unlike `search-service`'s index-owning bean) and
the port bean. Verified against the real live index (`PineconeVectorSimilarityAdapterSmokeTest`,
requires the Docker network — passed for real, not skipped). **Honest gap**:
built and wired as an available bean, but no strategy calls it yet — items
#7 and #10 both flag it as their natural next step (real embedding
similarity instead of a category proxy) rather than silently ignoring it.

**What's wrong**: every strategy only reranks/injects within the fixed
list `search-service` already returned. There's no "more like this"
embedding-based candidate generator — `PROJECT_STATE.md` confirms this was
a deliberate scope cut, not an oversight, but it means there's no genuinely
new retrieval signal anywhere downstream of `search-service`.

**Add**: a `VectorSimilarityPort` in `recommender-service` (gRPC call to
`search-service`, or a shared outbound Pinecone adapter) for "find nearest
neighbors of product X" — enables real embedding-based candidate
generation, not just reranking.

**Reference**: Pinterest (KDD 2022) — [ItemSage: Learning Product Embeddings for Shopping Recommendations at Pinterest](https://arxiv.org/abs/2205.11728) — one shared embedding space reused across retrieval surfaces (the multi-modal aggregation doesn't map since WANDS has no images, but the "reuse the same embedding space" pattern does)

---

### 9. ✅ DONE — Single-pass reranking — no real multi-stage retrieval→cheap-cut→heavy-rerank
**Files**: `graphql-gateway/.../application/SearchOrchestrationUseCase.java`

**Result**: `SearchOrchestrationUseCase` now asks `search-service` for a
widened pool (`WIDE_POOL_SIZE = 200`, not just the caller's display `topK`),
applies a hard eligibility **selection** stage — drops zero-social-proof
candidates (`ratingCount == 0`), then cuts to `ELIGIBLE_POOL_SIZE = 50` by
`search-service`'s own score (free, no second scoring pass) — distinct in
kind from any strategy's own scoring, and applies it *uniformly*, including
to `NONE` (a deliberate change: `NONE` is no longer a byte-for-byte
passthrough of raw search results, only of the eligibility-filtered ones).
Only the reduced set is handed to whichever strategy runs, and the final
output is truncated to the caller's `topK` only at the very end. Verified
live: a `COLLABORATIVE` query for "coffee table" now surfaces a genuinely
different top result than the pre-fix narrow-candidate-list version — a
product the old ~6-10-candidate pool never had a chance to include. New unit
tests cover the widening, the selection-stage filter/cut, and final
truncation.

**What's wrong**: exactly one candidate list, exactly one scoring pass.
`NeuralRankingStrategy`'s ONNX forward pass runs on whatever `search-service`
returned (currently `topK` as small as 5-10), never on a wide, cheaply-cut
pool.

**Add**: widen retrieval (pull top-200 from Pinecone) → cheap
heuristic/linear cut to ~50, including a hard eligibility **selection**
stage (filter zero-rating-count/out-of-category candidates) distinct from
scoring → run the expensive strategy only on the reduced set.

**References**:
- LinkedIn — [Making Your Feed More Relevant – Part I](https://engineering.linkedin.com/blog/2015/11/making-your-feed-more-relevant--part-i) — First Pass Rankers per source, Second Pass Ranker combines/rescores
- DoorDash — [Powering Search & Recommendations at DoorDash](https://careersatdoordash.com/blog/powering-search-recommendations-at-doordash/) — explicit separation of **selection** (hard eligibility filters) from **ranking**

---

### 10. ✅ DONE (item cold-start is a partial fix) — Cold start is silent and implicit, not a designed policy
**Files**: `recommender-service/.../domain/strategy/CollaborativeFilteringStrategy.java`, `NeuralRankingStrategy.java`, `config/RecommenderConfig.java`

**Result**: `CollaborativeFilteringStrategy` now takes an explicit, named
`PopularityBoostStrategy` fallback (a shared instance from `RecommenderConfig`,
not a second independently-constructed copy), triggered only when a user has
**both** zero all-time history and zero session signal — a stated policy, not
a silent no-op. New-item cold start (features that would collapse to 0 for
the ~97% of the catalog with no clickstream footprint) is a **partial** fix:
it depends on item #8's `VectorSimilarityPort`, which landed in a different
parallel workstream and isn't wired into `NeuralRankingStrategy` here. What's
implemented instead is the fallback available without it — feature 1
(category match) and the new feature 7 (session category overlap, item #11)
both still produce real, non-zero signal for a zero-footprint item, since
they come from the candidate's own metadata, not clickstream history. Wiring
in real embedding similarity is a documented, explicit follow-on, not a
hidden gap.

**What's wrong**: `CollaborativeFilteringStrategy` just returns
`List.copyOf(baseResults)` when a user profile is empty; `NeuralRankingStrategy`'s
category-match feature silently goes to 0. `TRAINING.md` already notes
~97% of WANDS products never appear in the clickstream at all, so their
`popularity_log`/`co_occurrence_log` features silently collapse to 0 too —
both a new-user and a new-item cold-start gap, neither with a stated policy.

**Add**: an explicit `NewUserStrategy` (or branch in `RecommenderConfig`)
blending `PopularityBoostStrategy` with category-probing exploration for
users with no history; and for items with zero clickstream footprint,
substitute a content-similarity score from `search-service`'s existing
embedding (cheap — reuses infra already built) in place of the missing
behavioral features.

**References**:
- Netflix — [Recommendations: Beyond the 5 stars](http://techblog.netflix.com/2012/04/netflix-recommendations-beyond-5-stars.html) — blending explicit preference/popularity for new users, transitioning to personalized signal as data accumulates
- Amazon Science — [Exploring Heterogeneous Metadata for Video Recommendation with Two-Tower Model](https://www.amazon.science/publications/exploring-heterogeneous-metadata-for-video-recommendation-with-two-tower-model) — a metadata-only tower so zero-interaction items still get a meaningful signal from day one

---

### 11. ✅ DONE — All history is treated as one flat signal — no session-level recency weighting
**Files**: `proto/recommender_service.proto`, `graphql-gateway` (schema + orchestration + gRPC client adapter), `CollaborativeFilteringStrategy.java`, `NeuralRankingStrategy.java`, `training/train_neural_ranker.py`

**Result**: added `recent_product_ids` to `RecommendRequest`, threaded end
to end — proto → recommender-service's `RecommendationContext` → an optional
`recentProductIds: [ID!]` GraphQL argument on `search`, through the gateway's
orchestration layer and gRPC client adapter. `CollaborativeFilteringStrategy`
weights a session match (2.5x) more heavily than the same signal from
all-time history. `NeuralRankingStrategy` gained a 7th feature — same-session
category overlap, built from real per-session grouping in
`train_neural_ranker.py` (using clickstream.csv's actual `session_id` column,
not a synthetic proxy) — bumping `FEATURE_COUNT` from 6 to 7 and requiring a
full retrain (`feature_parity_fixtures.csv`/`FeatureParityTest`/
`test_feature_parity.py` all updated together, keeping the Java/Python golden-
vector parity test from item #5 honest). Real, reported retrain result: the
7th feature raised every model's held-out pairwise accuracy by ~6-7 points
(XGBoost 0.7995→0.8687, linear baseline 0.8046→0.8751, old MLP 0.8018→0.8700)
— a genuinely informative feature, without changing which model wins (the
linear baseline still does; same honest finding as item #3).

**What's wrong**: `ClickstreamRepositoryPort` loads all-time history as
one undifferentiated profile — there's no distinction between "this
browsing session" and "everything this user has ever done."

**Add**: a `recentProductIds`/current-session field on the
`RecommendRequest` proto, and a recency-decayed blend (session signal
weighted far more heavily than all-time history) — plus, on
`NeuralRankingStrategy`, a new 7th feature: same-session recency-weighted
category overlap, distinct from the existing all-time `co_occurrence_log`.

**References**:
- Pinterest — [Real-time User Signal Serving for Feature Engineering](https://medium.com/pinterest-engineering/real-time-user-signal-serving-for-feature-engineering-ead9a01e5b) — short-term vs. long-term interest, recency-aware weighting
- Etsy — [Leveraging Real-Time User Actions to Personalize Etsy Ads (ADPM)](https://www.etsy.com/codeascraft/leveraging-real-time-user-actions-to-personalize-etsy-ads) — live action stream inferring current-session intent, distinct from long-term modeling

---

## P2 — architecture/contract cleanups (not urgent correctness bugs)

### 12. ✅ DONE (filters only — pagination cursor and debug/explain field still open) — `SearchRequest`/`RecommendRequest` proto contracts are too thin for the ceremony wrapped around them
**File**: `proto/search_service.proto`, `search-service` (`SearchProductsUseCase`/`SearchProductsService`/`SearchGrpcService`), `graphql-gateway` (`SearchPort`/`GrpcSearchServiceClientAdapter`/`SearchOrchestrationUseCase`/schema/controller)

**Result — a deliberately scoped, partial fix**: added `category_filter`
(case-insensitive substring match against `categoryHierarchy`) and
`min_rating` to `SearchRequest`, threaded end to end as optional GraphQL
arguments (`categoryFilter: String`, `minRating: Float`) on `search`,
applied as a hard eligibility filter on the widened candidate pool (same
selection stage as item #9, before any strategy runs — applies uniformly
regardless of strategy). Verified live: filtering an "outdoor patio
furniture" query by `categoryFilter: "Bedroom"` correctly returns zero
results; filtering by `minRating: 4.5` correctly narrows to only ≥4.5-rated
candidates. **Not done, honestly left open**: a pagination cursor and a
per-result debug/explain field (which strategy/feature contributed to a
score) — both still real gaps, not silently dropped from scope, just not
implemented in this pass. Applied post-fusion, not pre-retrieval, so a
narrow filter can legitimately return fewer than `topK` results — a stated
limitation, not a bug; over-fetching until enough eligible results are
found is a further follow-on.

**What's wrong**: `SearchRequest` has only `query` + `top_k` — no filters,
facets, pagination cursor, or explain/debug fields, despite the heavyweight
hexagonal/gRPC architecture around it.

**Add**: filter fields (category, price range, rating floor), a pagination
cursor, and an optional debug/explain field on the response so a caller
can see which strategy/feature contributed to a given score (useful for
both the demo UI's hover cards and for debugging strategy behavior).

---

### 13. ✅ DONE — Document the architecture/complexity tradeoff explicitly in the README
**File**: `README.md`

**Result**: added an explicit "The honest tradeoff" paragraph right after
the "Why this project" section — states plainly that hexagonal-plus-gRPC-
only-plus-four-services is more service boundary than a 43K-product,
no-real-traffic system needs, that it's here to demonstrate the pattern on
purpose, and that a system this size built to actually ship would likely be
one service with clean internal module boundaries.

**What's wrong**: the review's core architecture finding is that
hexagonal-architecture-plus-gRPC-only-plus-four-services is disproportionate
to a single-node, 43K-product, no-real-traffic system — legitimate as a
skills demonstration, not defensible as "what I'd ship." The README
currently frames this as "a deliberate architectural discipline" without
naming the tradeoff plainly.

**Add**: a short, honest paragraph — "this is more service boundary than
the problem needs; it's here to demonstrate gRPC/hexagonal competency, not
because 43K products need three JVMs" — matching this project's existing
honesty norms about every other limitation.

---

## Suggested execution order

**Everything in this document is now done, except two explicitly-scoped
partial items** — see each item's "Result" above for what actually happened,
not just what was planned.

- **P0 (#1–#5): done**, including the IPS/counterfactual estimator from
  item #4 (initially deferred, closed in the P1 pass below). All five
  landed together via four parallel workstreams (the eval fix and feature-
  parity fix shared eval-side files, so were done in one pass) — the
  eval-circularity fix overturned the previous "Collaborative Filtering
  wins" headline result; current `RESULTS.md` is the only version to trust.
- **P1 (#6–#11): done**, via five more parallel workstreams (hybrid
  retrieval, embedding-similarity port, diversity decorator, cold-start +
  session-recency bundled together since they share files, and the IPS
  estimator) plus one sequential follow-on (#9, multi-stage retrieval —
  held back a batch since it touches the same file as #12's filter
  plumbing). Two honest partial gaps remain, tracked explicitly rather than
  hidden: item #8's `VectorSimilarityPort` is built and live-tested but not
  yet consumed by any strategy (items #7 and #10 both name it as their
  natural next step); item #10's new-item cold-start fix is real but
  partial for the same reason.
- **P2 (#12–#13): done**, with #12 itself a deliberately scoped partial fix
  — category/rating filters are real and live-verified; a pagination cursor
  and a per-result debug/explain field are still open, not implemented here.

Execution note for future similar work: the same disjoint-file-ownership
parallel-fork pattern that worked for P0 scaled cleanly to this larger,
more interdependent P1/P2 batch too — the only real coordination points
were a few shared files two forks both needed (`RecommenderConfig.java`,
`proto/*.proto`), each resolved by having exactly one fork/session own the
final edit rather than two forks racing on the same file.
