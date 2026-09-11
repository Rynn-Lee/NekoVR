from __future__ import annotations

import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


VALIDATE_READY = Path(__file__).resolve().parents[2] / "validate_ready.py"
SPEC = importlib.util.spec_from_file_location("nekovr_validate_ready", VALIDATE_READY)
assert SPEC is not None and SPEC.loader is not None
validate_ready = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validate_ready)


class DirectDatasetEvidenceTests(unittest.TestCase):
    def test_each_required_category_has_a_distinct_gradle_check(self) -> None:
        checks = validate_ready.DIRECT_SERVER_CHECKS
        self.assertEqual(set(validate_ready.REQUIRED_SERVER_EVIDENCE), set(checks))
        tasks = [task for task, _ in checks.values()]
        self.assertEqual(len(tasks), len(set(tasks)))
        self.assertNotIn(":server:core:test", tasks)

    def test_direct_check_writes_a_machine_readable_result(self) -> None:
        root = VALIDATE_READY.parents[1]
        workspace_tmp = VALIDATE_READY.parents[1] / "build"
        workspace_tmp.mkdir(parents=True, exist_ok=True)
        artifact = workspace_tmp / "schema.json"
        try:
            with patch.object(
                validate_ready.subprocess,
                "run",
                return_value=subprocess.CompletedProcess([], 0),
            ) as run:
                passed, command, artifact = validate_ready.run_direct_server_check(
                    "schema",
                    ":server:core:datasetReadySchemaEvidence",
                    ("dev.slimevr.unit.DatasetArchiveValidatorTests",),
                    "gradlew",
                    root,
                    workspace_tmp,
                )

            self.assertTrue(passed)
            self.assertIn("datasetReadySchemaEvidence", command)
            result = json.loads(artifact.read_text(encoding="utf-8"))
            self.assertEqual("nekovr-dataset-direct-evidence-v1", result["format"])
            self.assertEqual("schema", result["id"])
            self.assertTrue(result["passed"])
            self.assertEqual(0, result["exitCode"])
            self.assertEqual(
                ["dev.slimevr.unit.DatasetArchiveValidatorTests"],
                result["testPatterns"],
            )
            run.assert_called_once_with(
                ["gradlew", ":server:core:datasetReadySchemaEvidence"],
                cwd=root,
                text=True,
            )
        finally:
            artifact.unlink(missing_ok=True)

    def test_command_artifact_and_report_contract_are_hash_bound(self) -> None:
        root = VALIDATE_READY.parents[1]
        artifact_root = root / "build" / "dataset-ready-test"
        command = "gradlew :server:core:datasetReadySchemaEvidence"
        artifact = validate_ready.write_command_artifact(
            "schema", [command], True, artifact_root
        )
        try:
            value = json.loads(artifact.read_text(encoding="utf-8"))
            self.assertEqual("nekovr-dataset-direct-evidence-v2", value["format"])
            self.assertEqual(
                validate_ready.command_hash(command), value["commandSha256"][0]
            )
            self.assertEqual(
                hashlib.sha256(artifact.read_bytes()).hexdigest(),
                validate_ready.sha256_file(artifact),
            )
            self.assertEqual(2, validate_ready.REPORT_SCHEMA_VERSION)
            self.assertEqual("nekovr-dataset-ready", validate_ready.VERIFIER_ID)
        finally:
            artifact.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
