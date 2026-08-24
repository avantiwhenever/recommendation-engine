# TODO — fixing and replacing what's actually built

This is not a wishlist of new features bolted onto a working system. Every
item below **replaces or corrects something already in the codebase that
doesn't hold up** — found by a staff-engineer-level critical review (see
`docs/PROJECT_STATE.md` for the review itself if it's archived there, or
re-run the review), then matched against real, cited engineering techniques
from companies that actually build search/recsys at scale.

Ordered by severity: **P0 items fix things that are mislabeled or
methodologically broken** (the code does something other than what its name
and docs claim). **P1 items close real capability gaps** that make this a
stronger, more honest system. **P2 items are architecture/contract
cleanups** that fell out of the review but aren't urgent correctness bugs.

Each item names the exact file to touch, what's wrong with it today, and a
cited reference for the replacement technique where one exists.

---

## P0 — fix things that are mislabeled or methodologically broken

### 1. `BanditExploreStrategy` is not a bandit — replace with a real one
**File**: `recommender-service/src/main/java/com/avanti/recengine/recommender/domain/strategy/BanditExploreStrategy.java`

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

### 2. `CollaborativeFilteringStrategy` is unnormalized co-occurrence counting, not item-item CF
**Files**: `recommender-service/src/main/java/com/avanti/recengine/recommender/domain/strategy/CollaborativeFilteringStrategy.java`, `adapter/out/clickstream/CsvClickstreamRepositoryAdapter.java`

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

### 3. Neural ranker's training loss doesn't match its own eval metric
**Files**: `training/train_neural_ranker.py`, `training/TRAINING.md`

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

### 4. Offline eval ground truth is circular — add an independent eval path
**Files**: `recommender-service/src/main/java/com/avanti/recengine/recommender/eval/EvalCli.java`, `RESULTS.md`

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

### 5. Feature duplication between Java (serving) and Python (training) — already caused one bug
**Files**: `recommender-service/.../domain/strategy/NeuralRankingStrategy.java` (`buildFeatures`), `training/train_neural_ranker.py`

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

### 6. `search-service` is dense-retrieval-only — no lexical fallback
**File**: `search-service/src/main/java/com/avanti/recengine/search/`

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

### 7. No diversity mechanism anywhere — a top-5 can be 5 near-duplicates
**Files**: new — a decorator/wrapper strategy in `recommender-service/.../domain/strategy/`

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

### 8. `recommender-service` has zero Pinecone access — no real second retrieval source
**Files**: `recommender-service/src/main/java/com/avanti/recengine/recommender/port/out/`, new adapter

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

### 9. Single-pass reranking — no real multi-stage retrieval→cheap-cut→heavy-rerank
**Files**: `graphql-gateway/.../application/SearchOrchestrationUseCase.java`, `search-service` topK handling

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

### 10. Cold start is silent and implicit, not a designed policy
**Files**: `recommender-service/.../domain/strategy/CollaborativeFilteringStrategy.java`, `NeuralRankingStrategy.java`, `config/RecommenderConfig.java`

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

### 11. All history is treated as one flat signal — no session-level recency weighting
**Files**: `proto/recommender_service.proto` (`RecommendRequest`), `CollaborativeFilteringStrategy.java`, `NeuralRankingStrategy.java`

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

### 12. `SearchRequest`/`RecommendRequest` proto contracts are too thin for the ceremony wrapped around them
**File**: `proto/search_service.proto`, `proto/recommender_service.proto`

**What's wrong**: `SearchRequest` has only `query` + `top_k` — no filters,
facets, pagination cursor, or explain/debug fields, despite the heavyweight
hexagonal/gRPC architecture around it.

**Add**: filter fields (category, price range, rating floor), a pagination
cursor, and an optional debug/explain field on the response so a caller
can see which strategy/feature contributed to a given score (useful for
both the demo UI's hover cards and for debugging strategy behavior).

---

### 13. Document the architecture/complexity tradeoff explicitly in the README
**File**: `README.md`

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

1. **#4 (eval circularity)** first — every other number in this repo is
   currently being measured against a circular ground truth; fixing this
   changes how every subsequent change should be judged.
2. **#3 (training/eval objective mismatch)** and **#5 (feature duplication)**
   next — both are cheap, both directly address documented bugs.
3. **#1 (real bandit)** and **#2 (real CF)** — the two mislabeled strategies,
   independent of each other, can be done in either order.
4. **#6 (hybrid retrieval)** and **#8 (embedding retrieval in recommender-service)**
   — the two retrieval-capability gaps.
5. **#7, #9, #10, #11** — capability additions, roughly independent,
   prioritize by what's most interesting to demo.
6. **#12, #13** — cleanup, do whenever convenient.
