import json

import pytest

from nekovr_ml import cli
from nekovr_ml.config import ConfigurationError, deterministic_random, load_config


def test_configuration_is_canonical_and_namespaced(tmp_path):
    path = tmp_path / "config.json"
    path.write_text(json.dumps({"sample_rate_hz": 50, "deterministic": True, "seed": 7, "schema_version": 1, "layouts": [5]}))
    config = load_config(path)
    assert config.sha256 == "e42bc3b5a756afeabd44196414a3c931deb74cf44683b95fe4cb161a14f989b4"
    assert deterministic_random(config, "a").random() == deterministic_random(config, "a").random()
    assert deterministic_random(config, "a").random() != deterministic_random(config, "b").random()


def test_configuration_rejects_nondeterministic_mode(tmp_path):
    path = tmp_path / "config.json"
    path.write_text(json.dumps({"sample_rate_hz": 50, "deterministic": False, "seed": 7, "schema_version": 1, "layouts": [5]}))
    with pytest.raises(ConfigurationError, match="deterministic"):
        load_config(path)


def test_packaged_and_repository_default_configs_match():
    packaged = load_config(cli._default_config())
    repository = load_config(cli._default_config().parents[2] / "configs" / "default.json")
    assert packaged.sha256 == repository.sha256


def test_onnx_entry_points_require_explicit_artifacts():
    export = cli._parser().parse_args(["export-onnx", "--checkpoint", "model.json", "--metadata", "metadata.json", "--output", "model.onnx"])
    validate = cli._parser().parse_args(["validate-onnx", "--model", "model.onnx"])
    assert (export.command, export.checkpoint, export.metadata, export.output) == ("export-onnx", "model.json", "metadata.json", "model.onnx")
    assert (validate.command, validate.model) == ("validate-onnx", "model.onnx")
