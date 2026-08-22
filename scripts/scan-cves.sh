#!/usr/bin/env bash
set -euo pipefail

# Scans resolved Maven + npm dependencies for known CVEs via Trivy
# (brew install trivy). Mirrors the "cve-scan" job in
# .github/workflows/build.yml so failures are reproducible locally before
# they show up in CI — this is exactly how the real HIGH-severity Netty
# CVEs documented in docs/PROJECT_STATE.md were found and verified fixed.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

trivy fs --scanners vuln --severity CRITICAL,HIGH,MEDIUM .
