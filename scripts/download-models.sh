#!/usr/bin/env bash
set -euo pipefail

# Fetches the pre-exported ONNX weights + tokenizer for the embedding model
# (BAAI/bge-small-en-v1.5), via Xenova's ONNX export, into models/ — same
# model and source as the sibling `search` project, ported here because
# Pinecone Local has no integrated (server-side) inference, so embeddings
# must be generated client-side regardless.
# https://huggingface.co/Xenova/bge-small-en-v1.5
#
# The neural-ranker model (models/neural-ranker/model.onnx) is NOT fetched
# here — it's trained locally from the clickstream dataset via
# training/train_neural_ranker.py, which writes its ONNX export directly
# into models/neural-ranker/.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="${ROOT_DIR}/models"

fetch_model() {
  local repo="$1"
  local dir="${MODELS_DIR}/$2"
  local base="https://huggingface.co/${repo}/resolve/main"

  mkdir -p "${dir}"
  echo "Downloading ${repo}..."
  curl -fsSL "${base}/onnx/model.onnx" -o "${dir}/model.onnx"
  curl -fsSL "${base}/tokenizer.json" -o "${dir}/tokenizer.json"
  curl -fsSL "${base}/config.json" -o "${dir}/config.json"
  du -h "${dir}"/*
}

fetch_model "Xenova/bge-small-en-v1.5" "bge-small-en-v1.5"

echo "Done."
