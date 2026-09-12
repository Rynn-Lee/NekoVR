from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import hashlib
from importlib import metadata
import json
import platform
from pathlib import Path
import subprocess
import sys
from typing import Any, Mapping, Sequence

from .config import RunConfiguration
from .splits import Normalization


def canonical_hash(value: Any) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()
    return hashlib.sha256(encoded).hexdigest()


def file_sha256(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _git_state(repository: str | Path) -> tuple[str, bool]:
    root = str(Path(repository).resolve())
    try:
        commit = subprocess.run(["git", "-C", root, "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
        dirty = subprocess.run(["git", "-C", root, "status", "--porcelain"], check=True, capture_output=True, text=True).stdout != ""
        return commit, dirty
    except (OSError, subprocess.CalledProcessError):
        return "UNKNOWN", True


def environment_snapshot() -> dict[str, Any]:
    packages = sorted(
        f"{distribution.metadata.get('Name', 'UNKNOWN')}=={distribution.version}"
        for distribution in metadata.distributions()
    )
    return {
        "python": sys.version.split()[0],
        "implementation": platform.python_implementation(),
        "platform": platform.platform(),
        "packages": packages,
    }


@dataclass(frozen=True)
class RunManifest:
    schema_version: int
    run_id: str
    created_utc: str
    seed: int
    config: Mapping[str, Any]
    config_sha256: str
    environment: Mapping[str, Any]
    source_commit: str
    source_dirty: bool
    dataset_hashes: Mapping[str, str]
    split_assignments: Mapping[str, str]
    split_sha256: str
    normalization: Mapping[str, Any]
    normalization_sha256: str
    metrics: Mapping[str, Any]
    artifact_hashes: Mapping[str, str]
    lineage: Mapping[str, Any]
    lineage_sha256: str
    reproducibility_fingerprint: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def create_run_manifest(
    config: RunConfiguration,
    repository: str | Path,
    dataset_paths: Mapping[str, str | Path],
    split_assignments: Mapping[str, str],
    normalization: Normalization | Mapping[str, Any],
    metrics: Mapping[str, Any],
    artifact_paths: Mapping[str, str | Path],
    environment: Mapping[str, Any] | None = None,
    lineage: Mapping[str, Any] | None = None,
) -> RunManifest:
    datasets = {name: file_sha256(path) for name, path in sorted(dataset_paths.items())}
    artifacts = {name: file_sha256(path) for name, path in sorted(artifact_paths.items())}
    normalization_value = asdict(normalization) if isinstance(normalization, Normalization) else dict(normalization)
    splits = dict(sorted(split_assignments.items()))
    commit, dirty = _git_state(repository)
    environment_value = dict(environment or environment_snapshot())
    lineage_value = dict(lineage or {})
    stable = {
        "seed": config.seed,
        "config_sha256": config.sha256,
        "source_commit": commit,
        "source_dirty": dirty,
        "dataset_hashes": datasets,
        "split_sha256": canonical_hash(splits),
        "normalization_sha256": canonical_hash(normalization_value),
        "artifact_hashes": artifacts,
        "environment_sha256": canonical_hash(environment_value),
        "lineage_sha256": canonical_hash(lineage_value),
    }
    fingerprint = canonical_hash(stable)
    return RunManifest(
        schema_version=2,
        run_id=fingerprint[:16],
        created_utc=datetime.now(timezone.utc).isoformat(),
        seed=config.seed,
        config=config.values,
        config_sha256=config.sha256,
        environment=environment_value,
        source_commit=commit,
        source_dirty=dirty,
        dataset_hashes=datasets,
        split_assignments=splits,
        split_sha256=stable["split_sha256"],
        normalization=normalization_value,
        normalization_sha256=stable["normalization_sha256"],
        metrics=dict(metrics),
        artifact_hashes=artifacts,
        lineage=lineage_value,
        lineage_sha256=stable["lineage_sha256"],
        reproducibility_fingerprint=fingerprint,
    )


def write_run_manifest(manifest: RunManifest, output: str | Path) -> None:
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".partial")
    temporary.write_text(json.dumps(manifest.to_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(target)


def _numeric_differences(left: Any, right: Any, path: str, tolerance: float, result: list[dict[str, Any]]) -> None:
    if isinstance(left, Mapping) and isinstance(right, Mapping):
        for key in sorted(set(left) | set(right)):
            if key not in left or key not in right:
                result.append({"path": f"{path}.{key}", "reason": "missing"})
            else:
                _numeric_differences(left[key], right[key], f"{path}.{key}", tolerance, result)
        return
    if isinstance(left, Sequence) and not isinstance(left, (str, bytes)) and isinstance(right, Sequence) and not isinstance(right, (str, bytes)):
        if len(left) != len(right):
            result.append({"path": path, "reason": "length"})
            return
        for index, (left_value, right_value) in enumerate(zip(left, right)):
            _numeric_differences(left_value, right_value, f"{path}[{index}]", tolerance, result)
        return
    if isinstance(left, (int, float)) and not isinstance(left, bool) and isinstance(right, (int, float)) and not isinstance(right, bool):
        if abs(float(left) - float(right)) > tolerance:
            result.append({"path": path, "left": left, "right": right, "absolute_difference": abs(float(left) - float(right))})
    elif left != right:
        result.append({"path": path, "left": left, "right": right})


def compare_run_manifests(
    left: RunManifest | Mapping[str, Any],
    right: RunManifest | Mapping[str, Any],
    metric_absolute_tolerance: float = 1e-9,
) -> dict[str, Any]:
    """Compare repeat runs while ignoring wall-clock IDs and enforcing exact artifact identity."""
    if metric_absolute_tolerance < 0:
        raise ValueError("reproducibility tolerance cannot be negative")
    left_value = left.to_dict() if isinstance(left, RunManifest) else dict(left)
    right_value = right.to_dict() if isinstance(right, RunManifest) else dict(right)
    differences: list[dict[str, Any]] = []
    exact = (
        "seed", "config_sha256", "environment", "source_commit", "source_dirty", "dataset_hashes",
        "split_assignments", "split_sha256", "normalization", "normalization_sha256", "artifact_hashes",
        "lineage", "lineage_sha256", "reproducibility_fingerprint",
    )
    for name in exact:
        if left_value.get(name) != right_value.get(name):
            differences.append({"path": name, "reason": "exact_mismatch"})
    _numeric_differences(left_value.get("metrics", {}), right_value.get("metrics", {}), "metrics", metric_absolute_tolerance, differences)
    return {
        "format": "nekovr-repeat-comparison-v1",
        "metric_absolute_tolerance": metric_absolute_tolerance,
        "passed": not differences,
        "differences": differences,
    }
