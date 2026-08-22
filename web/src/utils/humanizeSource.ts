const SOURCE_LABELS: Record<string, string> = {
  search: "Matched your search",
  passthrough: "Baseline search result",
  none: "Baseline search result",
  popularity: "Boosted — popular with other shoppers",
  collaborative: "Boosted — shoppers like you also viewed this",
  bandit: "Surfaced for exploration",
  neural: "Ranked by the neural model",
};

/** Maps a raw backend `source` string (a strategy name, or "search") to a human-readable badge label. */
export function humanizeSource(source: string): string {
  const key = source.trim().toLowerCase();
  return SOURCE_LABELS[key] ?? source;
}
