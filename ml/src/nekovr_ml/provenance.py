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
) -> RunManifest:
    datasets = {name: file_sha256(path) for name, path in sorted(dataset_paths.items())}
    artifacts = {name: file_sha256(path) for name, path in sorted(artifact_paths.items())}
    normalization_value = asdict(normalization) if isinstance(normalization, Normalization) else dict(normalization)
    splits = dict(sorted(split_assignments.items()))
    commit, dirty = _git_state(repository)
    stable = {
        "seed": config.seed,
        "config_sha256": config.sha256,
        "source_commit": commit,
        "source_dirty": dirty,
        "dataset_hashes": datasets,
        "split_sha256": canonical_hash(splits),
        "normalization_sha256": canonical_hash(normalization_value),
        "artifact_hashes": artifacts,
    }
    fingerprint = canonical_hash(stable)
    return RunManifest(
        schema_version=1,
        run_id=fingerprint[:16],
        created_utc=datetime.now(timezone.utc).isoformat(),
        seed=config.seed,
        config=config.values,
        config_sha256=config.sha256,
        environment=dict(environment or environment_snapshot()),
        source_commit=commit,
        source_dirty=dirty,
        dataset_hashes=datasets,
        split_assignments=splits,
        split_sha256=stable["split_sha256"],
        normalization=normalization_value,
        normalization_sha256=stable["normalization_sha256"],
        metrics=dict(metrics),
        artifact_hashes=artifacts,
        reproducibility_fingerprint=fingerprint,
    )


def write_run_manifest(manifest: RunManifest, output: str | Path) -> None:
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".partial")
    temporary.write_text(json.dumps(manifest.to_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(target)

