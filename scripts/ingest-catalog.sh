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

echo "Running the ingestion CLI inside the compose network..."
docker compose run --rm --entrypoint java search-service \
  -jar ingestion-cli.jar --data-dir /data --models-dir /models "$@"

echo "Done."
