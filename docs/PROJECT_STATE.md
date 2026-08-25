# Project state

This doc exists so a new session (or a human) with zero prior context can
understand what this project is, what's architecturally non-negotiable, and
what operational quirks of its dependencies are worth knowing before
re-deriving them from scratch. It describes the system as it stands, not a
history of how it got here.

## What this project is

A **React + GraphQL recommendation engine** over the
[WANDS](https://github.com/wayfair/WANDS) furniture catalog: a Pinecone-backed
search service, a pluggable recommendation-strategy layer (popularity,
collaborative filtering, bandit exploration, an MMR diversity decorator, and
an ONNX-served neural ranker) that can add, remove, or re-rank a baseline
search result set, and a GraphQL gateway in front of both — grounded in real
recommender-systems literature and industry engineering practice, not an
arbitrary hack. Sibling portfolio project to
[`search`](https://github.com/avantiwhenever/search), a hybrid lexical/
semantic search system over the same catalog.

Three architectural rules are **non-negotiable design constraints**:
1. Every Java service is **hexagonal architecture** (ports & adapters) —
   `domain`/`application`/`port` packages never import Spring, gRPC stubs, or
   the Pinecone SDK; only `adapter` and `config` do.
2. **Java services talk to each other over gRPC only.** No REST between
   `graphql-gateway`, `search-service`, and `recommender-service`.
3. **GraphQL exists only at the frontend-facing edge** (`graphql-gateway`).
   Nothing downstream of the gateway speaks GraphQL or REST to another Java
   service. (Talking directly to Pinecone from `recommender-service` is a
   direct infrastructure dependency, not inter-service traffic, so this rule
   doesn't apply to it — see `VectorSimilarityPort`'s Javadoc.)

## Recommendation strategies

Six selectable strategies (`Strategy`/`RecommenderStrategy` enum, mirrored
across the proto contract, `recommender-service`'s domain layer, and the
GraphQL schema):

1. **None** — passthrough baseline (control group), still subject to the
   gateway's eligibility-selection stage (see below).
2. **Popularity** — reranks by a blend of search relevance and
   clickstream-derived popularity; can inject globally popular products
   absent from the original candidates.
3. **Collaborative filtering** — item-item CF using adjusted-cosine
   similarity over session co-occurrence (Sarwar, Karypis, Konstan & Riedl,
   WWW 2001), weighted more heavily for the current browsing session than
   all-time history. Falls back to a named popularity blend for users with
   no history at all.
4. **Bandit exploration** — Thompson Sampling over per-category arms,
   warm-started from historical engagement and conditioned on the requesting
   user's category profile (Etsy's OPAR pattern, Spotify's calibrated-bandit
   pattern).
5. **Neural ranking** — a gradient-boosted pairwise ranking model (XGBoost,
   `rank:ndcg`), served via ONNX Runtime, over 7 features including
   same-session category overlap.
6. **Diverse popularity** — Popularity, then an MMR (maximal marginal
   relevance) re-rank pass on top, trading some ranking quality for category
   spread. `DiversityAwareStrategy` is a decorator that can wrap any
   strategy's output, not just Popularity.

`recommender-service` also exposes a `VectorSimilarityPort` (Pinecone-backed
"find similar products," reusing `search-service`'s embedding index),
consumed by `DiversityAwareStrategy` (see "Known simplifications" below for
what it's used for and what it isn't).

## Retrieval pipeline

`search-service` does hybrid dense + lexical retrieval: Pinecone (dense,
`bge-small-en-v1.5`) fused with an embedded, in-memory BM25 index via
Reciprocal Rank Fusion — no Elasticsearch/Lucene/Solr, staying Pinecone-only
infra. `graphql-gateway`'s `SearchOrchestrationUseCase` runs a multi-stage
pipeline on top: widen the request to a real candidate pool (200), apply a
hard eligibility selection stage (drop zero-social-proof candidates, cut to
50 by score) uniformly regardless of strategy, hand the survivors to
whichever strategy runs, then truncate to the caller's requested `topK`. An
optional `categoryFilter`/`minRating` argument on the GraphQL `search` query
applies the same kind of hard filter before that pipeline runs.

## Offline evaluation

`recommender-service`'s `EvalCli` scores every strategy against held-out
clickstream sessions in three ways, all in `RESULTS.md`:
- **Implicit clickstream eval** — relevance grades derived from the same
  synthetic clickstream the strategies read from (circular ground truth,
  documented as such in `RESULTS.md`).
- **Independent WANDS relevance eval** — scored against WANDS' own human
  relevance judgments, which no strategy is trained or tuned against. The
  more trustworthy of the two; all six strategies cluster tightly here
  (nDCG@5 in the 0.90-0.97 range) rather than one strategy clearly winning.
- **Off-policy (IPS) evaluation** — an item-level Inverse Propensity Scoring
  estimate of each strategy's real reward, using the synthetic clickstream's
  exact, documented logging policy (`avantiwhenever/WANDS`'s
  `CLICKSTREAM.md`). Reports raw and propensity-clipped estimates plus an
  effective-sample-size diagnostic alongside the point estimate.

Sessions are replayed in strict timestamp order (`TemporalClickstreamIndex`)
so a held-out session's features never see events from after that session.

## Verified-working operational facts

- **JDK 26** — Docker base images are `maven:3.9.16-eclipse-temurin-26` and
  `eclipse-temurin:26-jre-jammy`, same tags the sibling `search` project uses.
- **`protoc-gen-grpc-java`'s macOS binaries are x86_64 even for the
  "aarch_64" classifier**, across every version checked (1.53-1.73) — meant
  to run via Rosetta 2 on Apple Silicon. Rosetta 2
  (`softwareupdate --install-rosetta --agree-to-license`) is required on a
  fresh Apple Silicon machine to build this repo.
- **Pinecone Java client is pinned to `3.1.0`, not the latest (6.x)**:
  `pinecone-client` 4.0.0+ requires a `vector_type` field in index-describe
  API responses that Pinecone Local's fixed API version (`2025-01`) doesn't
  return, causing a hard client-side deserialization failure on
  `describeIndex`/`createServerlessIndex`. 3.1.0 is the newest version whose
  `IndexModel` doesn't reference `vector_type` at all.
- **Pinecone Local's control-plane host needs a URL scheme**
  (`http://pinecone-local:5080`), but the data-plane host/port never needs
  separate construction — `Pinecone.getIndexConnection(indexName)` resolves
  it internally via `describeIndex`. See
  `rec-support/.../pinecone/PineconeVectorStore.java`'s class Javadoc.
- **`ghcr.io/pinecone-io/pinecone-local` is a single-platform (x86_64-only)
  image with no shell inside it** — `docker-compose.yml` pins
  `platform: linux/amd64` and has no healthcheck (a CMD-based healthcheck
  can't run without a shell). Services depending on it use plain
  `depends_on` (start-order only) and retry their first Pinecone connection
  with backoff.
- **`recommender-service` connects to the same `wands-products` Pinecone
  index `search-service` owns**, as a read-only consumer — it never calls
  `ensureServerlessIndex` (unlike `search-service`'s own bean), since it
  isn't the index's owner.
- **A live gRPC channel to Pinecone Local can drop the very first query
  after a fresh connection** — observed in production, not just in theory:
  `io.grpc.StatusRuntimeException: UNKNOWN: channel closed`, wrapping a
  `ClosedChannelException`, on the first `similarProductIds` call right
  after a container restart, even though the connection itself succeeded at
  startup. This is a `RuntimeException`, not Pinecone's own
  `PineconeException` type, so `PineconeVectorSimilarityAdapter` catches
  `RuntimeException` broadly rather than just `PineconeException` — a
  narrower catch would let this specific error propagate as a GraphQL
  `INTERNAL_ERROR` instead of degrading to "no similar items found" the way
  the port's contract promises. Retrying the same request immediately
  succeeds, consistent with a one-time cold-channel hiccup rather than a
  persistent connectivity problem.
- **Pinecone Local has a real, measured concurrent-query capacity ceiling**
  — a single-process, in-memory emulator, not built for concurrent-query
  throughput. `DiversityAwareStrategy`'s embedding lookups (up to 50 per
  request, one per candidate) run concurrently via a small fixed thread
  pool for latency (a fully sequential version measured ~12.8s per live
  request; parallelizing brought that to ~3-5s, but raising the pool from
  10 to 25 threads made no further difference — the ceiling is Pinecone
  Local's, not client-side thread count). At pool size 10, a tight
  back-to-back burst of several `DIVERSE_POPULARITY` requests (e.g.
  `scripts/capture_demo_snapshots.py` capturing every demo query in
  sequence) transiently overloaded Pinecone Local badly enough to fail an
  *unrelated* request — a plain `COLLABORATIVE` query failed too, since
  `search-service`'s own baseline Pinecone query (needed by every strategy)
  couldn't get through while this class's lookups were saturating it.
  Pinecone Local recovered on its own within seconds, no restart needed.
  Lowering the pool to 5 threads made the failure stop reproducing across
  repeated full capture runs; `scripts/capture_demo_snapshots.py` also
  gained its own retry-with-backoff as a second, independent safeguard,
  since a one-off batch tool firing requests in a tighter, more uniform
  burst than real user traffic ever would is reasonable to make resilient
  on its own. See `DiversityAwareStrategy`'s class Javadoc for the full
  account.
- **Build order**: `rec-support` must be `mvn install`ed to `~/.m2` before
  other modules build against it via `mvn -pl <module> test` (not `-am`, to
  avoid racing on `rec-support`'s own `target/` directory when multiple
  modules are being worked on).
- **`data/` and `models/` are gitignored**, fetched via
  `scripts/download-data.sh` / `scripts/download-models.sh` — the real WANDS
  catalog (42,994 products), the real clickstream (170,990 events / 5,000
  users), and the real `bge-small-en-v1.5` ONNX weights (128MB, fp32).
- **Pinecone Local has no persistence** — a fresh `docker compose up` (or a
  restarted `pinecone-local` container) needs the ingestion CLI re-run
  before search returns results.
- **Local host-port conflicts**: `graphql-gateway`/`web`'s host ports are
  overridable via `GATEWAY_HOST_PORT`/`WEB_HOST_PORT` env vars (not sticky
  across separate `docker compose up` invocations — supply them every time).
  Vite's `VITE_GRAPHQL_URL` is baked in at image build time; if a rebuild
  doesn't seem to pick up a changed build arg, use `docker compose build
  --no-cache` rather than trusting the layer cache.

## Known simplifications / deliberate scope cuts

- `VectorSimilarityPort` (real Pinecone-backed "find similar products" in
  `recommender-service`) is consumed by `DiversityAwareStrategy`, blended
  with its category/product-class proxy — see that class's Javadoc for why
  it's blended (max of the two signals) rather than substituted, and why
  one lookup per candidate up front, not one per pairwise comparison.
  `NeuralRankingStrategy`'s zero-footprint-item fallback still uses the
  category/product-class proxy only, not this port.
- The GraphQL `search` query's `categoryFilter`/`minRating` arguments are a
  hard eligibility filter, but there's no pagination cursor and no
  per-result debug/explain field showing which strategy/feature contributed
  to a score.
- A caller requesting `topK` larger than `SearchOrchestrationUseCase`'s
  internal `ELIGIBLE_POOL_SIZE` (50) gets fewer results than requested,
  silently — the selection-stage pool cap is fixed regardless of `topK`.
- Proto stubs are generated independently in all three consuming modules
  rather than via one shared generated-stubs artifact. Simpler for a small
  polyglot repo; costs a little redundant compilation, no functional
  downside since gRPC messages are wire-compatible regardless of which
  JVM/module produced the generated class.
- Pinecone Local has no persistence and a 100K-vector cap — a documented
  local-dev/demo choice, not a production claim (see README).
- The IPS estimator is item-level (each item's presence in the top-5 treated
  as an independent action), not a full listwise estimator.
- The synthetic clickstream (`avantiwhenever/WANDS`'s `CLICKSTREAM.md`) is
  fabricated, not real user behavior — documented explicitly in that repo.
- `Bandit Exploration`'s per-arm priors are fixed at construction from
  historical data; there's no live traffic loop to update them from reward.
- `CollaborativeFilteringStrategy`/`NeuralRankingStrategy`'s session-recency
  weighting uses a fixed multiplier, not a real time-decay function — the
  proto carries an unordered session product-ID list, not per-event
  timestamps.
