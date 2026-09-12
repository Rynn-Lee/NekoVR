"""Validate and exercise the hash-locked ML dependency closure.

The staging step may use an index. Both fresh virtual environments are then
created and installed with --no-index from the staged, hash-verified wheelhouse.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import venv

sys.path.insert(0, str(Path(__file__).resolve().parent / "src"))

from nekovr_ml.dependency_lock import locked_packages


def _run(command: list[str], cwd: Path) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def _environment_python(environment: Path) -> Path:
    return environment / ("Scripts/python.exe" if sys.platform == "win32" else "bin/python")


def _installed(python: Path, cwd: Path) -> dict[str, str]:
    result = subprocess.run(
        [str(python), "-m", "pip", "list", "--format=json"],
        cwd=cwd,
        check=True,
        capture_output=True,
        text=True,
    )
    return {
        item["name"].lower().replace("_", "-"): item["version"]
        for item in json.loads(result.stdout)
        if item["name"].lower() not in {"pip", "nekovr-ml"}
    }


def verify(lock: Path, project: Path, wheelhouse: Path, offline: bool) -> str:
    expected = locked_packages(lock)
    wheelhouse.mkdir(parents=True, exist_ok=True)
    if not offline:
        _run([
            sys.executable, "-m", "pip", "download", "--require-hashes",
            "--only-binary=:all:", "--dest", str(wheelhouse),
            "--requirement", str(lock),
        ], project)
    environments = []
    for index in range(2):
        environment = wheelhouse.parent / f"fresh-env-{index}"
        venv.EnvBuilder(with_pip=True, clear=True).create(environment)
        python = _environment_python(environment)
        _run([
            str(python), "-m", "pip", "install", "--no-index",
            "--find-links", str(wheelhouse), "--require-hashes",
            "--requirement", str(lock),
        ], project)
        _run([
            str(python), "-m", "pip", "install", "--no-index",
            "--find-links", str(wheelhouse), "--no-deps", "--no-build-isolation", str(project),
        ], project)
        _run([str(python), "-m", "pip", "check"], project)
        installed = _installed(python, project)
        if installed != expected:
            raise RuntimeError(f"fresh environment differs from lock: expected={expected}, installed={installed}")
        environments.append(installed)
    if environments[0] != environments[1]:
        raise RuntimeError("repeat installation produced a different dependency closure")
    fingerprint = hashlib.sha256(
        json.dumps(environments[0], sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    return fingerprint


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, default=Path(__file__).with_name("requirements.lock"))
    parser.add_argument("--wheelhouse", type=Path)
    parser.add_argument("--offline", action="store_true", help="use an already staged wheelhouse")
    parser.add_argument("--check-only", action="store_true", help="validate closure and hashes without installing")
    args = parser.parse_args()
    packages = locked_packages(args.lock.resolve())
    if args.check_only:
        print(json.dumps({"packages": packages}, sort_keys=True))
        return 0
    project = Path(__file__).resolve().parent
    if args.wheelhouse:
        fingerprint = verify(args.lock.resolve(), project, args.wheelhouse.resolve(), args.offline)
    else:
        with tempfile.TemporaryDirectory(prefix="nekovr-ml-lock-") as directory:
            fingerprint = verify(args.lock.resolve(), project, Path(directory) / "wheelhouse", args.offline)
    print(json.dumps({"closure_sha256": fingerprint, "package_count": len(packages), "repeats": 2}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
