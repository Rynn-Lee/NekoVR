import json

import pytest

from nekovr_ml.model import CompactCausalModel, ModelConfig
from nekovr_ml.personalization import descriptor_from_model, execute_portable_personalization_probe, generate_all_personalization_artifacts, verify_personalization_artifacts


def test_generated_bundle_freezes_backbone_and_hashes_every_artifact(tmp_path):
    model = CompactCausalModel(ModelConfig(feature_count=4, hidden_size=4, max_slots=10), seed=2)
    descriptor = descriptor_from_model("small-v1", model, ("body:hip",), (5, 6, 8, 10))
    paths = generate_all_personalization_artifacts((descriptor,), tmp_path)
    manifest = verify_personalization_artifacts(paths[0])
    assert set(manifest["trainable_parameters"]) == set(model.adapter_parameter_names)
    assert not set(manifest["trainable_parameters"]) & set(manifest["frozen_parameters"])
    assert manifest["trainable_parameter_count"] < manifest["frozen_parameter_count"]
    assert set(manifest["artifact_sha256"]) == {
        "training_graph.json", "evaluation_graph.json", "optimizer.json", "nominal_checkpoint.json",
        "base_model_checkpoint.json", "probe-training-input.json", "worker-request.json",
    }
    checkpoint = json.loads((paths[0] / "nominal_checkpoint.json").read_text())
    assert all("values" in parameter for parameter in checkpoint["parameters"].values())
    probe = execute_portable_personalization_probe(paths[0])
    assert probe["passed"] and probe["base_model_sha256"] == descriptor.model_sha256


def test_equal_shapes_with_different_weights_have_distinct_base_identity(tmp_path):
    first = descriptor_from_model("small-v1", CompactCausalModel(ModelConfig(feature_count=2), seed=1), ("body:hip",), (5,))
    second = descriptor_from_model("small-v1", CompactCausalModel(ModelConfig(feature_count=2), seed=2), ("body:hip",), (5,))
    assert first.parameter_shapes == second.parameter_shapes
    assert first.model_sha256 != second.model_sha256
    first_root = generate_all_personalization_artifacts((first,), tmp_path / "first")[0]
    second_root = generate_all_personalization_artifacts((second,), tmp_path / "second")[0]
    assert verify_personalization_artifacts(first_root)["base_model_sha256"] != verify_personalization_artifacts(second_root)["base_model_sha256"]


def test_tampered_artifact_is_rejected(tmp_path):
    descriptor = descriptor_from_model("small-v1", CompactCausalModel(ModelConfig(feature_count=2), seed=1), ("body:hip",), (5,))
    root = generate_all_personalization_artifacts((descriptor,), tmp_path)[0]
    (root / "optimizer.json").write_text("{}")
    with pytest.raises(ValueError, match="hash mismatch"):
        verify_personalization_artifacts(root)
