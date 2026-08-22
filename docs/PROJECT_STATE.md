# Project state — read this first if picking up cold

This doc exists so a new Claude Code session (or a human) with zero memory of
prior conversations can figure out what this project is, what's already
decided, what's already verified working, and what's left to do — without
having to re-derive any of it from scratch. Update it as work progresses;
treat it as living memory, not a one-time snapshot.

## What this project is

A second portfolio project, sibling to `/Users/avanti/codebase/search` (a
Java hybrid lexical/semantic search system over the WANDS furniture
catalog). This one is a **React + GraphQL recommendation engine**: same
WANDS catalog, but backed by Pinecone instead of Elasticsearch, with a
pluggable recommendation-strategy layer (popularity, collaborative
filtering, bandit exploration, and an ONNX-served neural ranker) that can
add, remove, or re-rank a baseline search result set — grounded in real
arxiv recommender-systems literature rather than an arbitrary hack.

Three architectural rules were set explicitly by the user and are
**non-negotiable design constraints**, not suggestions:
1. Every Java service is **hexagonal architecture** (ports & adapters) —
   `domain`/`application`/`port` packages must never import Spring, gRPC
   stubs, or the Pinecone SDK; only `adapter` and `config` do.
2. **Java services talk to each other over gRPC only.** No REST between
   `graphql-gateway`, `search-service`, and `recommender-service`.
3. **GraphQL exists only at the frontend-facing edge** (`graphql-gateway`).
   Nothing downstream of the gateway speaks GraphQL or REST to another Java
   service.

## Where the full architecture plan lives

The complete, detailed architecture plan (approved by the user, including
the back-and-forth that shaped it) is at
`/Users/avanti/.claude/plans/lucky-forging-fairy.md` on this machine — but
that path is **local to this machine's Claude Code install and will not
exist in a fresh environment or a different session's filesystem view**.
This doc is the durable copy. If that plan file is ever unavailable, this
doc plus the "Key decisions" table in `README.md` fully substitute for it.

## Two upstream prerequisite tasks (already done)

Both already completed and pushed before this repo's work began — don't
redo them:
1. `/Users/avanti/codebase/search`: added 4 more demo queries (8 total) to
   the GitHub Pages strategy-comparison snapshot. Pushed to
   `avantiwhenever/search`.
2. `/Users/avanti/codebase/WANDS`: generated a synthetic ~5,000-user, 171K-
   event clickstream dataset (`dataset/clickstream.csv`), documented in that
   repo's `CLICKSTREAM.md` as synthetic and NOT part of the original Wayfair
   release. Pushed to `avantiwhenever/WANDS`. This is a **hard dependency**
   of `recommender-service` (popularity/collaborative/neural strategies all
   train on it) — fetched into this repo's `data/` via
   `scripts/download-data.sh`, not committed here.

## Build status (update this section as milestones land)

- **M0 — scaffold: DONE, verified, committed, pushed.** Parent Maven
  reactor (`rec-support`, `search-service`, `recommender-service`,
  `graphql-gateway`), shared `proto/` contracts with gRPC codegen verified
  working end-to-end, `rec-support` fully implemented and tested for real
  (not just compiled) against live infrastructure — see "Verified-working
  facts" below. Pushed to `avantiwhenever/recommendation-engine`.
- **M1 — search-service: DONE, verified, committed** (`0f0401c`). Hexagonal
  Pinecone-backed search + picocli ingestion CLI. Fork found and fixed two
  real Maven packaging bugs (shade-vs-Spring-Boot-repackage plugin
  ordering; gRPC's `NameResolverProvider` SPI file getting clobbered by
  unmerged shading) that only surfaced when actually running the built
  jars. Verified with a live Pinecone Local container + real ingested
  WANDS data + a `grpcurl` call returning correctly-ranked results.
- **M2 + M2.5 — recommender-service + neural ranking model: DONE, verified.**
  5 strategies (passthrough, popularity,
  collaborative-filtering, bandit, ONNX-based neural ranking), 18/18 tests
  passing. **Deviation from the original plan**: `NeuralRankingStrategy`'s
  "embedding cosine similarity" feature was replaced with a category-match
  proxy — no strategy ended up needing Pinecone/vector similarity, so
  `recommender-service` has no Pinecone dependency at all (removed from its
  `application.yml`; reads `product.csv` directly instead for catalog
  metadata). **Training pipeline caught its own bug**: the first two
  training attempts produced a suspicious ~99.98% held-out pairwise
  accuracy; traced to two real methodological bugs (negative sampling from
  the full catalog, then a trivial leakage where negatives always got a
  lower `base_score_proxy` than any real position could produce) — both
  fixed. Final honest result: **0.7972** neural ranker vs. 0.7093
  popularity-only, 0.6751 category-only, 0.4667 base-score-only (≈random,
  confirming the leak is gone), 0.5 random baseline. Full account in
  `training/TRAINING.md`. Real end-to-end smoke test against the live
  service with the actual trained model and all 5 strategies via `grpcurl`
  confirmed sensible, differentiated results per strategy.
- **M3 — graphql-gateway: DONE, verified.** Real schema, resolvers, gRPC
  client adapters to both backend services. 7/7 tests passing including
  real in-process gRPC round trips. **Bug found and fixed** (by this fork,
  then cleaned up by the main session once safe to touch the shared root
  pom): root `pom.xml` pinned `spring-graphql.version=1.3.3`, older than
  what `spring-boot-dependencies:3.5.16` itself manages (1.4.6) — the
  mismatch pulled an incompatible transitive `org.dataloader:java-dataloader`,
  breaking `GraphQlAutoConfiguration` at startup. Root pom now pins 1.4.6;
  the module-local workaround has been removed.
- **M4 — web (React UI): DONE, verified.** Vite + TS + Apollo Client, real component structure,
  loading/error states, humanized "source" badges. Verified via a clean
  `npm run build`, Vitest + RTL component tests against Apollo's
  `MockedProvider` (2/2 passing), and a real `docker build`/`docker run`
  round trip. **Host-level change**: Node.js wasn't installed on this
  machine — installed via `brew install node` (v26.7.0) to do this work.
  **Bug found and fixed in `docker-compose.yml`**: the `web` service passed
  `VITE_GRAPHQL_URL` as a runtime `environment:` var, but Vite bakes env
  vars in at build time — a static nginx-served bundle can't read a
  runtime env var, so it was a no-op. Fixed to a `build.args` entry.
- **M5 — IN PROGRESS as of this writing.** Done so far: full reactor
  build/test passing after integrating all four forks' work; JDK bumped to
  26 per explicit user request (see "Verified-working facts"); root
  `pom.xml`'s `spring-graphql` version fixed and `graphql-gateway`'s
  redundant local override removed; CI workflow
  (`.github/workflows/build.yml`) and Dependabot config
  (`.github/dependabot.yml`) written, adapted from the sibling `search`
  project's six-job posture for this polyglot/gRPC repo (JDK 26, added an
  `npm test`/`npm run build` step, matrixed `docker-lint`/`docker-image-scan`
  across all four Dockerfiles); `docker-compose.yml` fixed twice more (see
  below) beyond what the `web` fork already fixed. A real end-to-end
  `docker compose` run is in progress — check `git log --oneline -5` and
  `docker compose ps` to see how far it actually got before trusting "done":
  the honest way to tell is whether this section has an "end-to-end
  verified" bullet added below it, not just this bullet's presence.
  - **Docker VM disk exhaustion mid-build**: the Docker Desktop VM's disk
    allocation (~19.5GB) filled up building four images back-to-back
    (`no space left on device`). Fixed by `docker image prune -f`
    (dangling only, safe) then `docker image prune -a -f` once all target
    images existed (removes anything with zero referencing containers —
    verified safe because it never touches an image backing an existing
    container, running or stopped, so it can't disrupt unrelated projects
    sharing this Docker daemon).
  - **Host port conflicts with unrelated running projects**: this machine
    already has other projects' containers bound to 8080
    (`search-search-api-1`) and 5173 (`ai-image-generation-frontend-1`).
    Rather than stop those, `docker-compose.yml`'s `graphql-gateway` and
    `web` ports are now overridable via `${GATEWAY_HOST_PORT:-8080}` /
    `${WEB_HOST_PORT:-5173}` — verification used
    `GATEWAY_HOST_PORT=18080 WEB_HOST_PORT=15173 docker compose up -d`.
    (A `docker-compose.override.yml`-based approach was tried first and
    abandoned: Compose merges list-type keys like `ports` by concatenation,
    not replacement, so both the base and override port bindings were
    attempted simultaneously and the base one still conflicted.)
  - **The ingestion CLI must run inside the compose network, not on the
    host**: Pinecone Local's data-plane host discovery
    (`Pinecone.getIndexConnection`) returns whatever hostname the
    *container* was configured with via `PINECONE_HOST`
    (`pinecone-local` here, for other containers' benefit) — a host-run
    process can't resolve that name even though the control-plane port is
    published to `localhost`. Fixed by adding the ingestion CLI's shaded
    jar into `search-service`'s Docker image
    (`COPY --from=build .../search-service-*-ingestion-cli.jar ingestion-cli.jar`)
    and a `./data:/data:ro` volume mount, then running it via
    `docker compose run --entrypoint java search-service -jar ingestion-cli.jar ...`
    — inside the network, `pinecone-local` resolves correctly. Don't try
    the host-run path again; it's a dead end given how Pinecone Local
    advertises its data-plane host.
  - **Ingestion OOM-killed at default settings** (exit 137) when run
    alongside this machine's other already-running containers (the sibling
    `search` project's idle Elasticsearch+Kibana alone were holding ~2.9GB
    in a 7.7GB VM). Fixed by lowering `--batch-size` from 500 to 100 and
    capping the JVM heap (`JAVA_TOOL_OPTIONS=-Xmx1200m`) on the ingestion
    run specifically — the served application itself hasn't shown memory
    pressure at steady state (~380MB RSS each for search-service/
    recommender-service). If ingestion OOMs again on a differently-loaded
    machine, lower `--batch-size` further before assuming it's a code bug.
  - **Frontend/gateway port and CORS mismatch, found via real user testing**:
    the `web` image bakes `VITE_GRAPHQL_URL` in at build time, but
    `docker-compose.yml` hardcoded `http://localhost:8080/graphql`
    regardless of `GATEWAY_HOST_PORT` — so overriding the gateway's port
    (needed on this machine, see above) silently broke the frontend, which
    is exactly the error the user hit. Separately, `graphql-gateway` had
    **no CORS configuration at all**, which would have blocked every
    browser request regardless of port. Fixed both: `VITE_GRAPHQL_URL`'s
    build arg now interpolates `${GATEWAY_HOST_PORT:-8080}`, and
    `graphql-gateway/src/main/resources/application.yml` now sets
    `spring.graphql.cors.allowed-origins` from a `WEB_ORIGIN` env var that
    `docker-compose.yml` derives from `${WEB_HOST_PORT:-5173}` — the two
    ports are now decided together by construction, not independently.
    Verified via a real `curl -H "Origin: http://localhost:15173"` request
    showing the `Access-Control-Allow-Origin` response header.

- **M5 — DONE. Full end-to-end verification complete**, via real
  `docker compose up` with `GATEWAY_HOST_PORT=18080 WEB_HOST_PORT=15173`
  (this machine's other projects hold the defaults):
  - All 42,994 WANDS products ingested into the live Pinecone Local index
    (`wands-products`, 384-dim, cosine, confirmed `"status":{"ready":true}`
    via the control-plane API).
  - Queried the gateway directly (`curl`, not just the UI) with `NONE`,
    `POPULARITY`, `BANDIT`, and `NEURAL` strategies for the same query —
    confirmed each produces genuinely different results:
    `POPULARITY` injected two extra products beyond the 3 original
    candidates (the strategy's designed "add" behavior, actually observed);
    `NEURAL` produced a fully different ranking order, not just re-scored
    the same order; `source` field correctly reflects which strategy ran.
  - Confirmed the web UI loads and serves correctly at the (remapped) host
    port after the frontend/CORS fix above.
  - Docker Desktop VM disk pressure and host port conflicts with this
    machine's other running projects were worked around per the bullets
    above without touching anything outside this repo or those other
    projects' running containers.

**If you're resuming this session cold**: everything above is genuinely
done and pushed — run `git log --oneline` to confirm the latest commit
matches what this doc describes. If it doesn't, something changed after
this doc was last updated; trust `git log`/`git status` over this doc's
prose. A verification `docker compose` stack may or may not still be
running (`docker compose ps`) — it's fine to tear down
(`docker compose down`) or leave up depending on whether someone's still
using it; nothing about finishing this project depends on that stack's
state.

## Verified-working facts (don't re-litigate or re-discover these)

- **JDK 26**, matching the sibling `search` project's choice (an earlier
  draft of this project used JDK 21 for perceived gRPC/Protobuf tooling
  maturity, but the user asked to use the latest version instead — verified
  the full reactor still builds/tests clean under 26, and the Docker base
  images (`maven:3.9.16-eclipse-temurin-26`, `eclipse-temurin:26-jre-jammy`)
  are the exact same tags the sibling project already uses).
- **`protoc-gen-grpc-java`'s macOS binaries are x86_64 even for the
  "aarch_64" classifier**, across every version checked (1.53–1.73). This
  is Google's own packaging, not a bug specific to one version — it's
  meant to run via Rosetta 2 on Apple Silicon. **Rosetta 2 was installed on
  this machine** (`softwareupdate --install-rosetta --agree-to-license`,
  with explicit user approval first) to unblock this. If working on a
  fresh Apple Silicon machine, this will be needed again.
- **Pinecone Java client is pinned to `3.1.0`, not the latest (6.x)**.
  Verified directly against the jars (via `javap`, not docs/memory):
  `pinecone-client` 4.0.0+ requires a `vector_type` field in index-describe
  API responses that Pinecone Local's fixed API version (`2025-01`) doesn't
  return, causing a hard client-side deserialization failure
  (`IllegalArgumentException`) on `describeIndex`/`createServerlessIndex`.
  3.1.0 is the newest version whose `IndexModel` doesn't reference
  `vector_type` at all. This was confirmed end-to-end against a live
  `pinecone-local` container (real index create → upsert → query → correct
  nearest-neighbor ordering and metadata round-trip), not just compiled.
- **Pinecone Local's control-plane host needs a URL scheme**
  (`http://pinecone-local:5080`), but you never need to separately construct
  a data-plane host/port — `Pinecone.getIndexConnection(indexName)` resolves
  the correct per-index data-plane host internally via `describeIndex`.
  See `rec-support/.../pinecone/PineconeVectorStore.java`'s class Javadoc.
- **`ghcr.io/pinecone-io/pinecone-local` is a single-platform (x86_64-only)
  image with no shell inside it** (scratch/distroless-style, single `/control`
  binary entrypoint) — `docker-compose.yml` pins `platform: linux/amd64`
  explicitly and deliberately has **no healthcheck** on it (a CMD-based
  healthcheck can't run without a shell). Services depending on it just use
  plain `depends_on` (start-order only, not readiness) and are expected to
  retry their first Pinecone connection with backoff.
- `rec-support` is fully real, tested, and `mvn install`ed to the local
  `~/.m2` repo — `WandsProductCsvLoader`/`EmbeddingTextBuilder` (ported from
  the sibling `search` project), `EmbeddingService` (ONNX `bge-small-en-v1.5`,
  ported verbatim, verified against the real downloaded model), and
  `PineconeVectorStore` (verified against a live Pinecone Local container).
  Other modules should build against it via `mvn -pl <module> test`
  (**not** `-am`) to avoid rebuilding/racing on `rec-support`'s own
  `target/` directory when multiple modules are worked on concurrently.
- `data/` and `models/` are populated locally (gitignored, fetched via
  `scripts/download-data.sh` / `scripts/download-models.sh`) — real WANDS
  catalog (42,994 products), real clickstream (170,990 events / 5,000
  users), real `bge-small-en-v1.5` ONNX weights (128MB, fp32).

## Known simplifications / deliberate scope cuts (be upfront about these, don't silently "fix")

- Proto stubs are generated independently in all three consuming modules
  (each compiles its own copy of `search_service.proto` /
  `recommender_service.proto` from the shared `proto/` dir) rather than via
  one shared generated-stubs artifact. Simpler for a small polyglot repo;
  costs a little redundant compilation, no functional downside since gRPC
  messages are wire-compatible regardless of which JVM/module produced the
  generated class.
- Pinecone Local has no persistence and a 100K-vector cap — explicitly a
  local-dev/demo choice, not production-representative; document this
  plainly in the README rather than pretending otherwise (same honesty
  norm as the sibling `search` project's README/WRITEUP.md).
