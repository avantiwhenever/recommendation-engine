#!/usr/bin/env python3
"""Golden-vector feature-parity test (Python side).

Loads feature_parity_fixtures.csv — the SAME file
FeatureParityTest.java reads — and asserts train_neural_ranker.py's
build_features() produces the expected values for the features that are
supposed to be train/serve-identical (category_match, popularity_log,
co_occurrence_log, avg_rating_over_5, rating_count_log,
session_category_overlap). Feature 1
(base_score_proxy) is deliberately NOT cross-checked against Java's
serve-time formula here — they're different formulas by design (documented
train/serve skew, see NeuralRankingStrategy's Javadoc and TRAINING.md) — but
this test does assert it's a finite, valid sigmoid output.

This exists because NeuralRankingStrategy.buildFeatures() (Java) and this
script's build_features() are two independently-maintained implementations
of the same feature formulas, previously kept in sync only by a hand-written
comment table — a gap that already caused one real train/serve skew bug (see
TRAINING.md). A future edit that silently breaks parity on either side now
fails both `python3 test_feature_parity.py` and the Java-side
FeatureParityTest, from one shared fixture file, instead of relying on
someone re-reading the comment table.

Usage: python3 test_feature_parity.py
"""
import csv
import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from train_neural_ranker import build_features, sigmoid  # noqa: E402

FIXTURES_PATH = Path(__file__).resolve().parent / "feature_parity_fixtures.csv"
TOLERANCE = 1e-9


def load_fixtures():
    with open(FIXTURES_PATH, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


class FeatureParityTest(unittest.TestCase):
    def test_shared_features_match_fixture(self):
        fixtures = load_fixtures()
        self.assertGreater(len(fixtures), 0, "fixture file loaded 0 rows")

        for row in fixtures:
            with self.subTest(case=row["name"]):
                user_id, product_id = "fixture-user", "fixture-product"
                products = {
                    product_id: {
                        "category": row["category_hierarchy"] or None,
                        "avg_rating": float(row["avg_rating"]),
                        "rating_count": int(row["rating_count"]),
                    }
                }

                user_top_category = row["user_top_category"] or None
                category_counts_by_user = {}
                if user_top_category:
                    category_counts_by_user[user_id] = {user_top_category: 1}

                popularity_raw = float(row["popularity_raw"])
                co_occurrence_raw = int(row["co_occurrence_raw"])
                other_product = "fixture-co-occurring-product"
                aggregates = {
                    "popularity": {product_id: popularity_raw},
                    "interacted_by_user": {user_id: {other_product}} if co_occurrence_raw else {user_id: set()},
                    "category_counts_by_user": category_counts_by_user,
                    "co_occurrence": {product_id: {other_product: co_occurrence_raw}},
                }

                train_time_position = int(row["train_time_position"])
                recent_categories_raw = row["recent_categories"]
                recent_category_segments = recent_categories_raw.split(";") if recent_categories_raw else []
                features = build_features(user_id, product_id, products, aggregates, train_time_position,
                                           recent_category_segments)

                self.assertAlmostEqual(features[0], float(row["expected_category_match"]), delta=TOLERANCE,
                                        msg="category_match")
                self.assertAlmostEqual(features[2], float(row["expected_popularity_log"]), delta=TOLERANCE,
                                        msg="popularity_log")
                self.assertAlmostEqual(features[3], float(row["expected_co_occurrence_log"]), delta=TOLERANCE,
                                        msg="co_occurrence_log")
                self.assertAlmostEqual(features[4], float(row["expected_avg_rating_over_5"]), delta=TOLERANCE,
                                        msg="avg_rating_over_5")
                self.assertAlmostEqual(features[5], float(row["expected_rating_count_log"]), delta=TOLERANCE,
                                        msg="rating_count_log")

                # Feature 1 (base_score_proxy): not cross-checked against
                # Java's serve-time formula (different by design), but must
                # still be a valid, finite sigmoid output of the documented
                # train-time formula.
                expected_train_feature1 = sigmoid(1.0 / train_time_position)
                self.assertAlmostEqual(features[1], expected_train_feature1, delta=TOLERANCE,
                                        msg="base_score_proxy (train-time formula)")
                self.assertTrue(0.0 < features[1] < 1.0, "base_score_proxy must be a valid sigmoid output")

                self.assertAlmostEqual(features[6], float(row["expected_session_category_overlap"]), delta=TOLERANCE,
                                        msg="session_category_overlap")

                self.assertEqual(len(features), 7)


if __name__ == "__main__":
    unittest.main()
