#!/usr/bin/env python3
"""Trains the neural ranking model used by NeuralRankingStrategy (recommender-service).

Builds implicit-feedback (user, product) pairs from data/clickstream.csv,
computes the 6 features documented in NeuralRankingStrategy's Javadoc (and
duplicated below — the two MUST stay in sync, since this script's feature
order is exactly what the exported ONNX model expects at serving time),
trains a small MLP regressor, and exports it to ONNX at
models/neural-ranker/model.onnx.

See TRAINING.md for the full methodology, the actual held-out numbers this
run produced, and an honest account of where the training-time features are
a proxy for what's available at serving time (they aren't identical — see
FEATURE 1 below).

Usage: python3 train_neural_ranker.py [--seed 42]
"""
import argparse
import csv
import math
import random
from collections import defaultdict
from pathlib import Path

import numpy as np
from sklearn.neural_network import MLPRegressor
from skl2onnx import to_onnx
from skl2onnx.common.data_types import FloatTensorType

ROOT_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT_DIR / "data"
MODEL_OUT = ROOT_DIR / "models" / "neural-ranker" / "model.onnx"

EVENT_WEIGHT = {"view": 0.2, "click": 0.5, "add_to_cart": 0.8, "purchase": 1.0}
INTERACTION_EVENTS = {"click", "add_to_cart", "purchase"}
FEATURE_NAMES = [
    "category_match", "base_score_proxy", "popularity_log",
    "co_occurrence_log", "avg_rating_over_5", "rating_count_log",
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
    }


def build_features(user_id, product_id, products, aggregates, position_for_pair):
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

    return [category_match, base_score_proxy, popularity_log, co_occurrence_log, avg_rating_over_5, rating_count_log]


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
        for product_id in user_positive_products:
            position = aggregates["best_position"].get((user_id, product_id))
            position = position if position and math.isfinite(position) else None
            X.append(build_features(user_id, product_id, products, aggregates, position))
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
            X.append(build_features(user_id, candidate, products, aggregates, negative_position))
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


def rename_onnx_output(onnx_model, new_name):
    old_name = onnx_model.graph.output[0].name
    onnx_model.graph.output[0].name = new_name
    for node in onnx_model.graph.node:
        for i, out in enumerate(node.output):
            if out == old_name:
                node.output[i] = new_name


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    np.random.seed(args.seed)

    print("Loading data...")
    products = load_products()
    rows = load_clickstream()
    aggregates = build_aggregates(rows, products)

    print("Building labeled (user, product) pairs with negative sampling...")
    X, y, groups = build_dataset(rows, products, aggregates, rng)
    print(f"Dataset: {len(X)} pairs ({int((y > 0).sum())} positive, {int((y == 0).sum())} negative), "
          f"{len(set(groups))} users")

    unique_users = sorted(set(groups))
    rng.shuffle(unique_users)
    split = int(len(unique_users) * 0.8)
    train_users, holdout_users = set(unique_users[:split]), set(unique_users[split:])
    train_mask = np.array([g in train_users for g in groups])
    holdout_mask = np.array([g in holdout_users for g in groups])

    print(f"Held-out split: {len(train_users)} train users, {len(holdout_users)} held-out users")

    model = MLPRegressor(
        hidden_layer_sizes=(16, 8),
        activation="relu",
        max_iter=500,
        random_state=args.seed,
    )
    model.fit(X[train_mask], y[train_mask])

    pred_holdout = model.predict(X[holdout_mask])
    neural_acc = pairwise_ranking_accuracy(y[holdout_mask], pred_holdout, groups[holdout_mask])

    # Baselines for honest comparison, same held-out pairs.
    popularity_only = X[holdout_mask][:, 2]  # feature index 2 = popularity_log
    popularity_acc = pairwise_ranking_accuracy(y[holdout_mask], popularity_only, groups[holdout_mask])
    category_only = X[holdout_mask][:, 0]  # feature index 0 = category_match
    category_acc = pairwise_ranking_accuracy(y[holdout_mask], category_only, groups[holdout_mask])
    base_score_only = X[holdout_mask][:, 1]  # feature index 1 = base_score_proxy
    base_score_acc = pairwise_ranking_accuracy(y[holdout_mask], base_score_only, groups[holdout_mask])
    random_acc = 0.5

    print(f"\nHeld-out pairwise ranking accuracy (fraction of positive>negative pairs correctly ordered):")
    print(f"  Neural ranker (MLP, this model):  {neural_acc:.4f}")
    print(f"  base_score_proxy-only baseline:   {base_score_acc:.4f}")
    print(f"  Category-match-only baseline:     {category_acc:.4f}")
    print(f"  Popularity-only baseline:         {popularity_acc:.4f}")
    print(f"  Random baseline:                  {random_acc:.4f}")

    MODEL_OUT.parent.mkdir(parents=True, exist_ok=True)
    onx = to_onnx(model, X[:1], initial_types=[("features", FloatTensorType([None, 6]))])
    rename_onnx_output(onx, "score")
    MODEL_OUT.write_bytes(onx.SerializeToString())
    print(f"\nWrote ONNX model -> {MODEL_OUT}")

    # Sanity-check the exported model loads and runs, matching what
    # OnnxRankingModelAdapter (Java) will do at serving time.
    import onnxruntime
    session = onnxruntime.InferenceSession(str(MODEL_OUT))
    sample = X[:1]
    result = session.run(["score"], {"features": sample})
    print(f"ONNX sanity check: sklearn predict={model.predict(sample)[0]:.4f}, "
          f"onnxruntime output={result[0][0][0]:.4f}")


if __name__ == "__main__":
    main()
