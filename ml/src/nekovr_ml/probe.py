from __future__ import annotations

import json
from pathlib import Path

from .model import CompactCausalModel, ModelConfig, SequenceSample, collate_variable_layout
from .onnx_export import export_model
from .provenance import canonical_hash, file_sha256


PROBE_FORMAT = "nekovr-onnx-probe-v1"
PROBE_SEED = 805


def _write_json(path: Path, payload: object) -> None:
    temporary = path.with_suffix(path.suffix + ".partial")
    temporary.write_text(json.dumps(payload, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def create_probe_bundle(output_directory: str | Path) -> Path:
    output = Path(output_directory)
    output.mkdir(parents=True, exist_ok=True)
    config = ModelConfig(feature_count=2, hidden_size=2, temporal_layers=1, kernel_size=2, role_count=8, max_slots=2)
    model = CompactCausalModel(config, seed=PROBE_SEED)
    feature_schema = {"version": 1, "features": ["probe_orientation", "probe_acceleration"]}
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
        "format": "nekovr-onnx-probe-manifest-v1", "deterministic_seed": PROBE_SEED,
        "files": {
            model_path.name: file_sha256(model_path), sidecar_path.name: file_sha256(sidecar_path),
            fixture_path.name: file_sha256(fixture_path),
        },
    })
    return manifest_path
