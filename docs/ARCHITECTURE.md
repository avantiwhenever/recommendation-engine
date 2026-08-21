# Architecture — a reviewer's tour

This doc is for someone reviewing this codebase who wants to navigate it
efficiently rather than read every file linearly. For *why* the big
decisions were made, see the README's "Key decisions" table; for exactly
what's verified-working vs. in-flight, see [PROJECT_STATE.md](PROJECT_STATE.md).

## Suggested reading order

1. **`proto/search_service.proto` and `proto/recommender_service.proto`** —
   these are the real contracts of the system. Every inter-service
   interaction in this codebase reduces to one of these two RPCs. Read
   these before anything else.
2. **`rec-support/`** — the shared infrastructure layer. Three independent
   pieces: WANDS CSV parsing (`support/wands/`), the ONNX embedding
   pipeline (`support/embedding/`), and the Pinecone client wrapper
   (`support/pinecone/`). None of them know about any service's domain
   model — that's deliberate (see "Why `rec-support` has no domain model"
   below).
3. **One full vertical slice** — `search-service/` is the simplest service
   (one use case, two outbound ports) and the best place to see the
   hexagonal pattern end to end: `domain` → `port/in` → `application` →
   `port/out` → `adapter/out/*` → `config` wiring it all together, plus
   `adapter/in/grpc` receiving requests. Once this one makes sense, the
   pattern repeats in `recommender-service` (more strategies, more
   outbound ports) and `graphql-gateway` (GraphQL inbound instead of gRPC,
   gRPC *clients* instead of a server).
4. **`recommender-service/`** — the strategy layer. Start at
   `domain/RecommendationStrategy.java` (the interface every strategy
   implements) and `config/RecommenderConfig.java` (how a strategy is
   selected at request time — a manually-built enum-keyed `Map`, not
   Spring's auto-populated bean map, so the same strategy code stays
   portable outside a Spring context if this project ever grows an offline
   eval CLI the way the sibling `search` project has one).
5. **`training/`** — the Python side, independent of the JVM: builds
   implicit-feedback labels from the clickstream CSV, trains a small model,
   exports ONNX. Read `training/TRAINING.md` for the actual methodology
   used and honest results, since Python training pipelines are exactly
   where "the plan said X but Y turned out more practical" deviations are
   most likely — that doc is where any such deviation is supposed to be
   recorded truthfully.
6. **`graphql-gateway/`** — thin by design. `adapter/in/graphql/` is the
   only non-gRPC inbound adapter anywhere in the system;
   `adapter/out/grpc/` is where the two backend services get called.
7. **`web/`** — standard React/Apollo Client; no architectural pattern
   beyond normal component structure, since hexagonal architecture is a
   backend-service concept here, not applied to the frontend.

## Why `rec-support` has no domain model

Under strict hexagonal architecture, each service owns its own domain
types — sharing a domain model across service boundaries is exactly the
kind of coupling hexagonal architecture exists to prevent (a change to one
service's concept of "Product" would ripple into every other service that
imported the same shared type). `rec-support` therefore only holds
technical building blocks with no business meaning of their own: CSV
parsing output (`WandsProductRow` — a row shape, not a domain concept),
an ONNX Runtime wrapper (`EmbeddingService`), and a Pinecone SDK wrapper
(`PineconeVectorStore`). Every service maps these into its *own*
domain type at its adapter boundary. If you see a service importing a
`rec-support` type directly into its `domain` or `application` package
instead of only in `adapter`, that's a hexagonal-boundary violation worth
flagging in review.

## Where the real integration risk was (and how it was retired)

The riskiest unknowns in this codebase were external-library integration
points where documentation and reality diverge — these were resolved by
inspecting real artifacts (`javap` against downloaded jars, actual
`docker run` against the real `pinecone-local` image) rather than trusting
documentation or memory, and the discoveries are recorded in
[PROJECT_STATE.md](PROJECT_STATE.md) under "Verified-working facts":

- The exact Pinecone Java client version compatible with Pinecone Local
  (3.1.0, not the latest — newer versions fail on a missing `vector_type`
  field Pinecone Local's fixed API version doesn't return).
- `protoc-gen-grpc-java`'s macOS binaries needing Rosetta 2.
- Pinecone Local's host/port model (one URL-scheme'd control-plane host;
  data-plane host resolution is automatic, not something you construct).
- `pinecone-local`'s image having no shell inside it, ruling out
  CMD-based Docker healthchecks.

If you're extending this project and hit an integration surprise, the
working method that resolved all four of the above was: don't guess from
memory or documentation prose — pull the real artifact and inspect it
(`javap -public <class>`, `docker run --entrypoint sh ... -c "..."`, a
throwaway Java `main()` hitting the real API) until you have ground truth,
then write the code against that.

## What's tested where

Every service follows the same testing shape:

- **`application`-layer unit tests** — fake/hand-written `port/out`
  implementations, no Spring context, no network, no Docker. This is
  where most of the real logic (strategy behavior, ranking, orchestration)
  should be covered, and where tests should run fastest.
- **gRPC adapter tests** — grpc-java's `InProcessServerBuilder`/
  `InProcessChannelBuilder` (`search-service`/`recommender-service`) or an
  in-process fake service implementation (`graphql-gateway`, testing its
  gRPC *client* adapters). Real wire (de)serialization, no real sockets.
- **`rec-support` integration tests** — the one place with tests against
  genuinely external systems: a live Pinecone Local container
  (`PineconeVectorStoreSmokeTest`, skips gracefully if the container isn't
  running) and the real downloaded ONNX model (`EmbeddingServiceTest`,
  skips gracefully if `models/` hasn't been fetched). These are the tests
  that would have caught the pinecone-client version issue above.
- **End-to-end** — `docker-compose up` plus a real GraphQL query, per
  HOWTO.md. Not run in CI (see the CI workflow's comments for why, mirroring
  the sibling `search` project's own reasoning about not running expensive
  full-catalog checks on every push).

## Known simplifications

See [PROJECT_STATE.md](PROJECT_STATE.md)'s "Known simplifications" section
— kept there rather than duplicated so there's one place that can't drift
out of sync.
