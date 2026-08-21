#!/usr/bin/env bash
set -euo pipefail

# Fetches the WANDS product catalog and the synthetic clickstream dataset
# into data/. product.csv/query.csv/label.csv come from the upstream Wayfair
# WANDS release; clickstream.csv comes from this project's own fork of WANDS
# (avantiwhenever/WANDS), where it was generated — see that repo's
# CLICKSTREAM.md for methodology. Not committed here, same convention as the
# sibling `search` project's scripts/download-dataset.sh.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_DIR="${ROOT_DIR}/data"
WANDS_UPSTREAM="https://raw.githubusercontent.com/wayfair/WANDS/main/dataset"
WANDS_FORK="https://raw.githubusercontent.com/avantiwhenever/WANDS/main/dataset"

mkdir -p "${DATA_DIR}"

for file in product.csv query.csv label.csv; do
  echo "Downloading ${file} (upstream WANDS)..."
  curl -fsSL "${WANDS_UPSTREAM}/${file}" -o "${DATA_DIR}/${file}"
done

echo "Downloading clickstream.csv (avantiwhenever/WANDS fork, synthetic)..."
curl -fsSL "${WANDS_FORK}/clickstream.csv" -o "${DATA_DIR}/clickstream.csv"

echo "Done. Row counts:"
for file in product.csv query.csv label.csv clickstream.csv; do
  count=$(($(wc -l < "${DATA_DIR}/${file}") - 1))
  echo "  ${file}: ${count} rows"
done
