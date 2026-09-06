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


def test_future_stage_entry_point_is_truthful(capsys):
    assert cli._run(["export-onnx"]) == 3
    assert '"implemented": false' in capsys.readouterr().out
