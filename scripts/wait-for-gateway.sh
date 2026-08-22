#!/usr/bin/env bash
set -euo pipefail

# Polls graphql-gateway until it's accepting connections. Any HTTP response
# counts as "up" — the point is confirming the process is listening, not
# that a given route exists.

HOST="${1:-http://localhost:8080}"
TIMEOUT_SECONDS="${2:-120}"

echo "Waiting for ${HOST} (up to ${TIMEOUT_SECONDS}s)..."
elapsed=0
while ! curl -s -o /dev/null "${HOST}/"; do
  if [ "${elapsed}" -ge "${TIMEOUT_SECONDS}" ]; then
    echo "graphql-gateway did not come up on ${HOST} within ${TIMEOUT_SECONDS}s" >&2
    exit 1
  fi
  sleep 2
  elapsed=$((elapsed + 2))
done

echo "graphql-gateway is up at ${HOST} (after ${elapsed}s)."
