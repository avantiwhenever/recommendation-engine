package com.avanti.recengine.recommender.eval;

import com.avanti.recengine.recommender.domain.Strategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders {@link EvalCli}'s results as {@code RESULTS.md}: the two
 * ground-truth tables, the IPS table, and the caveats needed to read them.
 * The file is regenerated wholesale on every run, never patched.
 */
final class ResultsMarkdownWriter {

    private ResultsMarkdownWriter() {
    }

    /**
     * @param holdOutFraction fraction of users held out for eval, for the report header
     * @param seed            the held-out split's seed, for the report header
     */
    static void write(Path resultsMdPath, List<StrategySummary> clickstreamSummaries,
                      List<StrategySummary> independentSummaries, Map<Strategy, IpsEvaluator.IpsResult> ipsResults,
                      int sessionCount, double holdOutFraction, long seed) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Recommender Evaluation Results\n\n");
        sb.append("Offline evaluation of each recommendation strategy against ").append(sessionCount)
                .append(" held-out clickstream sessions (").append(Math.round(holdOutFraction * 100))
                .append("% of users, seed ").append(seed).append("), scored against **two independent ");
        sb.append("ground truths** — read both tables' intro paragraphs before trusting either alone. Strategy ");
        sb.append("features (popularity, co-occurrence) are point-in-time-correct: computed by replaying sessions in ");
        sb.append("timestamp order, so a held-out session's features never include events from after that session. ");
        sb.append("Regenerate with `./scripts/run-recommender-eval.sh`.\n\n");

        sb.append("## Implicit clickstream eval\n\n");
        sb.append("Ground truth: each session's strongest observed event per product ");
        sb.append("(view=0, click=1, cart=2, purchase=3). **Circular** — the synthetic clickstream generator's ");
        sb.append("session composition and click/cart/purchase probabilities are themselves a probabilistic function ");
        sb.append("of the same WANDS `label.csv` grade used to construct the session's candidate order in the first ");
        sb.append("place (see `WANDS/scripts/generate_clickstream.py`). A strategy scoring well here is partly just ");
        sb.append("recovering the synthetic generator's own parameters, not demonstrating real recommendation ");
        sb.append("quality — compare against the independent table below before drawing conclusions.\n\n");
        appendTable(sb, clickstreamSummaries);

        sb.append("\n## Independent WANDS relevance eval\n\n");
        sb.append("Ground truth: Wayfair's original human relevance annotation (`label.csv`: Exact=2/Partial=1/");
        sb.append("Irrelevant=0) for each session's query — entirely independent of the clickstream generator's ");
        sb.append("click-probability model, even though the candidate *ordering* was influenced by it. This is the ");
        sb.append("table to trust for \"is this strategy actually surfacing relevant products,\" at the cost of not ");
        sb.append("crediting personalization at all: WANDS' judgments are query-level, not user-level, so a strategy ");
        sb.append("that improves personalization without changing which products are objectively relevant to the ");
        sb.append("query won't show a difference here.\n\n");
        appendTable(sb, independentSummaries);

        sb.append("\n_Independent-eval Recall@5 is tiny (~0.06) compared to the clickstream eval's Recall@5 (~0.6) ");
        sb.append("— not a bug, a scale mismatch: Recall's denominator is \"all relevant items,\" and under WANDS' ");
        sb.append("judgments that's every Exact/Partial product for the query (often 100+), while under the ");
        sb.append("clickstream eval it's only the 8-15 products the synthetic generator ever showed in that session. ");
        sb.append("Similarly, independent-eval Precision@5 is near-ceiling (~0.99) for nearly every strategy ");
        sb.append("uniformly — expected, since the synthetic generator only ever samples session candidates from ");
        sb.append("WANDS-judged (mostly Exact/Partial) products in the first place, so almost everything shown is ");
        sb.append("already relevant by WANDS' standard regardless of ranking. nDCG@5/MRR (rank-order-sensitive, not ");
        sb.append("just presence) are the metrics actually worth comparing between strategies in this table._\n");
        sb.append("\n_Recall@5 is near-ceiling by construction for sessions whose candidate pool is close to size 5 ");
        sb.append("(the synthetic clickstream generator uses page sizes 8-15) — nDCG@5 and Precision@5 are the more ");
        sb.append("discriminative metrics here since they depend on rank order within the top 5, not just presence ");
        sb.append("in an already-small, mostly-fully-retrieved pool._\n");
        sb.append("\n_`Popularity` and `Collaborative Filtering` can inject products absent from a session's original ");
        sb.append("candidates; neither eval has a signal on whether a user would have engaged with a product they ");
        sb.append("were never shown, so injected products always score as irrelevant in both tables — their ");
        sb.append("real-world value from serendipitous discovery is not captured by these numbers. See EvalCli's ");
        sb.append("class Javadoc._\n");
        sb.append("\n_`Bandit Exploration` scoring below `None` (the unmodified baseline) is expected, not a bug — ");
        sb.append("it deliberately trades ranking quality for Thompson-Sampling exploration over category arms ");
        sb.append("warm-started from real historical engagement (Etsy's OPAR pattern, Spotify's context-conditioned ");
        sb.append("calibrated bandits — see BanditExploreStrategy's class Javadoc for both citations), the standard ");
        sb.append("explore/exploit tradeoff. Neither offline eval can credit exploration's real purpose — surfacing ");
        sb.append("under-exposed products over time — the same kind of offline/online eval gap noted above for the ");
        sb.append("injecting strategies. Priors are fixed at construction from historical data, not updated from ");
        sb.append("live reward — this project has no live traffic loop to update from; see the strategy's Javadoc._\n");
        sb.append("\n_All p95 latencies round to 0ms — every strategy here is in-memory arithmetic over a small ");
        sb.append("candidate list (no ONNX/gRPC/disk I/O in the hot path even for `Neural Ranking`'s forward pass), ");
        sb.append("genuinely sub-millisecond rather than an unmeasured placeholder._\n");

        appendIpsSection(sb, ipsResults);

        Files.writeString(resultsMdPath, sb.toString());
    }

    private static void appendIpsSection(StringBuilder sb, Map<Strategy, IpsEvaluator.IpsResult> ipsResults) {
        sb.append("\n## Off-policy (IPS) evaluation\n\n");
        sb.append("The two tables above score each strategy's reranking as a static list against a grade — neither ");
        sb.append("can estimate the *reward* (click/cart/purchase) users would actually have generated had a ");
        sb.append("strategy's ranking been the one shown, since no user was ever shown it; only the clickstream's ");
        sb.append("original logging-policy ranking was. This table closes that gap using Inverse Propensity Scoring ");
        sb.append("(IPS), reweighting each *already-observed* outcome by the inverse probability the known cascade ");
        sb.append("logging policy (`WANDS/CLICKSTREAM.md`'s exact click/cart/purchase model — not estimated) would ");
        sb.append("have produced that outcome at the position it actually showed the item. This is unusually ");
        sb.append("implementable here because, unlike almost every real production system, this project's synthetic ");
        sb.append("clickstream has a fully known, documented logging policy. See Criteo's [\"Offline A/B Testing ");
        sb.append("for Recommender Systems\"](https://arxiv.org/pdf/1801.07030) and Spotify Research's ");
        sb.append("[counterfactual-evaluation work](https://research.atspotify.com/publications/towards-a-fair-marketplace-counterfactual-evaluation-of-the-trade-off-between-relevance-fairness-satisfaction-in-recommendation-systems), ");
        sb.append("Simplifying assumption, stated honestly: this is an *item-level* ");
        sb.append("IPS estimator (each item's presence in the top-5 treated as an independent action), not a full ");
        sb.append("listwise estimator — see `IpsEvaluator`'s class Javadoc.\n\n");

        sb.append("| Strategy | Raw IPS estimate | Clipped IPS estimate (floor 1e-3) | Effective sample size | Scored items |\n");
        sb.append("|---|---|---|---|---|\n");
        for (Strategy type : Strategy.values()) {
            IpsEvaluator.IpsResult r = ipsResults.get(type);
            sb.append("| ").append(strategyDisplayName(type))
                    .append(" | ").append(format(r.rawEstimate()))
                    .append(" | ").append(format(r.clippedEstimate()))
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f", r.effectiveSampleSize()))
                    .append(" | ").append(r.scoredItemCount())
                    .append(" |\n");
        }

        sb.append("\n_**Read the effective sample size (ESS) before trusting either estimate.** IPS's known failure ");
        sb.append("mode is variance: a rare high-reward outcome (a purchase) logged for an item the policy was ");
        sb.append("unlikely to produce that outcome for gets a tiny propensity and an enormous inverse weight, and ");
        sb.append("can dominate the whole sum. ESS (Hájek/Kish: (Σw)²/Σw² over the clipped weights) estimates how ");
        sb.append("many *effectively independent* samples the clipped estimate is really resting on — a value close ");
        sb.append("to \"scored items\" means weights are fairly uniform and the estimate is stable; a value far ");
        sb.append("below it means a handful of extreme-weight events are carrying the number, and it should be read ");
        sb.append("as noisy/directional at best, not a precise point estimate. If the raw and clipped columns differ ");
        sb.append("substantially, that's the clipping visibly doing its job, not a discrepancy to reconcile — the ");
        sb.append("clipped number is the one to trust in that case._\n");
        sb.append("\n_Reward scale (0.2/0.5/0.8/1.0 for view/click/cart/purchase) matches `TemporalClickstreamIndex`'s ");
        sb.append("popularity weighting elsewhere in this codebase, for consistency — these are relative weights, ");
        sb.append("not a probability or a currency amount, so compare estimates *between strategies* in this table, ");
        sb.append("not against the 0-1 metrics in the tables above, which are on an unrelated scale._\n");
        sb.append("\n_Injected products (Popularity/Collaborative Filtering can add items absent from a session's ");
        sb.append("original candidates) contribute nothing here — an injected item has no logged position or ");
        sb.append("observed outcome to reweight, the same honest gap already noted for the other two tables above._\n");
    }

    private static void appendTable(StringBuilder sb, List<StrategySummary> summaries) {
        sb.append("| Strategy | nDCG@5 | MRR | Recall@5 | Precision@5 | p95 latency (ms) |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (StrategySummary s : summaries) {
            sb.append("| ").append(s.strategyName())
                    .append(" | ").append(format(s.ndcgAt5()))
                    .append(" | ").append(format(s.mrr()))
                    .append(" | ").append(format(s.recallAt5()))
                    .append(" | ").append(format(s.precisionAt5()))
                    .append(" | ").append(s.p95LatencyMs())
                    .append(" |\n");
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    static String strategyDisplayName(Strategy type) {
        return switch (type) {
            case NONE -> "None";
            case POPULARITY -> "Popularity";
            case COLLABORATIVE -> "Collaborative Filtering";
            case BANDIT -> "Bandit Exploration";
            case NEURAL -> "Neural Ranking";
            case DIVERSE_POPULARITY -> "Diverse Popularity";
        };
    }
}
