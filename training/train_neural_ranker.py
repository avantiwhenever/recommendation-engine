#!/usr/bin/env python3
"""Trains the neural ranking model used by NeuralRankingStrategy (recommender-service).

Builds implicit-feedback (user, product) pairs from data/clickstream.csv,
computes the 7 features documented in NeuralRankingStrategy's Javadoc (and
duplicated below — the two MUST stay in sync; see
feature_parity_fixtures.csv / test_feature_parity.py / FeatureParityTest.java
for the golden-vector test that now catches drift automatically instead of
relying on someone re-reading a comment table), trains a ranking model, and
exports it to ONNX at models/neural-ranker/model.onnx.

**Training objective**: pairwise (XGBoost `rank:ndcg`, i.e. LambdaMART-style
— pairwise gradients weighted by the |NDCG delta| swapping each pair would
cause), not pointwise regression. The held-out evaluation metric has always
been pairwise ranking accuracy; training pointwise (as an earlier version of
this script did, via sklearn's MLPRegressor) optimized a different objective
than the one being measured. See TRAINING.md's "Training objective" section
for the actual numbers this change produced, including an honest comparison
against the old pointwise model and a linear-combination-of-all-7-features
baseline that the single-feature ablations alone didn't previously rule out.

See TRAINING.md for the full methodology, the actual held-out numbers this
run produced, and an honest account of where the training-time features are
a proxy for what's available at serving time (they aren't identical — see
FEATURE 1 below).

Usage:
  python3 train_neural_ranker.py [--seed 42]              # train + export the model
  python3 train_neural_ranker.py --ci-seeds 5              # also report a
                                                             # multi-seed mean/std
                                                             # confidence interval
"""
import argparse
import csv
import math
import random
import statistics
from collections import defaultdict
from pathlib import Path

import numpy as np
import xgboost as xgb
from onnxmltools import convert_xgboost
from onnxmltools.convert.common.data_types import FloatTensorType as MTFloatTensorType
from onnxmltools.convert.xgboost._parse import WrappedBooster
from sklearn.linear_model import LogisticRegression

ROOT_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT_DIR / "data"
MODEL_OUT = ROOT_DIR / "models" / "neural-ranker" / "model.onnx"

EVENT_WEIGHT = {"view": 0.2, "click": 0.5, "add_to_cart": 0.8, "purchase": 1.0}
INTERACTION_EVENTS = {"click", "add_to_cart", "purchase"}
FEATURE_NAMES = [
    "category_match", "base_score_proxy", "popularity_log",
    "co_occurrence_log", "avg_rating_over_5", "rating_count_log",
    "session_category_overlap",
]


def top_level_category(category_hierarchy):
    if not category_hierarchy:
        return None
    return category_hierarchy.split("/")[0].strip()


def load_products():
    products = {}
    with open(DATA_DIR / "product.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            avg_rating = row["average_rating"]
            rating_count = row["rating_count"]
            products[row["product_id"]] = {
                "category": row["category hierarchy"] or None,
                "avg_rating": float(avg_rating) if avg_rating else 0.0,
                "rating_count": int(float(rating_count)) if rating_count else 0,
            }
    return products


def load_clickstream():
    rows = []
    with open(DATA_DIR / "clickstream.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            rows.append(row)
    return rows


def build_aggregates(rows, products):
    """Mirrors CsvClickstreamRepositoryAdapter's aggregation exactly, so the
    popularity/co-occurrence features are computed identically to serving time."""
    popularity = defaultdict(float)
    interacted_by_session = defaultdict(set)
    user_of_session = {}
    interacted_by_user = defaultdict(set)
    category_counts_by_user = defaultdict(lambda: defaultdict(int))
    best_position = defaultdict(lambda: math.inf)  # (user, product) -> min position ever shown at
    # FEATURE 7 support: which session(s) each (user, product) interaction
    # pair actually occurred in, and which sessions belong to each user —
    # lets training reconstruct a real "recent products this session"
    # context per training pair, the same real signal RecommendRequest's
    # recent_product_ids field carries at serving time (see
    # NeuralRankingStrategy's Javadoc, feature 7).
    sessions_by_user_product = defaultdict(set)
    sessions_by_user = defaultdict(set)

    for row in rows:
        user_id, session_id, product_id, event_type = (
            row["user_id"], row["session_id"], row["product_id"], row["event_type"]
        )
        popularity[product_id] += EVENT_WEIGHT.get(event_type, 0.0)
        position = int(row["position"])
        best_position[(user_id, product_id)] = min(best_position[(user_id, product_id)], position)

        if event_type not in INTERACTION_EVENTS:
            continue
        user_of_session[session_id] = user_id
        interacted_by_session[session_id].add(product_id)
        interacted_by_user[user_id].add(product_id)
        sessions_by_user_product[(user_id, product_id)].add(session_id)
        sessions_by_user[user_id].add(session_id)
        category = top_level_category(products.get(product_id, {}).get("category"))
        if category:
            category_counts_by_user[user_id][category] += 1

    co_occurrence = defaultdict(lambda: defaultdict(int))
    for session_products in interacted_by_session.values():
        for a in session_products:
            for b in session_products:
                if a != b:
                    co_occurrence[a][b] += 1

    all_positions = [int(row["position"]) for row in rows]

    return {
        "popularity": popularity,
        "interacted_by_user": interacted_by_user,
        "category_counts_by_user": category_counts_by_user,
        "co_occurrence": co_occurrence,
        "best_position": best_position,
        "all_positions": all_positions,
        "interacted_by_session": interacted_by_session,
        "sessions_by_user_product": sessions_by_user_product,
        "sessions_by_user": sessions_by_user,
    }


def session_category_segments(recent_product_ids, products):
    """Resolves a session's product IDs to their top-level category segments
    — mirrors NeuralRankingStrategy.sessionCategorySegments (Java): one
    entry per resolvable ID, unresolvable/uncategorized IDs are skipped, not
    padded with a placeholder."""
    segments = []
    for pid in recent_product_ids:
        category = top_level_category(products.get(pid, {}).get("category"))
        if category:
            segments.append(category)
    return segments


def build_features(user_id, product_id, products, aggregates, position_for_pair, recent_category_segments=None):
    product = products.get(product_id, {"category": None, "avg_rating": 0.0, "rating_count": 0})
    user_categories = aggregates["category_counts_by_user"].get(user_id, {})
    top_category = max(user_categories, key=user_categories.get) if user_categories else None

    category_match = 1.0 if (top_category and top_level_category(product["category"]) == top_category) else 0.0

    # FEATURE 1 (base_score_proxy) — the one feature with real, documented
    # train/serve skew: at serving time this is sigmoid(search-service's
    # actual relevance score); here, at training time, there is no real
    # search call behind these implicit-feedback pairs, so it's approximated
    # from the clickstream's own recorded "position" column (1/position, a
    # stand-in for "how relevant did the original simulated ranking think
    # this was"), sigmoid-squashed the same way. See TRAINING.md.
    base_score_proxy = sigmoid(1.0 / position_for_pair) if position_for_pair else sigmoid(1.0 / 20)

    popularity_log = math.log1p(aggregates["popularity"].get(product_id, 0.0))

    interacted = aggregates["interacted_by_user"].get(user_id, set())
    co_occurrence_total = sum(
        aggregates["co_occurrence"].get(product_id, {}).get(other, 0)
        for other in interacted if other != product_id
    )
    co_occurrence_log = math.log1p(co_occurrence_total)

    avg_rating_over_5 = product["avg_rating"] / 5.0
    rating_count_log = math.log1p(product["rating_count"])

    # FEATURE 7 (session_category_overlap) — same-session recency-weighted
    # category overlap (TODO.md item #11), distinct from feature 4's
    # all-time co_occurrence_log. "Recency-weighted" means "derived from
    # the session-scoped list," not an actual time-decay curve — see
    # NeuralRankingStrategy's Javadoc.
    candidate_category = top_level_category(product["category"])
    segments = recent_category_segments or []
    if candidate_category and segments:
        session_category_overlap = sum(1 for c in segments if c == candidate_category) / len(segments)
    else:
        session_category_overlap = 0.0

    return [category_match, base_score_proxy, popularity_log, co_occurrence_log, avg_rating_over_5,
            rating_count_log, session_category_overlap]


def sigmoid(x):
    return 1.0 / (1.0 + math.exp(-x))


def build_dataset(rows, products, aggregates, rng):
    # Positive pairs: max event weight per (user, product).
    pair_weight = defaultdict(float)
    for row in rows:
        key = (row["user_id"], row["product_id"])
        pair_weight[key] = max(pair_weight[key], EVENT_WEIGHT.get(row["event_type"], 0.0))

    # Hard negatives: sample only from products that actually appear
    # somewhere in the clickstream (nonzero popularity), not the full ~43K
    # catalog. An earlier version of this script sampled uniformly from the
    # whole catalog, which made the task almost trivially separable — ~97%
    # of WANDS products never appear in the synthetic clickstream at all
    # (sessions only draw from label.csv's judged candidates per query), so
    # a random negative was overwhelmingly "a product neither this user nor
    # any user ever saw," collapsing the task to "does this product have any
    # clickstream footprint" rather than genuine preference modeling — see
    # TRAINING.md for the before/after numbers this produced.
    candidate_pool = [pid for pid in aggregates["popularity"] if aggregates["popularity"][pid] > 0]
    users = sorted({u for (u, _p) in pair_weight.keys()})

    X, y, groups = [], [], []
    for user_id in users:
        user_positive_products = [p for (u, p) in pair_weight if u == user_id]
        user_sessions = sorted(aggregates["sessions_by_user"].get(user_id, set()))
        for product_id in user_positive_products:
            position = aggregates["best_position"].get((user_id, product_id))
            position = position if position and math.isfinite(position) else None
            # FEATURE 7 context: the real session(s) this pair actually
            # occurred in — deterministic choice of the lowest session_id
            # when a pair spans more than one, "other products in that same
            # session" (excluding the target itself, which isn't "recent" to
            # itself).
            pair_sessions = sorted(aggregates["sessions_by_user_product"].get((user_id, product_id), set()))
            recent_ids = set()
            if pair_sessions:
                recent_ids = aggregates["interacted_by_session"].get(pair_sessions[0], set()) - {product_id}
            recent_segments = session_category_segments(recent_ids, products)
            X.append(build_features(user_id, product_id, products, aggregates, position, recent_segments))
            y.append(pair_weight[(user_id, product_id)])
            groups.append(user_id)

        # Negative sampling: as many unseen products as positives, per user.
        seen = aggregates["interacted_by_user"].get(user_id, set()) | set(user_positive_products)
        negatives_needed = len(user_positive_products)
        attempts = 0
        sampled = 0
        while sampled < negatives_needed and attempts < negatives_needed * 20:
            attempts += 1
            candidate = rng.choice(candidate_pool)
            if candidate in seen:
                continue
            # Sample a position from the empirical distribution rather than
            # using a fixed sentinel — an earlier version always used the
            # same low constant for negatives, which (since real positions
            # are 1-15) made base_score_proxy alone a near-perfect trivial
            # separator between positives and negatives, not a meaningful
            # signal. See TRAINING.md.
            negative_position = rng.choice(aggregates["all_positions"])
            # FEATURE 7 context for a negative: this candidate never
            # actually appeared in any of this user's sessions, so there's
            # no real "the session it occurred in" to draw from. Sample one
            # of the user's actual sessions instead, simulating "if this
            # candidate had been shown during a real browsing session of
            # theirs" — same spirit as sampling negative_position from the
            # empirical position distribution just above, rather than a
            # fixed sentinel.
            recent_ids = aggregates["interacted_by_session"].get(rng.choice(user_sessions), set()) if user_sessions else set()
            recent_segments = session_category_segments(recent_ids, products)
            X.append(build_features(user_id, candidate, products, aggregates, negative_position, recent_segments))
            y.append(0.0)
            groups.append(user_id)
            sampled += 1

    return np.array(X, dtype=np.float32), np.array(y, dtype=np.float32), np.array(groups)


def pairwise_ranking_accuracy(y_true, y_pred, groups):
    """For each user, fraction of (positive, negative) pairs the model ranks
    correctly (positive scored above negative) — an AUC-style metric
    appropriate for graded implicit labels rather than a fixed threshold."""
    correct, total = 0, 0
    for user_id in set(groups):
        mask = groups == user_id
        true_u, pred_u = y_true[mask], y_pred[mask]
        pos_idx = np.where(true_u > 0)[0]
        neg_idx = np.where(true_u == 0)[0]
        for i in pos_idx:
            for j in neg_idx:
                total += 1
                if pred_u[i] > pred_u[j]:
                    correct += 1
                elif pred_u[i] == pred_u[j]:
                    correct += 0.5
    return correct / total if total else float("nan")


def sort_by_group(X, y, groups):
    """XGBoost's group-based ranking API requires rows contiguous per group
    (it takes group *sizes*, not group *labels*), unlike this script's other
    per-user loops which don't care about row order."""
    order = np.argsort(groups, kind="stable")
    X_sorted, y_sorted, groups_sorted = X[order], y[order], groups[order]
    # group sizes must be in the same order groups appear in groups_sorted —
    # np.unique(..., return_counts=True) sorts by value instead, which
    # silently pairs the wrong size with the wrong group, so sizes are
    # computed by run-length over groups_sorted directly instead.
    sizes = []
    current, count = None, 0
    for g in groups_sorted:
        if g != current:
            if current is not None:
                sizes.append(count)
            current, count = g, 1
        else:
            count += 1
    sizes.append(count)
    return X_sorted, y_sorted, groups_sorted, sizes


# rank:ndcg requires integer relevance grades (XGBoost rejects continuous
# labels for its NDCG-based objective) — quantizing the 5 distinct label
# values already in use (0.0/0.2/0.5/0.8/1.0 for none/view/click/cart/
# purchase) to integer grades 0-4 preserves the exact same relative
# ordering, so nothing about the labeling *scheme* changes, only its
# representation for this one training call.
LABEL_TO_GRADE = {0.0: 0, 0.2: 1, 0.5: 2, 0.8: 3, 1.0: 4}


def to_integer_relevance(y):
    return np.array([LABEL_TO_GRADE[round(float(v), 1)] for v in y], dtype=np.int32)


def train_pairwise_ranker(X_train, y_train, groups_train, seed):
    """The model actually exported and served — XGBoost with rank:ndcg
    (LambdaMART: pairwise gradients weighted by the |ΔNDCG| swapping each
    pair would cause), matching Airbnb's Applying Deep Learning to Airbnb
    Search (KDD 2019, arXiv:1810.09591) rather than pointwise regression."""
    X_sorted, y_sorted, _, group_sizes = sort_by_group(X_train, y_train, groups_train)
    model = xgb.XGBRanker(
        objective="rank:ndcg",
        n_estimators=100,
        max_depth=4,
        learning_rate=0.1,
        random_state=seed,
    )
    model.fit(X_sorted, to_integer_relevance(y_sorted), group=group_sizes)
    return model


def train_pointwise_mlp(X_train, y_train, seed):
    """The OLD model this script used to train and export, kept here only
    as an honest comparison baseline against the new pairwise objective —
    no longer exported or served."""
    from sklearn.neural_network import MLPRegressor
    model = MLPRegressor(hidden_layer_sizes=(16, 8), activation="relu", max_iter=500, random_state=seed)
    model.fit(X_train, y_train)
    return model


def train_linear_baseline(X_train, y_train, seed):
    """The missing baseline: a linear combination of all 7 features, not
    just any ONE feature alone. Beating every single-feature baseline (as
    both models above already do) is a low bar; beating this is the real
    test of whether nonlinearity/tree-splits are earning their complexity."""
    model = LogisticRegression(max_iter=1000, random_state=seed)
    model.fit(X_train, (y_train > 0).astype(int))
    return model


def rename_onnx_output(onnx_model, new_name):
    old_name = onnx_model.graph.output[0].name
    onnx_model.graph.output[0].name = new_name
    for node in onnx_model.graph.node:
        for i, out in enumerate(node.output):
            if out == old_name:
                node.output[i] = new_name


def export_xgb_ranker_to_onnx(model):
    """onnxmltools has no direct XGBRanker converter (only XGBClassifier/
    XGBRegressor/XGBRFClassifier/XGBRFRegressor are registered) — but a
    ranker's underlying tree ensemble predicts identically to a regressor at
    inference time (sum of leaf values); only the *training* gradient
    differs. WrappedBooster's own num_class-sniffing heuristic misfires for
    ranking objectives (misdetects num_class=1 and picks the classifier
    path, producing a "label"/"probabilities" output pair rather than a raw
    score), so operator_name is forced to XGBRegressor explicitly here —
    verified this produces bit-identical output to the sklearn API's
    .predict() before relying on it."""
    wrapped = WrappedBooster(model.get_booster())
    wrapped.operator_name = "XGBRegressor"
    onx = convert_xgboost(wrapped, initial_types=[("features", MTFloatTensorType([None, len(FEATURE_NAMES)]))])
    rename_onnx_output(onx, "score")
    return onx


def run_one_seed(rows, products, aggregates, seed, train_pointwise_too=False):
    """Runs the full pair-construction + train + held-out-eval pipeline for
    one seed. Returns a dict of pairwise-accuracy numbers. Data loading and
    aggregation (the expensive, seed-independent parts) are done once by the
    caller and passed in — only negative sampling and the train/holdout user
    split vary per seed."""
    rng = random.Random(seed)
    np.random.seed(seed)

    X, y, groups = build_dataset(rows, products, aggregates, rng)

    unique_users = sorted(set(groups))
    rng.shuffle(unique_users)
    split = int(len(unique_users) * 0.8)
    train_users, holdout_users = set(unique_users[:split]), set(unique_users[split:])
    train_mask = np.array([g in train_users for g in groups])
    holdout_mask = np.array([g in holdout_users for g in groups])

    results = {}

    pairwise_model = train_pairwise_ranker(X[train_mask], y[train_mask], groups[train_mask], seed)
    pred_pairwise = pairwise_model.predict(X[holdout_mask])
    results["pairwise_xgb_ndcg"] = pairwise_ranking_accuracy(y[holdout_mask], pred_pairwise, groups[holdout_mask])

    linear_model = train_linear_baseline(X[train_mask], y[train_mask], seed)
    pred_linear = linear_model.decision_function(X[holdout_mask])
    results["linear_all_7_features"] = pairwise_ranking_accuracy(y[holdout_mask], pred_linear, groups[holdout_mask])

    popularity_only = X[holdout_mask][:, 2]
    results["popularity_only"] = pairwise_ranking_accuracy(y[holdout_mask], popularity_only, groups[holdout_mask])
    category_only = X[holdout_mask][:, 0]
    results["category_only"] = pairwise_ranking_accuracy(y[holdout_mask], category_only, groups[holdout_mask])
    base_score_only = X[holdout_mask][:, 1]
    results["base_score_only"] = pairwise_ranking_accuracy(y[holdout_mask], base_score_only, groups[holdout_mask])
    results["random"] = 0.5

    if train_pointwise_too:
        pointwise_model = train_pointwise_mlp(X[train_mask], y[train_mask], seed)
        pred_pointwise = pointwise_model.predict(X[holdout_mask])
        results["pointwise_mlp_OLD"] = pairwise_ranking_accuracy(y[holdout_mask], pred_pointwise, groups[holdout_mask])

    results["_pairwise_model"] = pairwise_model
    results["_sample_row"] = X[:1]
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, default=42, help="Seed for the exported model")
    parser.add_argument("--ci-seeds", type=int, default=0,
                         help="If >0, also run this many additional seeds (seed, seed+1, ...) "
                              "and report a mean+/-std confidence interval across all of them")
    args = parser.parse_args()

    print("Loading data...")
    products = load_products()
    rows = load_clickstream()
    aggregates = build_aggregates(rows, products)

    seeds = [args.seed] + [args.seed + i for i in range(1, args.ci_seeds)] if args.ci_seeds > 0 else [args.seed]
    all_results = []
    primary_result = None

    for seed in seeds:
        print(f"\n=== seed {seed} ===")
        result = run_one_seed(rows, products, aggregates, seed, train_pointwise_too=True)
        all_results.append(result)
        if seed == args.seed:
            primary_result = result
        for key in ["pairwise_xgb_ndcg", "pointwise_mlp_OLD", "linear_all_7_features",
                    "popularity_only", "category_only", "base_score_only"]:
            print(f"  {key}: {result[key]:.4f}")

    if len(seeds) > 1:
        print(f"\n=== summary across {len(seeds)} seeds ({seeds}) ===")
        for key in ["pairwise_xgb_ndcg", "pointwise_mlp_OLD", "linear_all_7_features",
                    "popularity_only", "category_only", "base_score_only"]:
            values = [r[key] for r in all_results]
            mean = statistics.mean(values)
            std = statistics.stdev(values) if len(values) > 1 else 0.0
            print(f"  {key}: {mean:.4f} +/- {std:.4f}  (n={len(values)}, values={[round(v, 4) for v in values]})")

    # Export the primary seed's pairwise model — the one actually served.
    MODEL_OUT.parent.mkdir(parents=True, exist_ok=True)
    onx = export_xgb_ranker_to_onnx(primary_result["_pairwise_model"])
    MODEL_OUT.write_bytes(onx.SerializeToString())
    print(f"\nWrote ONNX model (pairwise XGBoost rank:ndcg, seed {args.seed}) -> {MODEL_OUT}")

    # Sanity-check the exported model loads and runs, matching what
    # OnnxRankingModelAdapter (Java) will do at serving time.
    import onnxruntime
    session = onnxruntime.InferenceSession(str(MODEL_OUT))
    sample = primary_result["_sample_row"]
    result = session.run(["score"], {"features": sample})
    sklearn_pred = primary_result["_pairwise_model"].predict(sample)[0]
    print(f"ONNX sanity check: xgboost predict={sklearn_pred:.4f}, onnxruntime output={result[0][0][0]:.4f}")


if __name__ == "__main__":
    main()
