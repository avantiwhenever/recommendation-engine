// Keys are the strategies' own domain name() values, lowercased — see
// each RecommendationStrategy implementation in recommender-service for
// the exact string ("Collaborative Filtering", not "collaborative"). Kept
// in sync with docs/app.js's SOURCE_LABELS, the same badge language in
// both the live React app and the static GitHub Pages demo.
const SOURCE_LABELS: Record<string, string> = {
  search: "Matched your search",
  none: "Baseline search result",
  popularity: "Boosted — popular with other shoppers",
  "collaborative filtering": "Boosted — shoppers like you also viewed this",
  "bandit exploration": "Surfaced for exploration",
  "neural ranking": "Ranked by the neural model",
  "popularity (diversified)": "Boosted for popularity, then spread across categories",
};

/** Maps a raw backend `source` string (a strategy name, or "search") to a human-readable badge label. */
export function humanizeSource(source: string): string {
  const key = source.trim().toLowerCase();
  return SOURCE_LABELS[key] ?? source;
}
