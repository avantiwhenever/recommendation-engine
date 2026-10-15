# How to run this project on your computer

This document tells you how to run the full system on your computer:

- Pinecone Local
- the two gRPC services
- the GraphQL gateway
- the React UI

Each step has a check, so that you can make sure that the step worked before you continue. You do not need a cloud account or an API key.

## Prerequisites

- JDK 26, Maven, Docker with the Docker Compose plugin, Node.js, and Python 3.
- **Apple Silicon (M-series) Macs:** The Maven build generates gRPC code with `protoc-gen-grpc-java`. Its macOS binaries are x86_64, also for the "aarch_64" classifier. This is an upstream packaging problem, not a problem in this repository. Install Rosetta 2 before you build:

  ```bash
  softwareupdate --install-rosetta --agree-to-license
  ```

  You do not need Rosetta 2 for the `pinecone-local` image. That image is also x86_64 only, but Docker runs it in emulation. `docker-compose.yml` already sets `platform: linux/amd64`.

## Steps

### 1. Download the data

```bash
./scripts/download-data.sh
```

This script puts four files into `data/`:

- `product.csv`, `query.csv`, and `label.csv` from `wayfair/WANDS`
- `clickstream.csv`, the synthetic clickstream from `avantiwhenever/WANDS`

**Check:** Run `wc -l data/*.csv`. Each file has one header line. The expected line counts are:

| File | Lines |
|---|---|
| `product.csv` | 42,995 |
| `query.csv` | 481 |
| `label.csv` | 233,449 |
| `clickstream.csv` | 170,991 |

### 2. Download the embedding model

```bash
./scripts/download-models.sh
```

This script puts the ONNX weights and the tokenizer of `bge-small-en-v1.5` into `models/bge-small-en-v1.5/`.

**Check:** The directory contains `model.onnx` (approximately 128 MB), `tokenizer.json`, and `config.json`.

### 3. Train the ranking model

`recommender-service` loads this model when it starts. Thus, train the model before you start the services.

```bash
cd training
pip install -r requirements.txt
python train_neural_ranker.py
cd ..
```

This step trains a pairwise XGBoost ranking model on the clickstream in `data/clickstream.csv`. It exports the model to `models/neural-ranker/model.onnx`. For the method and the results, see [training/TRAINING.md](training/TRAINING.md).

**Check:** The file `models/neural-ranker/model.onnx` exists.

If you train the model again while the services operate, restart `recommender-service`:

```bash
docker compose restart recommender-service
```

### 4. Start Pinecone Local and all services

```bash
./scripts/start-services.sh
```

This script builds and starts five containers. Then it waits until `graphql-gateway` responds.

If another application already uses port 8080 or 5173, set different ports before you start:

```bash
export GATEWAY_HOST_PORT=18080 WEB_HOST_PORT=15173
```

**Check:** Run `docker compose ps`. These five containers must show the status "running", not "restarting" or "exited":

- `pinecone-local`
- `search-service`
- `recommender-service`
- `graphql-gateway`
- `web`

### 5. Load the catalog into Pinecone

```bash
./scripts/ingest-catalog.sh
```

This script rebuilds `search-service`, so that it uses your latest local changes. Then it runs the ingestion tool in the Docker Compose network.

- The tool must run in the network, not on the host. Pinecone Local always returns its internal container hostname as the data-plane host. See [docs/PROJECT_STATE.md](docs/PROJECT_STATE.md).
- The tool calculates an embedding for each of the approximately 43,000 products and writes them to the `wands-products` index.
- This step uses much CPU time and takes a long time. The tool shows its progress at intervals.

**Check:** Run `curl -s http://localhost:5080/indexes/wands-products`. When the ingestion is complete, the vector count is more than zero.

### 6. Send a query to the GraphQL gateway

Open `http://localhost:8080/graphiql` and run this query:

```graphql
query {
  search(query: "platform bed frame", strategy: COLLABORATIVE, topK: 5) {
    products { name score source averageRating }
  }
}
```

**Check:**

- The response contains real WANDS products, not an error.
- Change `strategy` to each of `NONE`, `POPULARITY`, `COLLABORATIVE`, `BANDIT`, `NEURAL`, and `DIVERSE_POPULARITY`. The order of the results or the `source` field changes.

### 7. Use the web UI

Open `http://localhost:5173`.

**Check:**

- The search box returns results.
- A different strategy in the list changes the results.
- Each result shows a badge that tells why the system shows it.

### 8. Run the offline evaluation

```bash
./scripts/run-recommender-eval.sh
```

This script scores all six strategies against the held-out clickstream sessions. It writes `RESULTS.md` in the repository root. For the method, see [docs/EVALUATION.md](docs/EVALUATION.md).

**Check:** `RESULTS.md` has three tables. Each table has one row for each of the six strategies. If the code and the data did not change, only the latency column is different from the previous file.

### 9. Update the snapshots for the GitHub Pages demo

```bash
python3 scripts/capture_demo_snapshots.py http://localhost:8080/graphql
```

This script runs a set of queries with all six strategies. It writes the results into `docs/data/*.json`. The static demo page `docs/index.html` reads these files.

The first argument is the gateway URL. If you do not give it, the script uses `http://localhost:18080/graphql`.

**Check:**

- `docs/data/manifest.json` lists the captured files.
- Start a local web server in `docs/`, for example `python3 -m http.server`. Open the page and make sure that it shows the new data.

### Stop the system

```bash
./scripts/stop-services.sh
```

## Scripts

| Script | What it does |
|---|---|
| `scripts/download-data.sh` | Downloads the WANDS and clickstream files into `data/` |
| `scripts/download-models.sh` | Downloads the embedding model into `models/` |
| `scripts/start-services.sh` | Runs `docker compose up --build`, then waits for the gateway |
| `scripts/wait-for-gateway.sh` | Waits until a host responds. `start-services.sh` uses it. |
| `scripts/stop-services.sh` | Runs `docker compose down` |
| `scripts/ingest-catalog.sh` | Runs the ingestion tool in the Docker Compose network |
| `scripts/run-recommender-eval.sh` | Runs the offline evaluation and writes `RESULTS.md` |
| `scripts/capture_demo_snapshots.py` | Captures live query results into `docs/data/` for the GitHub Pages demo |
| `scripts/scan-cves.sh` | Runs the CI `cve-scan` job on your computer with Trivy |

## Run one part at a time, without Docker

Use this method when you develop one part of the system.

- **Java services.** First, install `rec-support`:

  ```bash
  mvn -pl rec-support install
  ```

  Then start a service with `mvn -pl <module> spring-boot:run`. Set the environment variables, for example `PINECONE_HOST` and `GRPC_PORT`, to `localhost` addresses instead of the Docker Compose service names.
- **Web UI.** In `web/`, run `npm run dev`. Set `VITE_GRAPHQL_URL=http://localhost:8080/graphql`.
- **Tests for one module.** Run `mvn -pl <module> test`. Do not use `-am`. The dependencies come from your local Maven repository. If you run builds in more than one terminal, `-am` can make two builds write to the same shared module at the same time.
