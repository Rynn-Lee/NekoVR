from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import onnxruntime as ort

from .model import CompactCausalModel, ModelConfig, SequenceSample, collate_variable_layout
from .onnx_export import export_model
from .onnx_validation import inspect_export
from .provenance import canonical_hash, file_sha256


PROBE_FORMAT = "nekovr-onnx-probe-v1"
PROBE_MANIFEST_FORMAT = "nekovr-onnx-probe-manifest-v1"
PROBE_SEED = 805
PROBE_FILES = frozenset({"probe.onnx", "probe.onnx.json", "probe-fixture.json"})
PROBE_OUTPUTS = ("correction_rotation_vectors", "confidence", "drift_rate")


def _write_json(path: Path, payload: object) -> None:
    temporary = path.with_suffix(path.suffix + ".partial")
    temporary.write_text(json.dumps(payload, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def create_probe_bundle(output_directory: str | Path) -> Path:
    output = Path(output_directory)
    output.mkdir(parents=True, exist_ok=True)
    config = ModelConfig(feature_count=2, hidden_size=2, temporal_layers=1, kernel_size=2, role_count=8, max_slots=2)
    model = CompactCausalModel(config, seed=PROBE_SEED)
    feature_schema = {
        "version": 1,
        "features": [
            {"id": "probe_orientation", "order": 0, "unit": "unitless", "source": "diagnostic_fixture",
             "validity": "finite_when_source_valid", "missingness": "zero_with_invalid_mask"},
            {"id": "probe_acceleration", "order": 1, "unit": "m/s2", "source": "diagnostic_fixture",
             "validity": "finite_when_source_valid", "missingness": "zero_with_invalid_mask"},
        ],
    }
    model_path, sidecar_path = export_model(
        model, output / "probe.onnx", model_id="nekovr-provider-probe", model_version="1.0.0",
        feature_schema=feature_schema, normalization={"mean": [0.0, 0.0], "standard_deviation": [1.0, 1.0]},
        supported_roles=(1, 2), minimum_slots=1, minimum_context=2, maximum_context=4,
        provenance={"seed": PROBE_SEED, "config_sha256": canonical_hash({"model": config.__dict__}), "source_commit": "PROBE_FIXTURE", "dataset_hashes": {}},
        validation_metrics={"purpose": "provider_and_packaging_probe", "not_for_correction": True}, performance_tier="probe",
    )
    sample = SequenceSample(
        features=(((0.25, -0.5), (0.0, 0.0)), ((0.5, 0.125), (0.0, 0.0))),
        role_ids=(1, 0), slot_mask=(True, False),
        channel_validity=(((True, True), (False, False)), ((True, True), (False, False))),
        time_deltas_s=(0.02, 0.02),
    )
    batch = collate_variable_layout([sample], config.feature_count, config.max_slots)
    prediction = model.forward(batch)
    fixture_path = output / "probe-fixture.json"
    _write_json(fixture_path, {
        "format": PROBE_FORMAT,
        "inputs": {
            "features": batch.features, "role_ids": batch.role_ids, "slot_mask": batch.slot_mask,
            "channel_validity": batch.channel_validity, "time_deltas_s": batch.time_deltas_s, "time_mask": batch.time_mask,
        },
        "expected_outputs": {
            "correction_rotation_vectors": prediction.correction_rotation_vectors,
            "confidence": prediction.confidence, "drift_rate": prediction.drift_rate,
        },
        "absolute_tolerance": 1e-5,
    })
    manifest_path = output / "manifest.json"
    _write_json(manifest_path, {
        "format": PROBE_MANIFEST_FORMAT, "deterministic_seed": PROBE_SEED,
        "files": {
            model_path.name: file_sha256(model_path), sidecar_path.name: file_sha256(sidecar_path),
            fixture_path.name: file_sha256(fixture_path),
        },
    })
    return manifest_path


def verify_probe_bundle(
    bundle_directory: str | Path,
    providers: tuple[str, ...] = ("CPUExecutionProvider",),
) -> dict[str, object]:
    """Authenticate and execute the committed probe fixture on every requested provider."""
    bundle = Path(bundle_directory)
    manifest_path = bundle / "manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("probe manifest is missing or invalid") from error
    if manifest.get("format") != PROBE_MANIFEST_FORMAT or manifest.get("deterministic_seed") != PROBE_SEED:
        raise ValueError("probe manifest format or deterministic seed is invalid")
    files = manifest.get("files")
    if not isinstance(files, dict) or set(files) != PROBE_FILES:
        raise ValueError("probe manifest must bind the complete model, sidecar, and fixture")
    for name, expected_hash in files.items():
        if Path(name).name != name or not isinstance(expected_hash, str) or len(expected_hash) != 64:
            raise ValueError("probe manifest contains an invalid file reference")
        path = bundle / name
        if not path.is_file() or file_sha256(path) != expected_hash:
            raise ValueError(f"probe file hash differs from manifest: {name}")

    model_path = bundle / "probe.onnx"
    inspect_export(model_path, bundle / "probe.onnx.json")
    try:
        fixture = json.loads((bundle / "probe-fixture.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("probe fixture is missing or invalid") from error
    tolerance = fixture.get("absolute_tolerance")
    if fixture.get("format") != PROBE_FORMAT or not isinstance(tolerance, (int, float)) or not 0 < tolerance <= 1e-3:
        raise ValueError("probe fixture format or tolerance is invalid")
    inputs = fixture.get("inputs")
    expected = fixture.get("expected_outputs")
    if not isinstance(inputs, dict) or set(inputs) != {
        "features", "role_ids", "slot_mask", "channel_validity", "time_deltas_s", "time_mask",
    } or not isinstance(expected, dict) or set(expected) != set(PROBE_OUTPUTS):
        raise ValueError("probe fixture tensor names are noncanonical")
    runtime_inputs = {
        "features": np.asarray(inputs["features"], dtype=np.float32),
        "role_ids": np.asarray(inputs["role_ids"], dtype=np.int64),
        "slot_mask": np.asarray(inputs["slot_mask"], dtype=np.bool_),
        "channel_validity": np.asarray(inputs["channel_validity"], dtype=np.bool_),
        "time_deltas_s": np.asarray(inputs["time_deltas_s"], dtype=np.float32),
        "time_mask": np.asarray(inputs["time_mask"], dtype=np.bool_),
    }
    expected_arrays = {name: np.asarray(expected[name], dtype=np.float32) for name in PROBE_OUTPUTS}
    if not providers:
        raise ValueError("at least one concrete provider must be requested")
    reports: list[dict[str, object]] = []
    for provider in providers:
        session = ort.InferenceSession(str(model_path), providers=[provider])
        if not session.get_providers() or session.get_providers()[0] != provider:
            raise ValueError(f"requested provider was not activated: {provider}")
        actual = session.run(list(PROBE_OUTPUTS), runtime_inputs)
        maximum_error = 0.0
        for name, value in zip(PROBE_OUTPUTS, actual, strict=True):
            reference = expected_arrays[name]
            if value.shape != reference.shape or value.dtype != np.float32 or not np.isfinite(value).all() or not np.isfinite(reference).all():
                raise ValueError(f"probe output shape, dtype, or finiteness differs: {name}")
            error = float(np.max(np.abs(value - reference), initial=0.0))
            maximum_error = max(maximum_error, error)
            if error > tolerance:
                raise ValueError(f"probe output differs from committed fixture: {name}")
        reports.append({"provider": provider, "maximum_absolute_error": maximum_error})
    return {"format": PROBE_FORMAT, "files": dict(sorted(files.items())), "providers": reports}
