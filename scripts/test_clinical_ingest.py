"""Dry-run test: python3 -I scripts/test_clinical_ingest.py  (needs pyyaml; no network, no DB)."""
import subprocess
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


class DryRunTest(unittest.TestCase):
    def test_dry_run_chunk_counts(self):
        out = subprocess.run(
            [sys.executable, "-I", str(HERE / "clinical_ingest.py"), "--dry-run",
             "--sources", str(HERE / "fixtures" / "clinical_sources_fixture.yaml")],
            capture_output=True, text=True, check=True).stdout
        self.assertIn("fixture-guideline: 2 chunks", out)
        self.assertIn("pmid-1: 1 chunks", out)
        self.assertIn("SKIP fixture-summary-only", out)
        self.assertIn("TOTAL chunks: 3 from 2 documents (dry-run)", out)


if __name__ == "__main__":
    unittest.main()
