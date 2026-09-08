from __future__ import annotations

import base64
from dataclasses import asdict
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys

import pytest

from nekovr_ml.model import CompactCausalModel, ModelConfig
from nekovr_ml.personal_trainer import (
    EpochMetrics,
    IpcMessage,
    RSA_SHA256_DIGEST_INFO,
    RsaPublicKey,
    TrainingArtifactBundle,
    TrainingPolicy,
    probe_training_providers,
    run_guarded_training,
    select_training_provider,
    verify_worker_package,
)
from nekovr_ml.trainer_worker import handle_message
from nekovr_ml.training import write_model_checkpoint


ARTIFACTS = Path(__file__).parents[1] / "artifacts" / "nekovr-small-v1"
RSA_N = "86e753fb575af29e33573e1e28bbf196156e19e51e516a55653d2fab09982f199c5832b741d87d177caf02c1fd84be9c74e46dcf6ac4c181d26d1996705ffaa0a02471e80f1855c9f76d1613479a89fab4e92f347d44ce6f98050f9cb5c0aaec244dc62eb3579095e3babc654fc6983b527ec0f2f75b87f7b66fa0d04547f10d"
RSA_D = "615ca534fa07eea094c73b52c2ed59df8ad9eaa07cb01fc1400ed7cf665e4a67fe797d28dbc1e60e44737ed709247bf929380fb4aa0714eda034134a8b120a2c42d59e63e6710ce0316761914c36d2cca26024d21f0c1f914ca845046a54d130302b115cdb9825795591e4fb6a0d3690079841f4948d3b2d633761d0e868a115"


def _policy(**changes: object) -> TrainingPolicy:
    values = {
        "maximum_trainable_parameters": 200,
        "maximum_trainable_fraction": 0.1,
        "maximum_checkpoint_bytes": 8 * 1024 * 1024,
        "maximum_epochs": 8,
        "early_stopping_patience": 2,
        "early_stopping_min_delta": 0.01,
        "maximum_correction_radians": math.radians(30),
        "maximum_false_correction_radians": math.radians(30),
        "maximum_jitter_radians": math.radians(30),
    }
    values.update(changes)
    return TrainingPolicy(**values)


def _signature(payload: dict[str, object]) -> str:
    message = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()
    digest_info = RSA_SHA256_DIGEST_INFO + hashlib.sha256(message).digest()
    size = (int(RSA_N, 16).bit_length() + 7) // 8
    encoded = b"\x00\x01" + b"\xff" * (size - len(digest_info) - 3) + b"\x00" + digest_info
    signature = pow(int.from_bytes(encoded, "big"), int(RSA_D, 16), int(RSA_N, 16)).to_bytes(size, "big")
    return base64.b64encode(signature).decode()


def test_signed_worker_package_requires_hash_signature_protocol_and_containment(tmp_path):
    executable = tmp_path / "nekovr-trainer"
    executable.write_bytes(b"fixed worker executable")
    payload = {
        "format": "nekovr-trainer-package-v1",
        "protocol_version": 1,
        "version": "1.0.0",
        "platform": "test-x64",
        "license": "GPL-3.0-or-later",
        "executable": executable.name,
        "executable_sha256": hashlib.sha256(executable.read_bytes()).hexdigest(),
        "public_key_id": "release-test",
    }
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({**payload, "signature_base64": _signature(payload)}), encoding="utf-8")
    verified = verify_worker_package(tmp_path, manifest, {"release-test": RsaPublicKey("release-test", RSA_N)})
    assert verified.executable == executable

    executable.write_bytes(b"tampered")
    with pytest.raises(ValueError, match="hash mismatch"):
        verify_worker_package(tmp_path, manifest, {"release-test": RsaPublicKey("release-test", RSA_N)})


class FakeBackend:
    def __init__(self, losses=(1.0, 0.8, 0.81, 0.82), unsafe=False):
        self.losses = losses
        self.unsafe = unsafe
        self.frozen = {name: f"hash-{name}" for name in TrainingArtifactBundle.load(ARTIFACTS).frozen_parameters}

    def frozen_parameter_hashes(self):
        return self.frozen

    def train_epoch(self, epoch):
        correction = 10.0 if self.unsafe else 0.1
        return EpochMetrics(epoch, self.losses[epoch - 1], correction, 0.01, 0.01)

    def write_checkpoint(self, directory):
        (directory / "adapter.bin").write_bytes(b"checkpoint")


def test_guarded_training_keeps_backbone_atomic_checkpoint_and_early_stops(tmp_path):
    result = run_guarded_training(TrainingArtifactBundle.load(ARTIFACTS), FakeBackend(), _policy(), tmp_path / "checkpoint.zip")
    assert result.epochs_completed == 4
    assert result.stopped_early
    assert result.best_validation_loss == 0.8
    assert len(result.checkpoint_sha256) == 64
    assert (tmp_path / "checkpoint.zip").is_file()
    assert not list(tmp_path.glob("*.partial"))


def test_adapter_budget_and_correction_safety_are_blocking(tmp_path):
    bundle = TrainingArtifactBundle.load(ARTIFACTS)
    with pytest.raises(ValueError, match="parameter budget"):
        run_guarded_training(bundle, FakeBackend(), _policy(maximum_trainable_parameters=10), tmp_path / "small.zip")
    with pytest.raises(RuntimeError, match="magnitude safety"):
        run_guarded_training(bundle, FakeBackend(unsafe=True), _policy(), tmp_path / "unsafe.zip")
    assert not (tmp_path / "unsafe.zip").exists()


def test_checkpoint_size_failure_preserves_last_valid_archive(tmp_path):
    checkpoint = tmp_path / "checkpoint.zip"
    first = run_guarded_training(
        TrainingArtifactBundle.load(ARTIFACTS), FakeBackend(losses=(1.0,)),
        _policy(maximum_epochs=1), checkpoint,
    )
    original = checkpoint.read_bytes()

    class OversizedBackend(FakeBackend):
        def write_checkpoint(self, directory):
            (directory / "adapter.bin").write_bytes(b"x" * 4096)

    with pytest.raises(ValueError, match="size budget"):
        run_guarded_training(
            TrainingArtifactBundle.load(ARTIFACTS), OversizedBackend(losses=(0.5,)),
            _policy(maximum_epochs=1, maximum_checkpoint_bytes=100), checkpoint,
        )
    assert checkpoint.read_bytes() == original
    assert len(first.checkpoint_sha256) == 64


class FakeProbe:
    def probe(self, bundle, provider):
        return (provider == "CPU", "verified" if provider == "CPU" else "training backend unavailable")


def test_provider_reporting_keeps_inference_and_training_support_separate():
    statuses = probe_training_providers(
        TrainingArtifactBundle.load(ARTIFACTS), FakeProbe(),
        ["CPUExecutionProvider", "DmlExecutionProvider"],
    )
    by_name = {status.provider: status for status in statuses}
    assert by_name["CPU"].training_available
    assert by_name["DIRECTML"].inference_available
    assert not by_name["DIRECTML"].training_available
    assert select_training_provider("AUTO", statuses) == "CPU"
    with pytest.raises(RuntimeError, match="forced CUDA"):
        select_training_provider("CUDA", statuses)


def test_typed_ipc_and_portable_worker_cpu_baseline(tmp_path):
    hello = handle_message(IpcMessage("hello", "request-1", {}))
    assert IpcMessage.parse(hello.to_json()) == hello

    model = CompactCausalModel(ModelConfig(1, 2, 1, 2, 8, 2, 3, 0.5, 0.1), seed=4)
    base_checkpoint = tmp_path / "base.json"
    write_model_checkpoint(model, base_checkpoint)
    training_input = tmp_path / "training.json"
    training_input.write_text(json.dumps({
        "format": "nekovr-training-input-v1",
        "examples": [{
            "sample_id": "train-1", "split": "train", "features": [[[0.2]]],
            "role_ids": [1], "slot_mask": [True], "channel_validity": [[[True]]],
            "time_deltas_s": [0.02], "target_correction_rotation_vectors": [[0.02, 0.0, 0.0]],
            "target_confidence": [1.0], "loss_weights": [1.0],
        }],
    }), encoding="utf-8")
    checkpoint = tmp_path / "checkpoint.zip"
    response = handle_message(IpcMessage("train", "request-2", {
        "artifact_dir": str(ARTIFACTS), "provider": "CPU", "base_checkpoint": str(base_checkpoint),
        "training_input": str(training_input), "learning_rate": 0.01, "seed": 9,
        "checkpoint": str(checkpoint), "policy": asdict(_policy(maximum_epochs=2)),
    }))
    assert response.kind == "train_result"
    assert response.payload["provider"] == "CPU"
    assert checkpoint.is_file()


def test_ipc_rejects_unknown_fields_and_protocol_versions():
    with pytest.raises(ValueError, match="fields differ"):
        IpcMessage.parse('{"kind":"hello","request_id":"1","payload":{},"protocol_version":1,"extra":true}')
    with pytest.raises(ValueError, match="incompatible"):
        IpcMessage.parse('{"kind":"hello","request_id":"1","payload":{},"protocol_version":2}')


def test_worker_serves_typed_ipc_in_a_separate_process():
    request = IpcMessage("hello", "out-of-process", {}).to_json() + "\n"
    environment = dict(os.environ)
    source_root = str(Path(__file__).parents[1] / "src")
    environment["PYTHONPATH"] = source_root + os.pathsep + environment.get("PYTHONPATH", "")
    completed = subprocess.run(
        [sys.executable, "-m", "nekovr_ml.trainer_worker", "--ipc-stdio"],
        input=request, capture_output=True, text=True, encoding="utf-8", check=True, env=environment,
    )
    response = IpcMessage.parse(completed.stdout.strip())
    assert response.kind == "hello_result"
    assert response.request_id == "out-of-process"
