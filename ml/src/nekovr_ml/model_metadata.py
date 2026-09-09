from __future__ import annotations

from dataclasses import asdict, dataclass
import json
import math
from pathlib import Path
from typing import Any, Mapping, Sequence

from .provenance import file_sha256


SIDECAR_FORMAT = "nekovr-model-sidecar-v1"


@dataclass(frozen=True)
class TensorMetadata:
    name: str
    dtype: str
    shape: tuple[str | int, ...]
    semantics: str


@dataclass(frozen=True)
class ModelSidecar:
    format: str
    schema_version: int
    model_id: str
    model_version: str
    feature_schema_sha256: str
    inputs: tuple[TensorMetadata, ...]
    outputs: tuple[TensorMetadata, ...]
    normalization: Mapping[str, Any]
    supported_roles: tuple[int, ...]
    slot_bounds: Mapping[str, int]
    context_bounds: Mapping[str, int]
    output_convention: Mapping[str, Any]
    provenance: Mapping[str, Any]
    validation_metrics: Mapping[str, Any]
    opset: int
    performance_tier: str
    model_size_bytes: int
    model_sha256: str
    model_kind: str = "global"
    profile_id: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def _is_sha256(value: object) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(character in "0123456789abcdef" for character in value)


def validate_sidecar(sidecar: ModelSidecar, model_path: str | Path | None = None) -> None:
    if sidecar.format != SIDECAR_FORMAT or sidecar.schema_version != 1:
        raise ValueError("unsupported model sidecar format or schema version")
    if not sidecar.model_id or not sidecar.model_version:
        raise ValueError("model identity and version are required")
    if sidecar.model_kind not in {"global", "personal"} or (sidecar.model_kind == "personal" and not sidecar.profile_id):
        raise ValueError("personal model sidecars require a profile ID")
    if not _is_sha256(sidecar.feature_schema_sha256) or not _is_sha256(sidecar.model_sha256):
        raise ValueError("feature schema and model hashes must be lowercase SHA-256 values")
    if sidecar.opset <= 0 or sidecar.model_size_bytes <= 0:
        raise ValueError("opset and model size must be positive")
    if not sidecar.performance_tier:
        raise ValueError("performance tier is required")
    for bounds, label in ((sidecar.slot_bounds, "slot"), (sidecar.context_bounds, "context")):
        if set(bounds) != {"minimum", "maximum"} or int(bounds["minimum"]) <= 0 or int(bounds["minimum"]) > int(bounds["maximum"]):
            raise ValueError(f"invalid {label} bounds")
    if len(set(sidecar.supported_roles)) != len(sidecar.supported_roles) or any(role < 0 for role in sidecar.supported_roles):
        raise ValueError("supported roles must be unique non-negative IDs")
    names = [tensor.name for tensor in sidecar.inputs + sidecar.outputs]
    if len(names) != len(set(names)) or not sidecar.inputs or not sidecar.outputs:
        raise ValueError("tensor names must be unique and inputs/outputs cannot be empty")
    mean = sidecar.normalization.get("mean")
    standard_deviation = sidecar.normalization.get("standard_deviation")
    if not isinstance(mean, Sequence) or isinstance(mean, (str, bytes)) or not isinstance(standard_deviation, Sequence) or isinstance(standard_deviation, (str, bytes)):
        raise ValueError("normalization mean and standard_deviation are required")
    if len(mean) != len(standard_deviation) or not mean or any(not math.isfinite(float(value)) for value in mean):
        raise ValueError("normalization vectors must be finite and equally sized")
    if any(not math.isfinite(float(value)) or float(value) <= 0.0 for value in standard_deviation):
        raise ValueError("normalization standard deviations must be finite and positive")
    required_provenance = {"seed", "config_sha256", "source_commit", "dataset_hashes"}
    if not required_provenance.issubset(sidecar.provenance):
        raise ValueError("sidecar provenance is incomplete")
    if model_path is not None:
        model = Path(model_path)
        if model.stat().st_size != sidecar.model_size_bytes or file_sha256(model) != sidecar.model_sha256:
            raise ValueError("model size or SHA-256 does not match sidecar")


def write_sidecar(sidecar: ModelSidecar, output: str | Path) -> None:
    validate_sidecar(sidecar)
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".partial")
    temporary.write_text(json.dumps(sidecar.to_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(target)


def load_sidecar(path: str | Path, model_path: str | Path | None = None) -> ModelSidecar:
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    try:
        sidecar = ModelSidecar(
            format=payload["format"], schema_version=int(payload["schema_version"]), model_id=payload["model_id"],
            model_version=payload["model_version"], feature_schema_sha256=payload["feature_schema_sha256"],
            inputs=tuple(TensorMetadata(value["name"], value["dtype"], tuple(value["shape"]), value["semantics"]) for value in payload["inputs"]),
            outputs=tuple(TensorMetadata(value["name"], value["dtype"], tuple(value["shape"]), value["semantics"]) for value in payload["outputs"]),
            normalization=payload["normalization"], supported_roles=tuple(int(value) for value in payload["supported_roles"]),
            slot_bounds=payload["slot_bounds"], context_bounds=payload["context_bounds"],
            output_convention=payload["output_convention"], provenance=payload["provenance"],
            validation_metrics=payload["validation_metrics"], opset=int(payload["opset"]),
            performance_tier=payload["performance_tier"], model_size_bytes=int(payload["model_size_bytes"]),
            model_sha256=payload["model_sha256"],
            model_kind=payload.get("model_kind", "global"), profile_id=payload.get("profile_id"),
        )
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError(f"invalid model sidecar: {error}") from error
    validate_sidecar(sidecar, model_path)
    return sidecar
