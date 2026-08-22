#!/usr/bin/env bash
set -euo pipefail

# Builds and runs recommender-service's offline evaluation CLI (EvalCli),
# which scores every recommendation strategy against held-out clickstream
# sessions and writes a fresh RESULTS.md at the repo root. See
# recommender-service/.../eval/EvalCli.java's class Javadoc for the full
# methodology (implicit relevance grades, held-out split, and honest
# limitations of offline click-log evaluation).
#
# Requires data/clickstream.csv and data/product.csv (scripts/download-data.sh)
# and models/neural-ranker/model.onnx (training/train_neural_ranker.py) to
# already exist.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mvn -q -B -pl recommender-service -am package -DskipTests
JAR=$(find "${ROOT_DIR}/recommender-service/target" -maxdepth 1 -name "*-eval-cli.jar")

(cd "${ROOT_DIR}/recommender-service" && java -jar "${JAR}")

echo "Done. See ${ROOT_DIR}/RESULTS.md"
