import json

from nekovr_ml.config import load_config
from nekovr_ml.provenance import compare_run_manifests, create_run_manifest, write_run_manifest
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
        lineage={"sources": [{"kind": "fixture", "source_sha256": "a" * 64}], "windows": [{"sample_id": "a:0"}]},
    )
    first = create_run_manifest(**kwargs)
    second = create_run_manifest(**kwargs)
    assert first.reproducibility_fingerprint == second.reproducibility_fingerprint
    assert first.dataset_hashes["source"] == "b277fd623676a525c29b9eb155afc8c9010681814ceafb2d7627f47b9a232576"
    output = tmp_path / "run.json"
    write_run_manifest(first, output)
    payload = json.loads(output.read_text())
    assert payload["schema_version"] == 2
    assert payload["split_sha256"] and payload["normalization_sha256"] and payload["artifact_hashes"]
    assert payload["lineage"]["sources"] and payload["lineage_sha256"]
    assert not output.with_suffix(".json.partial").exists()


def test_repeat_comparison_is_exact_for_identity_and_tolerant_only_for_metrics(tmp_path):
    config_path = tmp_path / "config.json"
    config_path.write_text(json.dumps({"schema_version": 1, "seed": 5, "deterministic": True, "sample_rate_hz": 50, "layouts": [5]}))
    dataset = tmp_path / "dataset.bin"
    artifact = tmp_path / "model.bin"
    dataset.write_bytes(b"dataset")
    artifact.write_bytes(b"model")
    common = dict(
        config=load_config(config_path), repository=tmp_path, dataset_paths={"source": dataset},
        split_assignments={"a": "train"}, normalization=Normalization((1.0,), (2.0,), ("a",)),
        artifact_paths={"model": artifact}, environment={"python": "fixture"}, lineage={"windows": ["a"]},
    )
    first = create_run_manifest(metrics={"loss": 0.1}, **common)
    second = create_run_manifest(metrics={"loss": 0.1000000005}, **common)
    assert compare_run_manifests(first, second, 1e-9)["passed"]
    assert not compare_run_manifests(first, second, 1e-12)["passed"]
    changed = dict(second.to_dict())
    changed["artifact_hashes"] = {"model": "0" * 64}
    assert not compare_run_manifests(first, changed, 1e-9)["passed"]
