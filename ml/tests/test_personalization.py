import json

import pytest

from nekovr_ml.model import CompactCausalModel, ModelConfig
from nekovr_ml.personalization import descriptor_from_model, generate_all_personalization_artifacts, verify_personalization_artifacts


def test_generated_bundle_freezes_backbone_and_hashes_every_artifact(tmp_path):
    model = CompactCausalModel(ModelConfig(feature_count=4, hidden_size=4, max_slots=10), seed=2)
    descriptor = descriptor_from_model("small-v1", model, ("body:hip",), (5, 6, 8, 10))
    paths = generate_all_personalization_artifacts((descriptor,), tmp_path)
    manifest = verify_personalization_artifacts(paths[0])
    assert set(manifest["trainable_parameters"]) == set(model.adapter_parameter_names)
    assert not set(manifest["trainable_parameters"]) & set(manifest["frozen_parameters"])
    assert manifest["trainable_parameter_count"] < manifest["frozen_parameter_count"]
    assert set(manifest["artifact_sha256"]) == {"training_graph.json", "evaluation_graph.json", "optimizer.json", "nominal_checkpoint.json"}
    checkpoint = json.loads((paths[0] / "nominal_checkpoint.json").read_text())
    assert all("values" in parameter for parameter in checkpoint["parameters"].values())


def test_tampered_artifact_is_rejected(tmp_path):
    descriptor = descriptor_from_model("small-v1", CompactCausalModel(ModelConfig(feature_count=2), seed=1), ("body:hip",), (5,))
    root = generate_all_personalization_artifacts((descriptor,), tmp_path)[0]
    (root / "optimizer.json").write_text("{}")
    with pytest.raises(ValueError, match="hash mismatch"):
        verify_personalization_artifacts(root)
