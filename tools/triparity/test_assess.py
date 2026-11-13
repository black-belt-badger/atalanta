#!/usr/bin/env python3
"""Deterministic tests of the triparity smoke verdict parser; no Java/Rust/Haskell needed."""
import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

from assess import assess, inventory

CASES = ["account.full", "assets.default", "orders.default"]


class TriparityAssessorTest(unittest.TestCase):
    def evaluate(self, markers, status, known):
        with tempfile.TemporaryDirectory() as temp:
            log = Path(temp) / "test.log"
            log.write_text("\n".join(markers) + "\n")
            with redirect_stdout(io.StringIO()):
                return assess("Test", log, status, CASES, known)

    def test_known_mismatch_is_a_successful_audit_baseline(self):
        self.assertTrue(self.evaluate([
            "PORTABLE-SMOKE PASS account.full",
            "PORTABLE-SMOKE MISMATCH assets.default",
            "PORTABLE-SMOKE PASS orders.default",
        ], 1, {"assets.default": "known incompatibility"}))

    def test_fixed_known_mismatch_is_an_improvement(self):
        self.assertTrue(self.evaluate([
            "PORTABLE-SMOKE PASS account.full",
            "PORTABLE-SMOKE PASS assets.default",
            "PORTABLE-SMOKE PASS orders.default",
        ], 0, {"assets.default": "known incompatibility"}))

    def test_new_mismatch_is_never_silently_accepted(self):
        self.assertFalse(self.evaluate([
            "PORTABLE-SMOKE MISMATCH account.full",
            "PORTABLE-SMOKE PASS assets.default",
            "PORTABLE-SMOKE PASS orders.default",
        ], 1, {"assets.default": "known incompatibility"}))

    def test_missing_or_duplicated_case_marker_is_a_failure(self):
        self.assertFalse(self.evaluate([
            "PORTABLE-SMOKE PASS account.full",
            "PORTABLE-SMOKE PASS assets.default",
        ], 0, {}))
        self.assertFalse(self.evaluate([
            "PORTABLE-SMOKE PASS account.full",
            "PORTABLE-SMOKE PASS account.full",
            "PORTABLE-SMOKE PASS assets.default",
            "PORTABLE-SMOKE PASS orders.default",
        ], 0, {}))

    def test_failed_build_without_mismatch_markers_is_a_failure(self):
        self.assertFalse(self.evaluate([
            "PORTABLE-SMOKE PASS account.full",
            "PORTABLE-SMOKE PASS assets.default",
            "PORTABLE-SMOKE PASS orders.default",
        ], 1, {}))

    def test_canonical_fixture_inventory_is_exactly_26(self):
        root = Path(__file__).resolve().parents[2]
        ids = inventory(root / "contracts/alpaca-trading/v1/manifest.json")
        self.assertEqual(len(ids), 26)
        self.assertIn("order_by_id.mismatched_id", ids)


if __name__ == "__main__":
    unittest.main()
