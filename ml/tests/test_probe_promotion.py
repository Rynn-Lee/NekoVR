from dataclasses import replace
import json

import pytest

from nekovr_ml.model_metadata import load_sidecar
from nekovr_ml.probe import create_probe_bundle, verify_probe_bundle
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
    with pytest.raises(TypeError):
        publish_catalog_entry(model, sidecar_path, catalog, _evidence())
    assert not catalog.exists()


def test_probe_bundle_is_byte_reproducible_and_runs_on_cpu(tmp_path):
    first = tmp_path / "first"
    second = tmp_path / "second"
    create_probe_bundle(first)
    create_probe_bundle(second)
    for name in ("probe.onnx", "probe.onnx.json", "probe-fixture.json", "manifest.json"):
        assert (first / name).read_bytes() == (second / name).read_bytes()
    report = verify_probe_bundle(first)
    assert report["providers"][0]["provider"] == "CPUExecutionProvider"


def test_probe_bundle_rejects_tampering_partial_files_and_wrong_finite_expected_output(tmp_path):
    bundle = tmp_path / "probe"
    create_probe_bundle(bundle)
    sidecar = bundle / "probe.onnx.json"
    sidecar.write_bytes(sidecar.read_bytes() + b" ")
    with pytest.raises(ValueError, match="hash differs"):
        verify_probe_bundle(bundle)

    create_probe_bundle(bundle)
    fixture_path = bundle / "probe-fixture.json"
    fixture = json.loads(fixture_path.read_text())
    fixture["expected_outputs"]["confidence"][0][0] += 0.25
    fixture_path.write_text(json.dumps(fixture, indent=2, sort_keys=True) + "\n")
    manifest_path = bundle / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    from nekovr_ml.provenance import file_sha256
    manifest["files"]["probe-fixture.json"] = file_sha256(fixture_path)
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    with pytest.raises(ValueError, match="committed fixture"):
        verify_probe_bundle(bundle)

    create_probe_bundle(bundle)
    (bundle / "probe-fixture.json").unlink()
    with pytest.raises(ValueError, match="hash differs"):
        verify_probe_bundle(bundle)
