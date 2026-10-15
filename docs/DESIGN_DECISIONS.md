# Design decisions

This document records the main technical decisions, the alternatives, and the trade-offs.

## Decisions

| Area | Decision | Alternative | Reason |
|---|---|---|---|
| Architecture | Hexagonal (ports and adapters) in each Java service | Layered Spring application | The `domain`, `application`, and `port` packages have no framework imports. You can test the business logic without Spring, a network, or Docker. |
| Communication between services | gRPC only | REST between services | The `.proto` files in `proto/` are strict, versioned contracts between the services. |
| Frontend API | One GraphQL gateway | One REST API for each service | The frontend uses one endpoint and selects only the fields that it needs. Only `graphql-gateway` accepts HTTP requests. |
| Vector database | Pinecone, with Pinecone Local for development | Self-hosted Elasticsearch or pgvector | Pinecone Local runs in Docker with no account or API key. To use hosted Pinecone, change the configuration only. |
| Retrieval | Hybrid: vector search and in-memory BM25, combined with RRF | Vector search only | Vector search is weak on exact terms, for example model numbers and brand names. An in-memory BM25 index adds keyword search without another server. |
| Embeddings | `bge-small-en-v1.5`, run in the service with ONNX Runtime | A hosted embedding API | Pinecone Local cannot calculate embeddings. A local model also has no cost and no network call. |
| Ranking model | Train in Python, serve in Java through ONNX | A Python model server | The model runs in the same process as the strategy, with no extra network call. `FeatureParityTest` keeps the two languages in agreement. |
| Request pipeline | Get 200 candidates, keep the best 50, then apply a strategy | Apply a strategy to all search results | A wide first pool gives the strategies more products to select from. A small second pool keeps the strategies fast. |
| Strategy design | One interface, `RecommendationStrategy`, with one implementation for each strategy | Conditional logic in one class | You can add, test, and compare a strategy without changes to the other strategies. Decorators, for example the diversity strategy, can wrap any strategy. |
| Data | WANDS catalog and a synthetic clickstream | A different public data set | WANDS has real products and human relevance labels. It has no user behavior, so the project generates a documented synthetic clickstream. See [DATA.md](DATA.md). |
| Build | Maven multi-module project | Gradle | A reviewer can read the Maven dependency structure quickly. |
| Platform | JDK 26 and Spring Boot 4 | Older long-term releases | Spring Boot 4 contains the fixes for critical CVEs in Spring Framework 6. It also includes a first-party gRPC integration. |

## The main trade-off: more services than the problem needs

This system has four services and gRPC contracts between them. A catalog of 43,000 products with no live traffic does not need this.
A system of this size, built only to operate, would probably be one service with clear internal modules.

The project uses separate services on purpose. It shows how to design service boundaries for a system that many teams develop.
The internal pattern is the same in both designs: ports, adapters, and a tested domain core. Only the network calls between the services are extra.

## Other limits

- **Pinecone Local** keeps data only in memory and holds a maximum of 100,000 vectors. It is for development and demonstration only.
- **The bandit priors do not change** during operation, because the project has no live traffic.
- **Session recency** uses a fixed weight, not a time-decay function. The gRPC request gives the session products without timestamps.

[PROJECT_STATE.md](PROJECT_STATE.md) has the full list of known limitations.
