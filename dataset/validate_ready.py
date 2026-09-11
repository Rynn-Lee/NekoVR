#!/usr/bin/env python3
"""Run the complete dataset-ready gate and persist one machine-readable report."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


REQUIRED_SERVER_EVIDENCE = (
    "schema",
    "numerical",
    "metadata",
    "reset-label",
    "crash-recovery",
    "memory-soak",
)

DIRECT_SERVER_CHECKS = {
    "schema": (
        ":server:core:datasetReadySchemaEvidence",
        (
            "dev.slimevr.unit.DatasetArchiveValidatorTests",
            "dev.slimevr.unit.DatasetConformanceFixtureTests",
            "dev.slimevr.unit.DatasetTelemetryChecksumTests",
        ),
    ),
    "numerical": (
        ":server:core:datasetReadyNumericalEvidence",
        ("dev.slimevr.unit.FP16BinaryPackerTests",),
    ),
    "metadata": (
        ":server:core:datasetReadyMetadataEvidence",
        (
            "dev.slimevr.unit.DatasetRecordingServiceTests.testMixedWiFiAndHIDnRFOriginTrackers",
            "dev.slimevr.unit.DatasetReplayTests",
        ),
    ),
    "reset-label": (
        ":server:core:datasetReadyResetLabelEvidence",
        (
            "dev.slimevr.unit.ResetLabelBindingsTests",
            "dev.slimevr.unit.ResetSupervisionTests",
        ),
    ),
    "crash-recovery": (
        ":server:core:datasetReadyCrashRecoveryEvidence",
        (
            "dev.slimevr.unit.DatasetRecordingServiceTests.testForcedCrashAndStartupRecovery",
            "dev.slimevr.unit.DatasetRecordingServiceTests.testRecoveryPreservesDurableManifestAndProducesValidArchive",
        ),
    ),
    "memory-soak": (
        ":server:core:datasetReadyMemorySoakEvidence",
        (
            "dev.slimevr.unit.DatasetRecordingServiceTests.testLongSoakRecordingMemoryAndWatermark",
        ),
    ),
}

REPORT_SCHEMA_VERSION = 2
VERIFIER_ID = "nekovr-dataset-ready"
VERIFIER_VERSION = "2"
REPORT_LIFETIME = timedelta(hours=24)


def run(command: list[str], cwd: Path, env: dict[str, str] | None = None) -> tuple[bool, str]:
    completed = subprocess.run(command, cwd=cwd, env=env, text=True)
    return completed.returncode == 0, " ".join(command)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def command_hash(command: str) -> str:
    return sha256_bytes(command.encode("utf-8"))


def git_build_identity(root: Path) -> tuple[str, bool]:
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=root, text=True, capture_output=True
    )
    if completed.returncode != 0:
        return "UNKNOWN", True
    dirty = subprocess.run(
        ["git", "status", "--porcelain"], cwd=root, text=True, capture_output=True
    )
    return completed.stdout.strip(), dirty.returncode != 0 or bool(dirty.stdout.strip())


def normalized_windows(report: dict) -> list[dict]:
    keys = (
        "eventIndex",
        "sessionTrackerId",
        "preStartFrame",
        "preEndFrame",
        "postStartFrame",
        "postEndFrame",
        "qualityFlags",
        "trainingPolicy",
    )
    return [{key: window.get(key) for key in keys} for window in report.get("validResetWindows", [])]


def compare_reports(server: dict, python: dict) -> bool:
    return (
        server.get("frames") == python.get("frames")
        and server.get("resetLabels") == python.get("resetLabels")
        and server.get("rosterSize") == python.get("rosterSize")
        and sorted(server.get("channelIds", [])) == sorted(python.get("channelIds", []))
        and server.get("quality") == python.get("quality")
        and sorted(server.get("transports", [])) == sorted(python.get("transports", []))
        and normalized_windows(server) == normalized_windows(python)
    )


def write_atomic(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as output:
        json.dump(value, output, indent=2)
        output.write("\n")
        temporary = Path(output.name)
    os.replace(temporary, path)


def run_direct_server_check(
    evidence_id: str,
    gradle_task: str,
    test_patterns: tuple[str, ...],
    gradle: str,
    root: Path,
    artifact_root: Path,
) -> tuple[bool, str, Path]:
    command = [gradle, gradle_task]
    completed = subprocess.run(command, cwd=root, text=True)
    artifact = artifact_root / f"{evidence_id}.json"
    junit_xml = (
        root
        / "server"
        / "core"
        / "build"
        / "test-results"
        / "dataset-ready"
        / evidence_id.replace("-", "").lower()
    )
    write_atomic(
        artifact,
        {
            "format": "nekovr-dataset-direct-evidence-v1",
            "id": evidence_id,
            "generatedUtc": datetime.now(timezone.utc)
            .isoformat()
            .replace("+00:00", "Z"),
            "command": command,
            "testPatterns": list(test_patterns),
            "junitXml": str(junit_xml.relative_to(root)),
            "passed": completed.returncode == 0,
            "exitCode": completed.returncode,
        },
    )
    return completed.returncode == 0, " ".join(command), artifact


def write_command_artifact(
    evidence_id: str,
    commands: list[str],
    passed: bool,
    artifact_root: Path,
) -> Path:
    artifact = artifact_root / f"{evidence_id}.json"
    write_atomic(
        artifact,
        {
            "format": "nekovr-dataset-direct-evidence-v2",
            "id": evidence_id,
            "generatedUtc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "commands": commands,
            "commandSha256": [command_hash(command) for command in commands],
            "passed": passed,
        },
    )
    return artifact


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run schema/numerical/recorder/RPC/UI checks and validate pilot archives"
    )
    parser.add_argument("--simulated-pilot", action="append", default=[], type=Path)
    parser.add_argument("--real-pilot", action="append", default=[], type=Path)
    parser.add_argument(
        "--report", type=Path, default=Path("dataset/dataset-ready-report.json")
    )
    parser.add_argument("--skip-checks", action="store_true", help="pilot-only diagnostic mode")
    parser.add_argument(
        "--allow-dirty-development",
        action="store_true",
        help="label a dirty-tree report as development-only; production runtime still rejects it",
    )
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    report_path = (root / args.report).resolve() if not args.report.is_absolute() else args.report
    pilots = [
        *(("SIMULATED", path.resolve()) for path in args.simulated_pilot),
        *(("REAL", path.resolve()) for path in args.real_pilot),
    ]
    check_results: dict[str, tuple[bool, str, list[str]]] = {}
    evidence_artifacts: dict[str, Path] = {}
    gradle = str(root / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    pnpm = "pnpm.cmd" if os.name == "nt" else "pnpm"
    direct_evidence_root = (
        root / "server" / "core" / "build" / "reports" / "dataset-ready" / "direct"
    )

    if args.skip_checks:
        for evidence_id in (*REQUIRED_SERVER_EVIDENCE, "rpc-ui", "baseline"):
            check_results[evidence_id] = (
                False,
                "skipped; diagnostic reports never pass the gate",
                ["SKIPPED"],
            )
    else:
        for evidence_id, (gradle_task, test_patterns) in DIRECT_SERVER_CHECKS.items():
            passed, command, artifact = run_direct_server_check(
                evidence_id,
                gradle_task,
                test_patterns,
                gradle,
                root,
                direct_evidence_root,
            )
            check_results[evidence_id] = (
                passed,
                f"{command}; result={artifact.relative_to(root)}",
                [command],
            )
            evidence_artifacts[evidence_id] = artifact
        rpc_ok, rpc_command = run(
            [
                gradle,
                ":server:core:test",
                "--tests",
                "dev.slimevr.unit.DatasetRPCHandlerTests",
            ],
            root,
        )
        gui_test_ok, gui_test_command = run([pnpm, "-C", "gui", "test"], root)
        rpc_ui_commands = [rpc_command, gui_test_command]
        check_results["rpc-ui"] = (
            rpc_ok and gui_test_ok,
            f"{rpc_command}; {gui_test_command}",
            rpc_ui_commands,
        )
        baseline_ok, baseline_command = run(["node", "scripts/foundation-baseline.mjs"], root)
        check_results["baseline"] = (baseline_ok, baseline_command, [baseline_command])
        for evidence_id in ("rpc-ui", "baseline"):
            passed, _, commands = check_results[evidence_id]
            evidence_artifacts[evidence_id] = write_command_artifact(
                evidence_id, commands, passed, direct_evidence_root
            )

    pilot_evidence_root = direct_evidence_root.parent / "pilots"
    server_report_path = pilot_evidence_root / "server-pilots.json"
    server_reports: list[dict] = []
    server_command_ok = False
    if pilots:
        server_command_ok, _ = run(
            [
                gradle,
                ":server:core:datasetArchiveReport",
                f"-PdatasetArchives={os.pathsep.join(str(path) for _, path in pilots)}",
                f"-PdatasetArchiveReport={server_report_path}",
            ],
            root,
        )
        if server_command_ok and server_report_path.exists():
            server_reports = json.loads(server_report_path.read_text(encoding="utf-8")).get("reports", [])

    python_root = root / "dataset" / "python"
    sys.path.insert(0, str(python_root))
    from nekovr_dataset import DatasetFormatError, DatasetReader

    pilot_evidence = []
    for index, (source, archive) in enumerate(pilots):
        server_report = server_reports[index] if index < len(server_reports) else {}
        try:
            python_report = DatasetReader().inspect_archive(archive)
            python_valid = bool(python_report.get("valid"))
            python_fatals = list(python_report.get("fatalFindings", []))
        except (OSError, DatasetFormatError, ValueError) as error:
            python_report = {}
            python_valid = False
            python_fatals = [str(error)]
        server_fatals = [
            finding.get("message", finding.get("code", "fatal finding"))
            for finding in server_report.get("findings", [])
            if finding.get("severity") == "FATAL"
        ]
        statistics_match = bool(server_report) and python_valid and compare_reports(server_report, python_report)
        provenance_valid = source != "REAL" or python_report.get("applicationCommit") != "SIMULATED"
        if not provenance_valid:
            python_fatals.append("REAL pilot declares SIMULATED provenance")
        python_report_path = pilot_evidence_root / f"python-pilot-{index}.json"
        write_atomic(
            python_report_path,
            {
                "format": "nekovr-dataset-python-pilot-evidence-v1",
                "source": source,
                "archive": str(archive),
                "report": python_report,
                "fatalFindings": python_fatals,
            },
        )
        pilot_evidence.append(
            {
                "source": source,
                "archive": str(archive),
                "archiveSha256": sha256_file(archive) if archive.is_file() else "",
                "serverResultArtifact": str(server_report_path.resolve()),
                "serverResultSha256": sha256_file(server_report_path) if server_report_path.is_file() else "",
                "pythonResultArtifact": str(python_report_path.resolve()),
                "pythonResultSha256": sha256_file(python_report_path),
                "serverValid": server_command_ok and bool(server_report.get("valid")),
                "pythonValid": python_valid,
                "statisticsMatch": statistics_match,
                "provenanceValid": provenance_valid,
                "fatalFindings": server_fatals + python_fatals,
                "frames": int(server_report.get("frames", 0)),
                "rosterSize": int(server_report.get("rosterSize", 0)),
                "channelIds": sorted(server_report.get("channelIds", [])),
                "validResetWindows": len(server_report.get("validResetWindows", [])),
                "transports": sorted(server_report.get("transports", [])),
            }
        )

    has_sources = {source for source, _ in pilots} == {"SIMULATED", "REAL"}
    has_multiple_layouts = len({pilot["rosterSize"] for pilot in pilot_evidence}) >= 2
    covered_transports = {
        transport for pilot in pilot_evidence for transport in pilot["transports"]
    }
    ready = (
        all(passed for passed, _, _ in check_results.values())
        and has_sources
        and has_multiple_layouts
        and len(covered_transports) >= 2
        and bool(pilot_evidence)
        and all(
            pilot["serverValid"]
            and pilot["pythonValid"]
            and pilot["statisticsMatch"]
            and pilot["provenanceValid"]
            and not pilot["fatalFindings"]
            for pilot in pilot_evidence
        )
    )
    generated = datetime.now(timezone.utc)
    build_commit, build_dirty = git_build_identity(root)
    dirty_policy = "ALLOW_DEVELOPMENT" if args.allow_dirty_development else "REQUIRE_CLEAN"
    if build_commit == "UNKNOWN" or (build_dirty and dirty_policy == "REQUIRE_CLEAN"):
        ready = False
    missing_real = "REAL" not in {source for source, _ in pilots}
    report = {
        "format": "nekovr-dataset-ready-report-v2",
        "schemaVersion": REPORT_SCHEMA_VERSION,
        "verifier": {"id": VERIFIER_ID, "version": VERIFIER_VERSION},
        "reportPath": str(report_path.resolve()),
        "generatedUtc": generated.isoformat().replace("+00:00", "Z"),
        "expiresUtc": (generated + REPORT_LIFETIME).isoformat().replace("+00:00", "Z"),
        "build": {
            "commit": build_commit,
            "dirty": build_dirty,
            "dirtyTreePolicy": dirty_policy,
        },
        "ready": ready,
        "blockingFindings": (["Missing physical real-pilot evidence"] if missing_real else []),
        "evidence": [
            {
                "id": evidence_id,
                "passed": passed,
                "detail": detail,
                "commands": commands,
                "commandSha256": [command_hash(command) for command in commands],
                "resultArtifact": str(evidence_artifacts[evidence_id].resolve())
                if evidence_id in evidence_artifacts
                else "",
                "resultSha256": sha256_file(evidence_artifacts[evidence_id])
                if evidence_id in evidence_artifacts and evidence_artifacts[evidence_id].is_file()
                else "",
            }
            for evidence_id, (passed, detail, commands) in check_results.items()
        ],
        "pilots": pilot_evidence,
    }
    write_atomic(report_path, report)
    print(json.dumps(report, indent=2))
    print(f"dataset-ready report: {report_path}")
    return 0 if ready else 1


if __name__ == "__main__":
    raise SystemExit(main())
