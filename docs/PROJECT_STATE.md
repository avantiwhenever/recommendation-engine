# Project state

This document gives a new contributor the facts that they need before they change the system:

- what the project is
- the architecture rules that must not change
- the operational behavior of the dependencies, as tested

It describes the system as it is now. It is not a history.

## What this project is

A React and GraphQL recommendation engine for the [WANDS](https://github.com/wayfair/WANDS) furniture catalog. It has three main parts:

- A search service that uses Pinecone and BM25.
- A strategy layer that can add, remove, or re-order the search results. See [STRATEGIES.md](STRATEGIES.md).
- A GraphQL gateway in front of both services.

Each strategy comes from published research or industry practice. See [REFERENCES.md](REFERENCES.md).

### Architecture rules

These three rules must not change:

1. **Each Java service uses hexagonal architecture (ports and adapters).** The `domain`, `application`, and `port` packages must not import Spring, gRPC stubs, or the Pinecone SDK. Only the `adapter` and `config` packages can import them.
2. **The Java services communicate with each other only through gRPC.** There is no REST between `graphql-gateway`, `search-service`, and `recommender-service`.
3. **GraphQL exists only in `graphql-gateway`, for the frontend.** No service after the gateway uses GraphQL or REST to call another Java service.

`recommender-service` calls Pinecone directly. This is an infrastructure dependency, not a call between services, so rule 3 does not apply to it. See the Javadoc of `VectorSimilarityPort`.

## Recommendation strategies

There are six strategies. The same names are in the proto contract, in the domain layer of `recommender-service`, and in the GraphQL schema.

| # | Strategy | Summary |
|---|---|---|
| 1 | None | The baseline. It returns the candidates without change. The gateway selection stage still applies. |
| 2 | Popularity | Combines search relevance with clickstream popularity. It can add popular products that search did not find. |
| 3 | Collaborative filtering | Item-to-item similarity, calculated as cosine similarity over session co-occurrence. The current session has a higher weight than the full history. A user with no history gets Popularity. |
| 4 | Bandit exploration | Thompson sampling over product categories. The priors come from historical engagement and the user's category profile. |
| 5 | Neural ranking | A pairwise XGBoost ranking model (`rank:ndcg`) with 7 features, served with ONNX Runtime |
| 6 | Diverse popularity | Popularity, then MMR re-ranking for category diversity. `DiversityAwareStrategy` is a decorator, so it can wrap any strategy. |

`recommender-service` also has a `VectorSimilarityPort`. It finds similar products with the embedding index of `search-service`. `DiversityAwareStrategy` uses it.

[STRATEGIES.md](STRATEGIES.md) gives the details of each strategy.

## Retrieval pipeline

`search-service` uses hybrid retrieval:

- Vector search in Pinecone, with `bge-small-en-v1.5` embeddings
- Keyword search in an in-memory BM25 index
- Reciprocal Rank Fusion (RRF) to combine the two lists

The project uses no Elasticsearch, Lucene, or Solr. Pinecone is the only search infrastructure.

`SearchOrchestrationUseCase` in `graphql-gateway` runs a multi-stage pipeline:

1. Get a pool of 200 candidates.
2. Remove the candidates that have no ratings, and keep the 50 with the highest scores. This step is the same for each strategy.
3. Apply the selected strategy to the 50 candidates.
4. Return the number of results that the caller asked for (`topK`).

The optional `categoryFilter` and `minRating` arguments of the GraphQL `search` query filter the candidates before this pipeline starts.

## Offline evaluation

`EvalCli` in `recommender-service` scores each strategy against held-out clickstream sessions in three ways. `RESULTS.md` shows all three.

- **Clickstream evaluation.** The relevance grades come from the same synthetic clickstream that the strategies read. Thus, this ground truth is circular. `RESULTS.md` says this.
- **Independent WANDS relevance evaluation.** The scores use the WANDS human relevance labels. No strategy trains on these labels. This is the more reliable of the two. All six strategies have similar scores here (nDCG@5 from 0.90 to 0.97). No strategy is clearly the best.
- **Off-policy (IPS) evaluation.** An item-level Inverse Propensity Scoring estimate of the reward of each strategy. It uses the known logging policy of the synthetic clickstream (see `CLICKSTREAM.md` in `avantiwhenever/WANDS`). The table shows the raw estimate, the clipped estimate, and the effective sample size.

`TemporalClickstreamIndex` replays the sessions in time order. Thus, the features of a held-out session never include events from after that session.

[EVALUATION.md](EVALUATION.md) explains the method and the results.

## Verified-working operational facts

### Build and platform

- **JDK 26.** The Docker base images are `maven:3.9.16-eclipse-temurin-26` and `eclipse-temurin:26-jre-jammy`.
- **Rosetta 2 on Apple Silicon.** The macOS binaries of `protoc-gen-grpc-java` are x86_64, also for the "aarch_64" classifier. This is true for each version from 1.53 to 1.73. To build this repository on a new Apple Silicon computer, install Rosetta 2 first:

  ```bash
  softwareupdate --install-rosetta --agree-to-license
  ```

- **Build order.** Install `rec-support` to `~/.m2` with `mvn install` before you build the other modules with `mvn -pl <module> test`. Do not use `-am`. If you work on more than one module at the same time, `-am` can cause a problem. Two builds can then write to the `target/` directory of `rec-support` at the same time.
- **Data and models are not in Git.** `data/` and `models/` are in `.gitignore`. To get them, run `scripts/download-data.sh` and `scripts/download-models.sh`. They contain:
  - the WANDS catalog: 42,994 products
  - the clickstream: 170,990 events from 5,000 users
  - the `bge-small-en-v1.5` ONNX model: 128 MB, fp32

### Spring Boot and gRPC

- **gRPC servers.** `search-service` and `recommender-service` use the Spring Boot 4 gRPC starter. Each gRPC service class has the `@GrpcService` annotation. The property `spring.grpc.server.port` sets the port.
- **gRPC clients.** `graphql-gateway` makes its stubs in `GatewayConfig` from named channels. Each channel is set in `spring.grpc.client.channel.<name>.target`. The channels set `ssl.enabled: false`, because the services do not use TLS inside the Docker network.

### Pinecone client and Pinecone Local

- **The Pinecone Java client must stay at version 3.1.0.** The latest major version is 6. Version 4.0.0 and later need a `vector_type` field in the index description. Pinecone Local uses a fixed API version (`2025-01`) that does not return this field. Thus, `describeIndex` and `createServerlessIndex` fail in the client. Version 3.1.0 is the newest version whose `IndexModel` does not use `vector_type`.
- **The control-plane host needs a URL scheme**, for example `http://pinecone-local:5080`. You do not set the data-plane host. `Pinecone.getIndexConnection(indexName)` finds it with `describeIndex`. See the class Javadoc of `PineconeVectorStore` in `rec-support`.
- **The Pinecone Local image is x86_64 only and has no shell.** Thus, `docker-compose.yml` sets `platform: linux/amd64` and has no health check. A health check with a command cannot run without a shell. The services that need Pinecone use `depends_on`, which controls only the start order. On startup, each service tries to connect up to 10 times, with 2 seconds between attempts.
- **`recommender-service` only reads the index.** It connects to the `wands-products` index, which `search-service` owns. It does not call `ensureServerlessIndex`, because it does not own the index.
- **The first query on a new gRPC channel can fail.** After a container restart, the first `similarProductIds` call failed with `io.grpc.StatusRuntimeException: UNKNOWN: channel closed`, which contains a `ClosedChannelException`. The connection itself succeeded at startup. A second request immediately after was successful, so this is a one-time problem with a new channel.
  - This exception is a `RuntimeException`, not a `PineconeException`.
  - Thus, `PineconeVectorSimilarityAdapter` catches all `RuntimeException` types.
  - With a narrower catch, the error would cause a GraphQL `INTERNAL_ERROR`. The port contract says that the result must be "no similar items found".
- **Pinecone Local has a limit on concurrent queries.** It is an in-memory emulator that runs in one process.
  - `DiversityAwareStrategy` sends up to 50 neighbor queries for each request, one for each candidate. It runs them in parallel on a small thread pool.
  - Sequential queries took approximately 12.8 seconds for each request. Parallel queries took approximately 3 to 5 seconds. A pool of 25 threads was not faster than a pool of 10, so the limit is in Pinecone Local, not in the client.
  - With 10 threads, a fast series of `DIVERSE_POPULARITY` requests overloaded Pinecone Local. An example is `scripts/capture_demo_snapshots.py`, which captures all demo queries one after the other. During the overload, other requests also failed, for example a `COLLABORATIVE` request, because the base search query of `search-service` could not get through. Pinecone Local recovered in a few seconds without a restart.
  - With 5 threads, the failure did not occur again in repeated full capture runs. `scripts/capture_demo_snapshots.py` also retries a failed request up to 3 times, with 2 seconds between attempts. This is a second, independent protection, because a batch tool sends requests faster than real users.
  - The class Javadoc of `DiversityAwareStrategy` gives the full account.
- **Pinecone Local does not keep data.** After a new `docker compose up`, or a restart of the `pinecone-local` container, run the ingestion tool again. Search returns no results until you do this.

### Local ports

- You can change the host ports of `graphql-gateway` and `web` with the `GATEWAY_HOST_PORT` and `WEB_HOST_PORT` environment variables. Docker Compose does not remember these values, so set them for each `docker compose up`.
- Vite writes `VITE_GRAPHQL_URL` into the image when it builds the image. If a rebuild does not use a changed build argument, run `docker compose build --no-cache`.

## Known simplifications / deliberate scope cuts

- **`VectorSimilarityPort` has one user.** `DiversityAwareStrategy` uses it, together with a category and product-class signal. The Javadoc of that class explains why it uses the higher of the two signals. It also explains why it sends one query for each candidate, not one for each pair. `NeuralRankingStrategy` does not use this port. For a product with no clickstream history, only its two category features give a signal. This is true for approximately 97% of the catalog.
- **The GraphQL `search` query has no pagination and no explanation field.** The `categoryFilter` and `minRating` arguments filter the candidates. But there is no pagination cursor and no field that shows which strategy or feature changed a score.
- **`topK` has an upper limit of 50.** If a caller asks for more results than `ELIGIBLE_POOL_SIZE` (50) in `SearchOrchestrationUseCase`, the caller gets fewer results than requested, with no error.
- **Each module generates its own proto stubs.** The three modules that use the proto files each generate the stubs, instead of one shared artifact. This is simpler for a small repository. It costs some extra compile time. It has no functional effect, because gRPC messages are compatible on the wire.
- **Pinecone Local is for development only.** It does not keep data and holds a maximum of 100,000 vectors. See [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md).
- **The IPS estimator is item-level.** It treats each item in the first 5 results as an independent action. It is not a full list-level estimator.
- **The clickstream is synthetic.** It does not show real user behavior. `CLICKSTREAM.md` in `avantiwhenever/WANDS` says this. See [DATA.md](DATA.md).
- **The bandit priors do not change.** `BanditExploreStrategy` calculates them one time from historical data. There is no live traffic to update them.
- **Session recency has no time decay.** `CollaborativeFilteringStrategy` gives the session a fixed 2.5 times weight. `NeuralRankingStrategy` uses a session feature with no time decay. The proto sends the session products as a list without timestamps.
