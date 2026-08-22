#!/usr/bin/env python3
"""Captures live GraphQL `search` results for a curated set of demo
queries x all 5 recommendation strategies against a locally-running
graphql-gateway (see HOWTO.md), writing one JSON file per query into
docs/data/.

Unlike the sibling `search` project's demo (which embeds a hand-copied
JS block into docs/index.html), this page's docs/app.js fetches these
JSON files directly at load time, so no manual "regenerate the embedded
block" step is needed after running this.

Usage: python3 scripts/capture_demo_snapshots.py [gateway_url] [user_id]
"""
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT_DIR / "docs" / "data"
TOP_K = 6

QUERIES = [
    "platform bed frame",
    "dining table",
    "accent chair",
    "outdoor patio furniture",
    "kids bunk bed",
    "coffee table",
]
STRATEGIES = ["NONE", "POPULARITY", "COLLABORATIVE", "BANDIT", "NEURAL"]

GQL = """
query Search($q: String!, $s: RecommenderStrategy!, $u: String, $k: Int!) {
  search(query: $q, strategy: $s, userId: $u, topK: $k) {
    strategy
    products { productId name productClass categoryHierarchy averageRating ratingCount score source }
  }
}
"""


def run_query(gateway_url, query, strategy, user_id):
    # gateway_url is a CLI arg (see main()) rather than a hardcoded literal,
    # so Semgrep's dynamic-urllib-use rule flags it — urllib also honors
    # file:// and other non-HTTP schemes, which would let a malicious value
    # read local files instead of hitting a server. Enforcing http(s) here
    # closes that off rather than just suppressing the finding.
    parsed_scheme = urllib.parse.urlparse(gateway_url).scheme
    if parsed_scheme not in ("http", "https"):
        raise ValueError(f"gateway_url must be http(s), got scheme {parsed_scheme!r}: {gateway_url!r}")

    payload = json.dumps({
        "query": GQL,
        "variables": {"q": query, "s": strategy, "u": user_id, "k": TOP_K},
    }).encode("utf-8")
    request = urllib.request.Request(
        gateway_url, data=payload, headers={"Content-Type": "application/json"}, method="POST"
    )
    # The rule is purely syntactic (any non-literal reaching urlopen trips
    # it) and can't see the scheme check above, which is the actual
    # mitigation the rule's own remediation text recommends.
    with urllib.request.urlopen(request, timeout=30) as response:  # nosemgrep: python.lang.security.audit.dynamic-urllib-use-detected.dynamic-urllib-use-detected
        body = json.loads(response.read())
    if "errors" in body:
        raise RuntimeError(f"GraphQL error for query={query!r} strategy={strategy}: {body['errors']}")
    return body["data"]["search"]


def main():
    gateway_url = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18080/graphql"
    user_id = sys.argv[2] if len(sys.argv) > 2 else "u00001"

    DATA_DIR.mkdir(parents=True, exist_ok=True)

    manifest = []
    for i, query in enumerate(QUERIES, start=1):
        print(f'Capturing "{query}"...')
        results_by_strategy = {}
        for strategy in STRATEGIES:
            result = run_query(gateway_url, query, strategy, user_id)
            results_by_strategy[strategy] = {
                "strategyLabel": result["strategy"],
                "products": result["products"],
            }
        out_path = DATA_DIR / f"q{i}.json"
        out_path.write_text(
            json.dumps({"query": query, "resultsByStrategy": results_by_strategy}, indent=2),
            encoding="utf-8",
        )
        manifest.append(out_path.name)
        print(f"  -> {out_path}")

    manifest_path = DATA_DIR / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"  -> {manifest_path}")

    print(f"Done. Captured {len(QUERIES)} queries x {len(STRATEGIES)} strategies into {DATA_DIR}/")


if __name__ == "__main__":
    main()
