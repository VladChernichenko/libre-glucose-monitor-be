"""Dry-run test: python3 -I scripts/test_clinical_ingest.py  (needs pyyaml; no network, no DB)."""
import subprocess
import sys
import unittest
from pathlib import Path

try:
    import yaml  # noqa: F401
    HAVE_YAML = True
except ImportError:
    HAVE_YAML = False

sys.path.insert(0, str(Path(__file__).resolve().parent))
import clinical_ingest as ci  # noqa: E402

HERE = Path(__file__).resolve().parent


@unittest.skipUnless(HAVE_YAML, 'pyyaml not importable in this interpreter (pip install pyyaml)')
class DryRunTest(unittest.TestCase):
    def test_dry_run_chunk_counts(self):
        out = subprocess.run(
            [sys.executable, str(HERE / "clinical_ingest.py"), "--dry-run",
             "--sources", str(HERE / "fixtures" / "clinical_sources_fixture.yaml")],
            capture_output=True, text=True, check=True).stdout
        self.assertIn("fixture-guideline: 2 chunks", out)
        self.assertIn("pmid-1: 1 chunks", out)
        self.assertIn("SKIP fixture-summary-only", out)
        self.assertIn("TOTAL chunks: 3 from 2 documents (dry-run)", out)


class PlanTest(unittest.TestCase):
    def test_unchanged_chunk_is_upserted_but_not_embedded(self):
        p = ci.plan_document(["a", "b"], {0: ("a", True), 1: ("old", True)}, False)
        self.assertEqual(p["upsert"], [0, 1])
        self.assertEqual(p["embed"], [1])

    def test_missing_embedding_and_reembed(self):
        self.assertEqual(ci.plan_document(["a"], {0: ("a", False)}, False)["embed"], [0])
        self.assertEqual(ci.plan_document(["a"], {0: ("a", True)}, True)["embed"], [0])

    def test_trailing_chunks_deactivate(self):
        self.assertEqual(ci.plan_document(["a"], {0: ("a", True), 1: ("b", True)}, False)["deactivate_from"], 1)

    def test_stale_ignores_null_and_processed(self):
        self.assertEqual(ci.plan_stale(["x", "y", None, "z"], ["x"]), ["y", "z"])


if __name__ == "__main__":
    unittest.main()
