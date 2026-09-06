import json

from nekovr_ml.config import load_config
from nekovr_ml.provenance import create_run_manifest, write_run_manifest
from nekovr_ml.splits import Normalization


def test_run_manifest_records_all_reproducibility_inputs_atomically(tmp_path):
    config_path = tmp_path / "config.json"
    config_path.write_text(json.dumps({"schema_version": 1, "seed": 5, "deterministic": True, "sample_rate_hz": 50, "layouts": [5]}))
    dataset = tmp_path / "dataset.bin"
    artifact = tmp_path / "model.bin"
    dataset.write_bytes(b"dataset")
    artifact.write_bytes(b"model")
    kwargs = dict(
        config=load_config(config_path), repository=tmp_path,
        dataset_paths={"source": dataset}, split_assignments={"a": "train"},
        normalization=Normalization((1.0,), (2.0,), ("a",)), metrics={"loss": 0.1},
        artifact_paths={"model": artifact}, environment={"python": "fixture"},
    )
    first = create_run_manifest(**kwargs)
    second = create_run_manifest(**kwargs)
    assert first.reproducibility_fingerprint == second.reproducibility_fingerprint
    assert first.dataset_hashes["source"] == "b277fd623676a525c29b9eb155afc8c9010681814ceafb2d7627f47b9a232576"
    output = tmp_path / "run.json"
    write_run_manifest(first, output)
    payload = json.loads(output.read_text())
    assert payload["split_sha256"] and payload["normalization_sha256"] and payload["artifact_hashes"]
    assert not output.with_suffix(".json.partial").exists()
