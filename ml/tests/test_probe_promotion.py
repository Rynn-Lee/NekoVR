from dataclasses import replace
import json

import numpy as np
import onnxruntime as ort
import pytest

from nekovr_ml.model_metadata import load_sidecar
from nekovr_ml.probe import create_probe_bundle
from nekovr_ml.promotion import (
    ArtifactPromotionEvidence,
    ArtifactPromotionPolicy,
    evaluate_artifact_promotion,
    publish_catalog_entry,
)


def _evidence(**changes):
    values = {
        "maximum_parity_error": 1e-7, "percentile_99_parity_error": 1e-8,
        "finite_outputs": True, "bounded_outputs": True, "masked_slots_zero": True,
        "activity_gate_passed": True, "quality_non_regression_passed": True,
    }
    values.update(changes)
    return ArtifactPromotionEvidence(**values)


def test_small_model_publication_requires_size_safety_quality_and_parity(tmp_path):
    bundle = tmp_path / "probe"
    create_probe_bundle(bundle)
    model = bundle / "probe.onnx"
    sidecar_path = bundle / "probe.onnx.json"
    sidecar = replace(load_sidecar(sidecar_path, model), performance_tier="small")
    assert evaluate_artifact_promotion(sidecar, _evidence()).passed
    oversized = replace(sidecar, model_size_bytes=15 * 1024 * 1024 + 1)
    result = evaluate_artifact_promotion(
        oversized,
        _evidence(maximum_parity_error=1e-3, finite_outputs=False, activity_gate_passed=False),
        ArtifactPromotionPolicy(),
    )
    assert not result.passed
    assert set(result.failures) >= {"small_model_size", "maximum_parity_error", "finite_outputs", "activity_gate_passed"}

    catalog = tmp_path / "catalog.json"
    with pytest.raises(ValueError, match="publication gates"):
        publish_catalog_entry(model, sidecar_path, catalog, _evidence(quality_non_regression_passed=False))
    assert not catalog.exists()
    assert publish_catalog_entry(model, sidecar_path, catalog, _evidence()).passed
    assert json.loads(catalog.read_text())["entries"][0]["model_sha256"] == load_sidecar(sidecar_path, model).model_sha256


def test_probe_bundle_is_byte_reproducible_and_runs_on_cpu(tmp_path):
    first = tmp_path / "first"
    second = tmp_path / "second"
    create_probe_bundle(first)
    create_probe_bundle(second)
    for name in ("probe.onnx", "probe.onnx.json", "probe-fixture.json", "manifest.json"):
        assert (first / name).read_bytes() == (second / name).read_bytes()
    fixture = json.loads((first / "probe-fixture.json").read_text())
    inputs = fixture["inputs"]
    runtime_inputs = {
        "features": np.asarray(inputs["features"], dtype=np.float32),
        "role_ids": np.asarray(inputs["role_ids"], dtype=np.int64),
        "slot_mask": np.asarray(inputs["slot_mask"], dtype=np.bool_),
        "channel_validity": np.asarray(inputs["channel_validity"], dtype=np.bool_),
        "time_deltas_s": np.asarray(inputs["time_deltas_s"], dtype=np.float32),
        "time_mask": np.asarray(inputs["time_mask"], dtype=np.bool_),
    }
    names = ("correction_rotation_vectors", "confidence", "drift_rate")
    actual = ort.InferenceSession(str(first / "probe.onnx"), providers=["CPUExecutionProvider"]).run(list(names), runtime_inputs)
    for name, value in zip(names, actual):
        assert value == pytest.approx(np.asarray(fixture["expected_outputs"][name]), abs=fixture["absolute_tolerance"])
