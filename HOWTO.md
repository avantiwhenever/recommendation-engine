# How to run this locally

Step-by-step instructions to get the full stack — Pinecone Local, both gRPC
services, the GraphQL gateway, and the React UI — running on your machine,
plus how to confirm each piece actually came up before moving to the next
step. No cloud account or API key needed anywhere in this flow.

## Prerequisites

- JDK 21, Maven, Docker + Docker Compose plugin, Node.js (for `web/`).
- **Apple Silicon Macs**: `protoc-gen-grpc-java`'s macOS binaries are
  x86_64 even for the "aarch_64" classifier (confirmed across many
  versions — this is upstream packaging, not a bug in this repo), so Maven
  builds that trigger gRPC codegen need Rosetta 2:
  ```bash
  softwareupdate --install-rosetta --agree-to-license
  ```
  Docker itself handles the `pinecone-local` image's x86_64-only
  architecture via its own emulation (`platform: linux/amd64` is already
  set in `docker-compose.yml`) — that part needs no host-level Rosetta.

## Step-by-step

### 1. Fetch the WANDS catalog + clickstream dataset

```bash
./scripts/download-data.sh
```

Downloads `product.csv`, `query.csv`, `label.csv` (from upstream
`wayfair/WANDS`) and `clickstream.csv` (from `avantiwhenever/WANDS`, this
project's synthetic addition) into `data/`.

**Verify:** `wc -l data/*.csv` — expect ~42,995 products, 480 queries,
233,448 labels, ~170,991 clickstream events (plus one header row each).

### 2. Fetch the embedding model

```bash
./scripts/download-models.sh
```

Downloads ONNX weights + tokenizer for `bge-small-en-v1.5` into
`models/bge-small-en-v1.5/`.

**Verify:** that directory should contain `model.onnx` (~128MB),
`tokenizer.json`, and `config.json`.

### 3. Start Pinecone Local + all four services

```bash
docker compose up -d --build
```

**Verify:** `docker compose ps` — all five containers (`pinecone-local`,
`search-service`, `recommender-service`, `graphql-gateway`, `web`) should
show as running (not restarting/exited). `curl -sf http://localhost:5080/indexes`
should return `{"indexes":[]}` before ingestion, confirming Pinecone Local
itself is reachable.

### 4. Ingest the WANDS catalog into Pinecone

```bash
docker compose run --rm search-service \
  java -cp app.jar:BOOT-INF/lib/* com.avanti.recengine.search.ingestion.IngestionCli
```

(Or build and run the shaded ingestion CLI jar locally instead of through
Docker — see `search-service/pom.xml`'s `maven-shade-plugin` execution,
classifier `ingestion-cli`.) This embeds all ~43K products via
`bge-small-en-v1.5` and upserts them into the `wands-products` Pinecone
Local index. Expect this to take a while (CPU-bound batch embedding) — the
CLI prints progress periodically.

**Verify:** `curl -s http://localhost:5080/indexes/wands-products` should
report a non-zero vector count once ingestion completes.

### 5. Train the neural ranking model

```bash
cd training
pip install -r requirements.txt
python train_neural_ranker.py
cd ..
```

Trains a small MLP on `data/clickstream.csv`'s implicit feedback and
exports it to `models/neural-ranker/model.onnx`. See `training/TRAINING.md`
for the actual held-out evaluation numbers and methodology.

**Verify:** `models/neural-ranker/model.onnx` should exist.
`recommender-service` needs a restart (`docker compose restart
recommender-service`) to pick up a freshly trained model if it was already
running.

### 6. Query the GraphQL gateway directly

Open `http://localhost:8080/graphiql` and run:

```graphql
query {
  search(query: "platform bed frame", strategy: COLLABORATIVE, topK: 5) {
    products { name score source averageRating }
  }
}
```

**Verify:** you get back real WANDS products (not an error), and switching
`strategy` between `NONE`, `POPULARITY`, `COLLABORATIVE`, `BANDIT`, and
`NEURAL` changes the result ordering and/or the `source` field per result.

### 7. Use the web UI

```
http://localhost:5173
```

**Verify:** the search box returns results, the strategy dropdown changes
them, and each result shows a "why was this shown" source badge.

## Running things individually outside Docker (for development)

Each Java module can run standalone via `mvn -pl <module> spring-boot:run`
once `rec-support` is installed (`mvn -pl rec-support install`) — point
`PINECONE_HOST`/`GRPC_PORT`/etc. env vars at `localhost` equivalents instead
of the docker-compose service names. `web/` runs via `npm run dev` inside
`web/`, pointed at `VITE_GRAPHQL_URL=http://localhost:8080/graphql`.

Run a module's tests in isolation with `mvn -pl <module> test` (omit
`-am` — dependencies resolve from the already-installed local repo, and
`-am` would rebuild/race on shared modules if you're doing this across
multiple terminals at once).
