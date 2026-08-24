#!/usr/bin/env bash
set -euo pipefail

# Embeds the WANDS catalog and upserts it into the running Pinecone Local
# instance. Must run *inside* the docker-compose network, not on the host —
# Pinecone Local's data-plane host discovery returns the container-internal
# hostname (`pinecone-local`) regardless of who's calling, which the host
# machine can't resolve even though the control-plane port is published to
# localhost. See docs/PROJECT_STATE.md for the full story.
#
# Requires: ./scripts/start-services.sh already run (pinecone-local up),
# and data/product.csv + models/bge-small-en-v1.5/ already fetched
# (scripts/download-data.sh / scripts/download-models.sh).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

echo "Rebuilding search-service (picks up any local code changes)..."
docker compose build search-service

# -Xmx1200m and a batch size of 100 (default is 500): on a machine also
# running other Docker workloads sharing the same VM memory budget (e.g.
# the sibling `search` project's Elasticsearch), the default batch size's
# peak per-batch memory has been observed to OOM-kill this process (exit
# 137) — see docs/PROJECT_STATE.md. The served search-service/
# recommender-service processes haven't shown similar pressure at steady
# state; this is specific to ingestion's batch embedding workload.
echo "Running the ingestion CLI inside the compose network..."
docker compose run --rm -e JAVA_TOOL_OPTIONS="-Xmx1200m" --entrypoint java search-service \
  -jar ingestion-cli.jar --data-dir /data --models-dir /models --batch-size 100 "$@"

echo "Done."
