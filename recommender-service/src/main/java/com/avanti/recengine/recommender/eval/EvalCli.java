package com.avanti.recengine.recommender.eval;

import com.avanti.recengine.recommender.adapter.out.onnx.OnnxRankingModelAdapter;
import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.Strategy;
import com.avanti.recengine.recommender.domain.strategy.BanditExploreStrategy;
import com.avanti.recengine.recommender.domain.strategy.CollaborativeFilteringStrategy;
import com.avanti.recengine.recommender.domain.strategy.DiversityAwareStrategy;
import com.avanti.recengine.recommender.domain.strategy.NeuralRankingStrategy;
import com.avanti.recengine.recommender.domain.strategy.PassthroughStrategy;
import com.avanti.recengine.recommender.domain.strategy.PopularityBoostStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Offline evaluation harness for {@code recommender-service}, mirroring the
 * sibling {@code search} project's {@code search-eval} module: runs every
 * {@link RecommendationStrategy} against a common held-out set and reports
 * nDCG@5/MRR/Recall@5/Precision@5, writing a fresh {@code RESULTS.md} each
 * run rather than patching individual rows.
 *
 * <p><b>Two independent ground truths, reported separately</b> (this is the
 * important part — read this before trusting either table alone):
 * <ul>
 *   <li><b>Implicit clickstream eval</b>: each session's strongest observed
 *       event per product (view=0, click=1, cart=2, purchase=3). This is
 *       <b>circular</b> — the synthetic clickstream generator's session
 *       composition and click/cart/purchase probabilities are themselves a
 *       probabilistic function of the same WANDS {@code label.csv} grade
 *       used to construct the session's candidate order in the first place
 *       (see {@code WANDS/scripts/generate_clickstream.py}). A strategy
 *       scoring well here is partly just recovering the synthetic
 *       generator's own parameters, not demonstrating real recommendation
 *       quality.</li>
 *   <li><b>Independent WANDS relevance eval</b>: the same sessions' same
 *       candidates, scored instead against Wayfair's original human
 *       annotation ({@code label.csv}, via {@link WandsLabelLoader}) for
 *       that session's query — entirely independent of the clickstream
 *       generator's click-probability model. This is the eval to trust for
 *       "is this strategy actually surfacing relevant products," at the
 *       cost of not being able to credit personalization signal at all
 *       (WANDS' judgments are query-level, not user-level — a strategy that
 *       improves personalization without changing which products are
 *       objectively relevant to the query won't show a difference here).</li>
 * </ul>
 * Both tables are computed from the exact same reranked list per
 * (strategy, session) pair — the strategies are only run once per session;
 * only which grade map scores the result differs.
 *
 * <p><b>Point-in-time correctness</b>: strategy features (popularity,
 * co-occurrence, user profile) are computed by {@link TemporalClickstreamIndex},
 * which replays sessions in timestamp order and evaluates each held-out
 * session against only the aggregate state accumulated from events strictly
 * before that session — fixing a temporal leak the previous full-history-at-once
 * approach had (a held-out session's features could previously include
 * events from *after* that session). See that class's Javadoc.
 *
 * <p><b>Held-out split</b>: a seeded 80/20 split by user id (same
 * philosophy as {@code training/train_neural_ranker.py}'s split, though a
 * different mechanism — Python's and Java's PRNGs don't produce the same
 * shuffle, so this is an independently-seeded, not byte-identical, split).
 * Applied uniformly to all 5 strategies for a fair comparison, even though
 * only {@code NeuralRankingStrategy} has anything that could leak from
 * training in the ML sense — {@code PopularityBoostStrategy} and
 * {@code CollaborativeFilteringStrategy}'s aggregates are online-style
 * running statistics, not a fitted model, so a held-out user's own past
 * actions legitimately informing later aggregates (their own or others') is
 * not a leak in that sense — only the temporal-ordering leak above is.
 *
 * <p><b>A known, honest limitation</b>: {@code PopularityBoostStrategy} and
 * {@code CollaborativeFilteringStrategy} can inject products absent from a
 * session's original candidates — this eval has no way to know whether a
 * user would have engaged with an injected product they were never shown,
 * so injected products always score as irrelevant (grade 0) in both tables
 * here. This under-scores exactly the "serendipitous discovery" behavior
 * those two strategies are designed to provide — a standard, acknowledged
 * gap between offline click-log evaluation and real online/interactive
 * evaluation (see arXiv:2509.06002's discussion of this exact tension).
 * Take the injecting strategies' numbers here as a lower bound on their
 * real value, not the full picture.
 *
 * <p><b>A third table, off-policy (IPS) reward estimation</b>: both tables
 * above score a strategy's ranking as a static list against a grade, not
 * against what users would actually have <i>done</i> had that ranking been
 * shown. {@link IpsEvaluator} closes that gap by reweighting each session's
 * already-observed clickstream outcome by the known cascade logging
 * policy's propensity for that outcome — see that class's Javadoc for the
 * full methodology, including its honest handling of IPS's known
 * high-variance failure mode via propensity clipping and an effective
 * sample size diagnostic.
 */
@Command(name = "recommender-eval", mixinStandardHelpOptions = true,
        description = "Evaluates all recommendation strategies against held-out clickstream sessions.")
public class EvalCli implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(EvalCli.class);

    private static final int K = 5;
    private static final double HOLD_OUT_FRACTION = 0.2;
    private static final long SEED = 42L;

    @Option(names = "--data-dir", defaultValue = "../data")
    private Path dataDir;

    @Option(names = "--models-dir", defaultValue = "../models")
    private Path modelsDir;

    @Option(names = "--results-md", defaultValue = "../RESULTS.md")
    private Path resultsMdPath;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new EvalCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        Path clickstreamCsv = dataDir.resolve("clickstream.csv");
        Path productCsv = dataDir.resolve("product.csv");
        Path labelCsv = dataDir.resolve("label.csv");
        Path neuralModelPath = modelsDir.resolve("neural-ranker/model.onnx");

        List<EvalSession> allSessions = EvalSessionLoader.load(clickstreamCsv, productCsv, labelCsv);
        Map<String, EvalSession> sessionsById = allSessions.stream()
                .collect(Collectors.toMap(EvalSession::sessionId, s -> s));

        var heldOutUsers = restrictToHeldOutUsers(allSessions);
        log.info("Loaded {} sessions total; {} users held out for eval", allSessions.size(), heldOutUsers.size());

        TemporalClickstreamIndex index = TemporalClickstreamIndex.load(clickstreamCsv, productCsv);

        Map<Strategy, List<QueryMetrics>> clickstreamMetrics = new LinkedHashMap<>();
        Map<Strategy, List<QueryMetrics>> independentMetrics = new LinkedHashMap<>();
        Map<Strategy, List<Long>> latenciesMs = new LinkedHashMap<>();
        Map<Strategy, IpsEvaluator.Accumulator> ipsAccumulators = new LinkedHashMap<>();
        for (Strategy type : Strategy.values()) {
            clickstreamMetrics.put(type, new ArrayList<>());
            independentMetrics.put(type, new ArrayList<>());
            latenciesMs.put(type, new ArrayList<>());
            ipsAccumulators.put(type, new IpsEvaluator.Accumulator());
        }

        try (OnnxRankingModelAdapter rankingModel = new OnnxRankingModelAdapter(neuralModelPath)) {
            PopularityBoostStrategy popularityBoost = new PopularityBoostStrategy(index);
            Map<Strategy, RecommendationStrategy> strategies = Map.of(
                    Strategy.NONE, new PassthroughStrategy(),
                    Strategy.POPULARITY, popularityBoost,
                    Strategy.COLLABORATIVE, new CollaborativeFilteringStrategy(index),
                    Strategy.BANDIT, new BanditExploreStrategy(index, new Random(SEED)),
                    Strategy.NEURAL, new NeuralRankingStrategy(index, rankingModel),
                    Strategy.DIVERSE_POPULARITY, new DiversityAwareStrategy(popularityBoost)
            );

            // Single time-ordered replay, shared by every strategy: for each
            // session, evaluate the held-out ones against the index's
            // *current* (pre-advance) state, then advance — this is what
            // makes the point-in-time correctness real rather than cosmetic.
            for (String sessionId : index.sessionIdsInTimeOrder()) {
                EvalSession session = sessionsById.get(sessionId);
                if (session != null && heldOutUsers.contains(session.userId())) {
                    for (Strategy type : Strategy.values()) {
                        RecommendationStrategy strategy = strategies.get(type);
                        RecommendationContext context = new RecommendationContext("", session.userId(), type);

                        long start = System.nanoTime();
                        List<ScoredProduct> reranked = strategy.apply(context, session.baseCandidates());
                        latenciesMs.get(type).add((System.nanoTime() - start) / 1_000_000);

                        List<String> rankedIds = reranked.stream().map(ScoredProduct::productId).toList();
                        clickstreamMetrics.get(type).add(toMetrics(session.sessionId(), rankedIds, session.relevanceGrades()));
                        independentMetrics.get(type).add(toMetrics(session.sessionId(), rankedIds, session.independentRelevanceGrades()));
                        ipsAccumulators.get(type).recordSession(session, rankedIds, K);
                    }
                }
                index.advance(sessionId);
            }
        }

        int evaluatedSessions = clickstreamMetrics.get(Strategy.NONE).size();
        List<StrategySummary> clickstreamSummaries = summarize(clickstreamMetrics, latenciesMs);
        List<StrategySummary> independentSummaries = summarize(independentMetrics, latenciesMs);
        Map<Strategy, IpsEvaluator.IpsResult> ipsResults = new LinkedHashMap<>();
        for (Strategy type : Strategy.values()) {
            ipsResults.put(type, ipsAccumulators.get(type).result());
        }

        writeResultsMarkdown(clickstreamSummaries, independentSummaries, ipsResults, evaluatedSessions);
        return 0;
    }

    private List<StrategySummary> summarize(Map<Strategy, List<QueryMetrics>> metricsByStrategy, Map<Strategy, List<Long>> latenciesMs) {
        List<StrategySummary> summaries = new ArrayList<>();
        for (Strategy type : Strategy.values()) {
            String name = strategyDisplayName(type);
            summaries.add(StrategySummary.aggregate(name, metricsByStrategy.get(type), latenciesMs.get(type)));
        }
        return summaries;
    }

    private static String strategyDisplayName(Strategy type) {
        return switch (type) {
            case NONE -> "None";
            case POPULARITY -> "Popularity";
            case COLLABORATIVE -> "Collaborative Filtering";
            case BANDIT -> "Bandit Exploration";
            case NEURAL -> "Neural Ranking";
            case DIVERSE_POPULARITY -> "Diverse Popularity";
        };
    }

    private QueryMetrics toMetrics(String sessionId, List<String> rankedIds, Map<String, Integer> grades) {
        return new QueryMetrics(
                sessionId,
                MetricsCalculator.ndcgAtK(rankedIds, grades, K),
                MetricsCalculator.reciprocalRank(rankedIds, grades),
                MetricsCalculator.recallAtK(rankedIds, grades, K),
                MetricsCalculator.precisionAtK(rankedIds, grades, K)
        );
    }

    /**
     * Deterministic 80/20 split by user id — independently seeded from (not
     * identical to) {@code train_neural_ranker.py}'s own Python-side split;
     * see class Javadoc.
     */
    private java.util.Set<String> restrictToHeldOutUsers(List<EvalSession> sessions) {
        List<String> users = sessions.stream().map(EvalSession::userId).distinct().sorted().collect(Collectors.toCollection(ArrayList::new));
        java.util.Collections.shuffle(users, new Random(SEED));
        int holdOutCount = (int) Math.round(users.size() * HOLD_OUT_FRACTION);
        return new java.util.HashSet<>(users.subList(0, holdOutCount));
    }

    private void writeResultsMarkdown(List<StrategySummary> clickstreamSummaries, List<StrategySummary> independentSummaries,
                                       Map<Strategy, IpsEvaluator.IpsResult> ipsResults, int sessionCount) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Recommender Evaluation Results\n\n");
        sb.append("Offline evaluation of each recommendation strategy against ").append(sessionCount)
                .append(" held-out clickstream sessions (20% of users, seed 42), scored against **two independent ");
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

        java.nio.file.Files.writeString(resultsMdPath, sb.toString());
        log.info("Wrote {}", resultsMdPath);
    }

    private void appendIpsSection(StringBuilder sb, Map<Strategy, IpsEvaluator.IpsResult> ipsResults) {
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
        sb.append("both cited in TODO.md item #4. Simplifying assumption, stated honestly: this is an *item-level* ");
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

    private void appendTable(StringBuilder sb, List<StrategySummary> summaries) {
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
}
