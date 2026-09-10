#!/usr/bin/env python3
"""Run the complete dataset-ready gate and persist one machine-readable report."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
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

DIRECT_SERVER_TESTS = {
    "schema": (
        "dev.slimevr.unit.DatasetArchiveValidatorTests",
        "dev.slimevr.unit.DatasetConformanceFixtureTests",
        "dev.slimevr.unit.DatasetTelemetryChecksumTests",
    ),
    "numerical": ("dev.slimevr.unit.FP16BinaryPackerTests",),
    "metadata": (
        "dev.slimevr.unit.DatasetRecordingServiceTests.testMixedWiFiAndHIDnRFOriginTrackers",
        "dev.slimevr.unit.DatasetReplayTests",
    ),
    "reset-label": (
        "dev.slimevr.unit.ResetLabelBindingsTests",
        "dev.slimevr.unit.ResetSupervisionTests",
    ),
    "crash-recovery": (
        "dev.slimevr.unit.DatasetRecordingServiceTests.testForcedCrashAndStartupRecovery",
        "dev.slimevr.unit.DatasetRecordingServiceTests.testRecoveryPreservesDurableManifestAndProducesValidArchive",
    ),
    "memory-soak": (
        "dev.slimevr.unit.DatasetRecordingServiceTests.testLongSoakRecordingMemoryAndWatermark",
    ),
}


def run(command: list[str], cwd: Path, env: dict[str, str] | None = None) -> tuple[bool, str]:
    completed = subprocess.run(command, cwd=cwd, env=env, text=True)
    return completed.returncode == 0, " ".join(command)


def git_commit(root: Path) -> str:
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=root, text=True, capture_output=True
    )
    if completed.returncode != 0:
        return "UNKNOWN"
    dirty = subprocess.run(
        ["git", "status", "--porcelain"], cwd=root, text=True, capture_output=True
    )
    suffix = "-dirty" if dirty.returncode != 0 or dirty.stdout.strip() else ""
    return completed.stdout.strip() + suffix


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
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    report_path = (root / args.report).resolve() if not args.report.is_absolute() else args.report
    pilots = [
        *(("SIMULATED", path.resolve()) for path in args.simulated_pilot),
        *(("REAL", path.resolve()) for path in args.real_pilot),
    ]
    check_results: dict[str, tuple[bool, str]] = {}
    gradle = str(root / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    pnpm = "pnpm.cmd" if os.name == "nt" else "pnpm"

    if args.skip_checks:
        for evidence_id in (*REQUIRED_SERVER_EVIDENCE, "rpc-ui", "baseline"):
            check_results[evidence_id] = (False, "skipped; diagnostic reports never pass the gate")
    else:
        for evidence_id, test_patterns in DIRECT_SERVER_TESTS.items():
            command = [gradle, ":server:core:test"]
            for pattern in test_patterns:
                command.extend(("--tests", pattern))
            check_results[evidence_id] = run(command, root)
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
        check_results["rpc-ui"] = (
            rpc_ok and gui_test_ok,
            f"{rpc_command}; {gui_test_command}",
        )
        check_results["baseline"] = run(
            ["node", "scripts/foundation-baseline.mjs"], root
        )

    server_reports: list[dict] = []
    server_command_ok = False
    with tempfile.TemporaryDirectory(prefix="nekovr-dataset-ready-") as temporary_dir:
        server_report_path = Path(temporary_dir) / "server-pilots.json"
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
        pilot_evidence.append(
            {
                "source": source,
                "archive": str(archive),
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
        all(passed for passed, _ in check_results.values())
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
    report = {
        "schemaVersion": 1,
        "generatedUtc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "buildCommit": git_commit(root),
        "ready": ready,
        "evidence": [
            {"id": evidence_id, "passed": passed, "detail": detail}
            for evidence_id, (passed, detail) in check_results.items()
        ],
        "pilots": pilot_evidence,
    }
    write_atomic(report_path, report)
    print(json.dumps(report, indent=2))
    print(f"dataset-ready report: {report_path}")
    return 0 if ready else 1


if __name__ == "__main__":
    raise SystemExit(main())
