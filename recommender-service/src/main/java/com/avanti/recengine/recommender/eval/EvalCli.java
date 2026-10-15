package com.avanti.recengine.recommender.eval;

import com.avanti.recengine.recommender.adapter.out.onnx.OnnxRankingModelAdapter;
import com.avanti.recengine.recommender.domain.RecommendationContext;
import com.avanti.recengine.recommender.domain.RecommendationStrategy;
import com.avanti.recengine.recommender.domain.ScoredProduct;
import com.avanti.recengine.recommender.domain.Strategy;
import com.avanti.recengine.recommender.domain.strategy.Strategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
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
 * before that session — a held-out session's features never include events
 * from *after* that session. See that class's Javadoc.
 *
 * <p><b>Held-out split</b>: a seeded 80/20 split by user id (same
 * philosophy as {@code training/train_neural_ranker.py}'s split, though a
 * different mechanism — Python's and Java's PRNGs don't produce the same
 * shuffle, so this is an independently-seeded, not byte-identical, split).
 * Applied uniformly to all 6 strategies for a fair comparison, even though
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

    /** Everything recorded for one strategy across all evaluated sessions. */
    private record StrategyRun(List<QueryMetrics> clickstreamMetrics, List<QueryMetrics> independentMetrics,
                               List<Long> latenciesMs, IpsEvaluator.Accumulator ips) {
        StrategyRun() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new IpsEvaluator.Accumulator());
        }
    }

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
        Set<String> heldOutUsers = heldOutUsers(allSessions);
        log.info("Loaded {} sessions total; {} users held out for eval", allSessions.size(), heldOutUsers.size());

        TemporalClickstreamIndex index = TemporalClickstreamIndex.load(clickstreamCsv, productCsv);
        Map<Strategy, StrategyRun> runs;
        try (OnnxRankingModelAdapter rankingModel = new OnnxRankingModelAdapter(neuralModelPath)) {
            Map<Strategy, RecommendationStrategy> strategies =
                    Strategies.create(index, rankingModel, null, new Random(SEED));
            runs = replay(index, strategies, allSessions, heldOutUsers);
        }

        ResultsMarkdownWriter.write(resultsMdPath,
                summarize(runs, StrategyRun::clickstreamMetrics),
                summarize(runs, StrategyRun::independentMetrics),
                ipsResults(runs),
                runs.get(Strategy.NONE).clickstreamMetrics().size(),
                HOLD_OUT_FRACTION, SEED);
        log.info("Wrote {}", resultsMdPath);
        return 0;
    }

    /**
     * Single time-ordered replay, shared by every strategy: for each
     * session, evaluate the held-out ones against the index's
     * <i>current</i> (pre-advance) state, then advance — this is what makes
     * the point-in-time correctness real rather than cosmetic.
     */
    private static Map<Strategy, StrategyRun> replay(TemporalClickstreamIndex index,
                                                     Map<Strategy, RecommendationStrategy> strategies,
                                                     List<EvalSession> allSessions, Set<String> heldOutUsers) {
        Map<String, EvalSession> sessionsById = allSessions.stream()
                .collect(Collectors.toMap(EvalSession::sessionId, Function.identity()));
        Map<Strategy, StrategyRun> runs = new EnumMap<>(Strategy.class);
        for (Strategy type : Strategy.values()) {
            runs.put(type, new StrategyRun());
        }

        for (String sessionId : index.sessionIdsInTimeOrder()) {
            EvalSession session = sessionsById.get(sessionId);
            if (session != null && heldOutUsers.contains(session.userId())) {
                for (Strategy type : Strategy.values()) {
                    evaluate(strategies.get(type), type, session, runs.get(type));
                }
            }
            index.advance(sessionId);
        }
        return runs;
    }

    /**
     * Runs one strategy once on one session; both ground truths and IPS
     * score that same reranked list.
     */
    private static void evaluate(RecommendationStrategy strategy, Strategy type, EvalSession session, StrategyRun run) {
        RecommendationContext context = new RecommendationContext("", session.userId(), type);

        long start = System.nanoTime();
        List<ScoredProduct> reranked = strategy.apply(context, session.baseCandidates());
        run.latenciesMs().add((System.nanoTime() - start) / 1_000_000);

        List<String> rankedIds = reranked.stream().map(ScoredProduct::productId).toList();
        run.clickstreamMetrics().add(toMetrics(session.sessionId(), rankedIds, session.relevanceGrades()));
        run.independentMetrics().add(toMetrics(session.sessionId(), rankedIds, session.independentRelevanceGrades()));
        run.ips().recordSession(session, rankedIds, K);
    }

    private static QueryMetrics toMetrics(String sessionId, List<String> rankedIds, Map<String, Integer> grades) {
        return new QueryMetrics(
                sessionId,
                MetricsCalculator.ndcgAtK(rankedIds, grades, K),
                MetricsCalculator.reciprocalRank(rankedIds, grades),
                MetricsCalculator.recallAtK(rankedIds, grades, K),
                MetricsCalculator.precisionAtK(rankedIds, grades, K)
        );
    }

    private static List<StrategySummary> summarize(Map<Strategy, StrategyRun> runs,
                                                   Function<StrategyRun, List<QueryMetrics>> groundTruth) {
        List<StrategySummary> summaries = new ArrayList<>();
        for (Strategy type : Strategy.values()) {
            StrategyRun run = runs.get(type);
            summaries.add(StrategySummary.aggregate(
                    ResultsMarkdownWriter.strategyDisplayName(type), groundTruth.apply(run), run.latenciesMs()));
        }
        return summaries;
    }

    private static Map<Strategy, IpsEvaluator.IpsResult> ipsResults(Map<Strategy, StrategyRun> runs) {
        Map<Strategy, IpsEvaluator.IpsResult> results = new EnumMap<>(Strategy.class);
        runs.forEach((type, run) -> results.put(type, run.ips().result()));
        return results;
    }

    /**
     * Deterministic 80/20 split by user id — independently seeded from (not
     * identical to) {@code train_neural_ranker.py}'s own Python-side split;
     * see class Javadoc.
     */
    private static Set<String> heldOutUsers(List<EvalSession> sessions) {
        List<String> users = sessions.stream().map(EvalSession::userId).distinct().sorted()
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(users, new Random(SEED));
        int holdOutCount = (int) Math.round(users.size() * HOLD_OUT_FRACTION);
        return new HashSet<>(users.subList(0, holdOutCount));
    }
}
