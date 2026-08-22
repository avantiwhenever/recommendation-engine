#!/usr/bin/env bash
set -euo pipefail

# Starts the full stack via docker-compose: Pinecone Local, all three Java
# services, and the web UI. Host ports for graphql-gateway/web are
# overridable (see docker-compose.yml) if 8080/5173 are already taken by
# another project on this machine — e.g.:
#   GATEWAY_HOST_PORT=18080 WEB_HOST_PORT=15173 ./scripts/start-services.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

docker compose up -d --build

echo "Started. Waiting for graphql-gateway to accept connections..."
"${ROOT_DIR}/scripts/wait-for-gateway.sh" "http://localhost:${GATEWAY_HOST_PORT:-8080}"

echo ""
echo "Stack is up:"
echo "  Web UI:          http://localhost:${WEB_HOST_PORT:-5173}"
echo "  GraphQL gateway:  http://localhost:${GATEWAY_HOST_PORT:-8080}/graphiql"
echo ""
echo "If Pinecone hasn't been populated yet, run ./scripts/ingest-catalog.sh next."
