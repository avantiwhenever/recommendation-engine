# Architecture: a tour for reviewers

This document helps you read the code in a useful order. You do not need to read each file.

- For the reasons behind the main decisions, see [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md).
- For the verified operational facts and the known limitations, see [PROJECT_STATE.md](PROJECT_STATE.md).

## Recommended reading order

1. **`proto/search_service.proto` and `proto/recommender_service.proto`.** These files are the contracts of the system. Each call between services uses one of these two RPCs. Read them first.
2. **`rec-support/`.** This module is the shared infrastructure. It has three independent parts:
   - `support/wands/`: the reader for the WANDS catalog files
   - `support/embedding/`: the ONNX embedding pipeline
   - `support/pinecone/`: the Pinecone client wrapper

   No part knows the domain model of a service. This is intentional. See [Why `rec-support` has no domain model](#why-rec-support-has-no-domain-model).
3. **One full vertical slice: `search-service/`.** This is the simplest service. It has one use case and three outbound ports. It shows the full hexagonal pattern:
   - `domain` → `port/in` → `application` → `port/out` → `adapter/out/*`
   - `config` connects the parts.
   - `adapter/in/grpc` receives the requests.

   The other services use the same pattern. `recommender-service` has more strategies and more outbound ports. `graphql-gateway` receives GraphQL requests instead of gRPC requests, and it is a gRPC client, not a gRPC server.
4. **`recommender-service/`.** This module contains the strategies.
   - Start at `domain/RecommendationStrategy.java`. Each strategy implements this interface.
   - Then read `domain/strategy/Strategies.java`. It builds the map from each strategy name to its implementation.
   - The map is plain Java, not a Spring bean map. Thus, `config/RecommenderConfig.java` and the offline evaluation tool (`eval/EvalCli.java`) use the same code. The evaluation tool does not use Spring.
5. **`training/`.** This is the Python part. It does not use the JVM. It makes labels from the clickstream, trains the ranking model, and exports it to ONNX. Read [training/TRAINING.md](../training/TRAINING.md) for the method and the results. That document also records each change from the original plan.
6. **`graphql-gateway/`.** This service is small by design.
   - `adapter/in/graphql/` is the only inbound adapter in the system that does not use gRPC.
   - `adapter/out/grpc/` calls the two backend services.
7. **`web/`.** This is a standard React and Apollo Client application. The hexagonal pattern applies to the backend services only, not to the frontend.

## Why `rec-support` has no domain model

In hexagonal architecture, each service owns its domain types. A domain model that services share causes coupling. For example, a change to the "Product" type of one service would then change all other services.

Thus, `rec-support` contains only technical parts with no business meaning:

| Type | Purpose |
|---|---|
| `WandsProductRow` | The shape of one row in the catalog file. It is not a domain concept. |
| `EmbeddingService` | A wrapper for ONNX Runtime |
| `PineconeVectorStore` | A wrapper for the Pinecone client |

Each service converts these types into its own domain types in its adapters.
If a `domain` or `application` package imports a `rec-support` type, that is a violation of the hexagonal boundary. Report it in the review.

## How the integration risks were removed

The largest risks were the external libraries, where the documentation and the real behavior were different.
The project removed these risks with tests on the real artifacts, not with the documentation. For example, it used `javap` on the downloaded jars and `docker run` on the real `pinecone-local` image.
[PROJECT_STATE.md](PROJECT_STATE.md) records the results under "Verified-working operational facts":

- **Pinecone client version.** Only version 3.1.0 works with Pinecone Local. Newer versions need a `vector_type` field that Pinecone Local does not return.
- **gRPC code generation on macOS.** The `protoc-gen-grpc-java` binaries for macOS need Rosetta 2.
- **Pinecone Local hosts.** You configure one control-plane URL with its scheme. The client finds the data-plane host automatically.
- **No health check command.** The `pinecone-local` image has no shell. Thus, a Docker health check with a command cannot run in it.

If you extend this project and an integration does not work as expected, use the same method:

1. Do not trust memory or documentation text.
2. Get the real artifact and examine it. For example, use `javap -public <class>`, `docker run --entrypoint sh ... -c "..."`, or a short Java `main()` method that calls the real API.
3. Write the code for the behavior that you saw.

## What each type of test covers

All services use the same test structure:

| Test type | What it uses | Purpose |
|---|---|---|
| Application-layer unit tests | Hand-written fake `port/out` implementations. No Spring context, no network, no Docker. | These tests cover most of the logic: strategies, ranking, and orchestration. They are the fastest tests. |
| gRPC adapter tests | `InProcessServerBuilder` and `InProcessChannelBuilder` in `search-service` and `recommender-service`. A fake in-process service in `graphql-gateway`, to test its gRPC client adapters. | These tests use real message serialization, but no real sockets. |
| Tests against external systems | A live Pinecone Local container (`PineconeVectorStoreSmokeTest`, `PineconeVectorSimilarityAdapterSmokeTest`) and the real ONNX model (`EmbeddingServiceTest`) | These tests skip when the container or the model is not available. They find problems such as the Pinecone client version problem above. |
| Full system | `docker compose up` and a real GraphQL query, as [HOWTO.md](../HOWTO.md) describes | CI does not run this test, because it needs the full catalog and takes a long time. |

For more about tests and CI, see [ENGINEERING_PRACTICES.md](ENGINEERING_PRACTICES.md).

## Known limitations

See "Known simplifications / deliberate scope cuts" in [PROJECT_STATE.md](PROJECT_STATE.md). The list is in one place only, so that it stays correct.
