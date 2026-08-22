#!/usr/bin/env bash
set -uo pipefail

# Stops the full docker-compose stack (pinecone-local + all three Java
# services + web). Safe to run even if nothing is up.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

if docker compose ps -q 2>/dev/null | grep -q .; then
  echo "Stopping the recommendation-engine docker-compose stack..."
  docker compose down
else
  echo "Nothing appears to be running for this stack."
fi
