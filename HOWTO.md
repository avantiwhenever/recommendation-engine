# How to run this locally

Step-by-step instructions to get the full stack — Pinecone Local, both gRPC
services, the GraphQL gateway, and the React UI — running on your machine,
plus how to confirm each piece actually came up before moving to the next
step. No cloud account or API key needed anywhere in this flow.

## Prerequisites

- JDK 26, Maven, Docker + Docker Compose plugin, Node.js (for `web/`).
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
./scripts/start-services.sh
```

(Set `GATEWAY_HOST_PORT`/`WEB_HOST_PORT` env vars first if 8080/5173 are
already taken by another project on your machine.) Builds and starts all
five containers, then polls until graphql-gateway responds.

**Verify:** `docker compose ps` — all five containers (`pinecone-local`,
`search-service`, `recommender-service`, `graphql-gateway`, `web`) should
show as running (not restarting/exited).

### 4. Ingest the WANDS catalog into Pinecone

```bash
./scripts/ingest-catalog.sh
```

Rebuilds `search-service` (to pick up any local changes) and runs the
ingestion CLI **inside** the compose network — it can't run on the host,
since Pinecone Local's data-plane host discovery returns the
container-internal hostname regardless of caller; see
[docs/PROJECT_STATE.md](docs/PROJECT_STATE.md) for why. This embeds all
~43K products via `bge-small-en-v1.5` and upserts them into the
`wands-products` Pinecone Local index — expect this to take a while
(CPU-bound batch embedding); the CLI prints progress periodically.

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

### 8. Run the offline evaluation

```bash
./scripts/run-recommender-eval.sh
```

Scores all 6 strategies against held-out clickstream sessions and writes
`RESULTS.md` at the repo root — see `EvalCli`'s class Javadoc for the full
methodology and honest caveats.

**Verify:** `RESULTS.md` has a fresh timestamp and a 5-row table.

### 9. Regenerate the GitHub Pages demo snapshots

```bash
python3 scripts/capture_demo_snapshots.py
```

Captures live results for a curated set of queries × all 6 strategies into
`docs/data/*.json` — this is what `docs/index.html`'s static demo fetches.

**Verify:** `docs/data/manifest.json` lists the captured files; open
`docs/index.html` locally (e.g. `python3 -m http.server` from `docs/`) to
confirm the page renders with the new data.

### Stopping everything

```bash
./scripts/stop-services.sh
```

## Convenience scripts

| Script | What it does |
|---|---|
| `scripts/download-data.sh` | Fetches WANDS + clickstream CSVs into `data/` |
| `scripts/download-models.sh` | Fetches the embedding model into `models/` |
| `scripts/start-services.sh` | `docker compose up --build` + waits for the gateway |
| `scripts/wait-for-gateway.sh` | Polls a host until it responds (used by the above) |
| `scripts/stop-services.sh` | `docker compose down` |
| `scripts/ingest-catalog.sh` | Runs the ingestion CLI inside the compose network |
| `scripts/run-recommender-eval.sh` | Runs the offline evaluation, writes `RESULTS.md` |
| `scripts/capture_demo_snapshots.py` | Captures live queries into `docs/data/` for the GitHub Pages demo |
| `scripts/scan-cves.sh` | Reproduces CI's `cve-scan` job locally via Trivy |

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
