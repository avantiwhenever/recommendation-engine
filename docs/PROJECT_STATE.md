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
- **M1 — search-service: dispatched to a background fork.** Hexagonal
  Pinecone-backed search + ingestion CLI. Check fork completion before
  assuming this is done — read its actual files and rerun
  `mvn -pl search-service test`, don't just trust a summary.
- **M2 + M2.5 — recommender-service + neural ranking model: dispatched to a
  background fork.** 5 strategies (passthrough, popularity, collaborative-
  filtering, bandit, ONNX-based neural ranking) + `training/` Python
  pipeline. Same caveat — verify, don't just trust.
- **M3 — graphql-gateway: dispatched to a background fork.** Real schema
  (already landed — see `graphql-gateway/src/main/resources/graphql/schema.graphqls`),
  resolvers, gRPC client adapters to both backend services.
- **M4 — web (React UI): dispatched to a background fork.** Vite + TS +
  Apollo Client. `web/` directory exists with a real scaffold in progress
  (node_modules already installed as of this writing) — check its actual
  state.
- **M5 — NOT STARTED: full docker-compose end-to-end verification, CI
  workflow, final polished README/HOWTO, final commit + push.** This is
  the next work to do once M1–M4 are confirmed real and correct.

**If you're resuming this session cold**: check whether the four forks
above actually finished (look for their work in `search-service/src`,
`recommender-service/src`, `graphql-gateway/src`, `web/src` — if those
directories only have the M0 skeleton/placeholder files, the forks either
didn't finish or their work wasn't integrated). Verify each module
independently with `mvn -pl <module> test` before trusting it. Then do the
M5 work: real end-to-end docker-compose run, CI, README/HOWTO polish,
commit, push.

## Verified-working facts (don't re-litigate or re-discover these)

- **JDK 21** (not the sibling `search` project's JDK 26) — chosen for
  broader gRPC/Protobuf tooling compatibility maturity. LTS.
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
