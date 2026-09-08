from __future__ import annotations

import argparse
from dataclasses import asdict
import json
import math
from pathlib import Path
import sys
import tempfile
from typing import Any, Mapping, Sequence

from .model import SequenceSample, collate_variable_layout
from .personal_trainer import (
    AtomicCheckpointArchive,
    EpochMetrics,
    IPC_PROTOCOL_VERSION,
    IpcMessage,
    OrtTrainingBackend,
    OrtTrainingRuntimeProbe,
    TrainerBackend,
    TrainingArtifactBundle,
    TrainingPolicy,
    execute_ort_training_probe,
    probe_training_providers,
    run_guarded_training,
    select_training_provider,
)
from .provenance import canonical_hash
from .training import TrainingExample, load_model_checkpoint, train_adapter_heads, write_model_checkpoint


def _training_examples(payload: Mapping[str, Any]) -> tuple[TrainingExample, ...]:
    if payload.get("format") != "nekovr-training-input-v1":
        raise ValueError("training input format must be nekovr-training-input-v1")
    examples = []
    for value in payload.get("examples", ()):
        sequence = SequenceSample(
            tuple(tuple(tuple(float(item) for item in slot) for slot in frame) for frame in value["features"]),
            tuple(int(item) for item in value["role_ids"]),
            tuple(bool(item) for item in value["slot_mask"]),
            tuple(tuple(tuple(bool(item) for item in slot) for slot in frame) for frame in value["channel_validity"]),
            tuple(float(item) for item in value["time_deltas_s"]),
        )
        examples.append(TrainingExample(
            str(value["sample_id"]), str(value.get("split", "train")), sequence,
            tuple(tuple(float(item) for item in slot) for slot in value["target_correction_rotation_vectors"]),
            tuple(float(item) for item in value["target_confidence"]),
            tuple(float(item) for item in value["loss_weights"]),
        ))
    training = tuple(example for example in examples if example.split == "train")
    if not training:
        raise ValueError("personal worker needs training examples from the train split")
    return training


class PortableCpuAdapterBackend(TrainerBackend):
    """Deterministic, portable CPU baseline; it never mutates shared-backbone parameters."""

    def __init__(self, checkpoint: str | Path, training_input: str | Path, learning_rate: float, seed: int):
        self.model = load_model_checkpoint(checkpoint)
        self.examples = _training_examples(json.loads(Path(training_input).read_text(encoding="utf-8")))
        self.learning_rate = learning_rate
        self.seed = seed

    def frozen_parameter_hashes(self) -> Mapping[str, str]:
        state = self.model.state_dict()
        return {name: canonical_hash(state[name]) for name in self.model.frozen_parameter_names}

    def train_epoch(self, epoch: int) -> EpochMetrics:
        result = train_adapter_heads(self.model, self.examples, 1, self.learning_rate, self.seed + epoch)
        maximum = 0.0
        clean_maximum = 0.0
        last_by_slot: dict[int, tuple[float, ...]] = {}
        jitter = 0.0
        for example in self.examples:
            output = self.model.forward(collate_variable_layout(
                [example.sequence], self.model.config.feature_count, self.model.config.max_slots,
            ))
            for slot, vector in enumerate(output.correction_rotation_vectors[0][:len(example.target_correction_rotation_vectors)]):
                magnitude = math.sqrt(sum(value * value for value in vector))
                maximum = max(maximum, magnitude)
                if all(abs(value) <= 1e-12 for value in example.target_correction_rotation_vectors[slot]):
                    clean_maximum = max(clean_maximum, magnitude)
                if slot in last_by_slot:
                    jitter = max(jitter, math.sqrt(sum((value - previous) ** 2 for value, previous in zip(vector, last_by_slot[slot]))))
                last_by_slot[slot] = vector
        return EpochMetrics(epoch, result.epoch_losses[-1], maximum, clean_maximum, jitter)

    def write_checkpoint(self, directory: Path) -> None:
        write_model_checkpoint(self.model, directory / "model-checkpoint.json")


class WorkerProviderProbe:
    def __init__(self, ort_probe: OrtTrainingRuntimeProbe):
        self.ort_probe = ort_probe

    def probe(self, bundle: TrainingArtifactBundle, provider: str) -> tuple[bool, str]:
        if provider == "CPU":
            return True, "portable frozen-backbone CPU adapter baseline passed"
        return self.ort_probe.probe(bundle, provider)


def _statuses(bundle: TrainingArtifactBundle):
    try:
        import onnxruntime as ort

        inference = ort.get_available_providers()
    except ImportError:
        inference = ()
    contract = bundle.manifest.get("ort_training_contract", {})
    probe = OrtTrainingRuntimeProbe(lambda artifacts, provider: execute_ort_training_probe(artifacts, provider, contract))
    return probe_training_providers(bundle, WorkerProviderProbe(probe), inference)


def _policy(value: Mapping[str, Any]) -> TrainingPolicy:
    return TrainingPolicy(
        maximum_trainable_parameters=int(value["maximum_trainable_parameters"]),
        maximum_trainable_fraction=float(value["maximum_trainable_fraction"]),
        maximum_checkpoint_bytes=int(value["maximum_checkpoint_bytes"]),
        maximum_epochs=int(value["maximum_epochs"]),
        early_stopping_patience=int(value["early_stopping_patience"]),
        early_stopping_min_delta=float(value["early_stopping_min_delta"]),
        maximum_correction_radians=float(value["maximum_correction_radians"]),
        maximum_false_correction_radians=float(value["maximum_false_correction_radians"]),
        maximum_jitter_radians=float(value["maximum_jitter_radians"]),
    )


def handle_message(message: IpcMessage) -> IpcMessage:
    if message.kind == "hello":
        return IpcMessage("hello_result", message.request_id, {"worker": "NekoVR Trainer", "protocol_version": IPC_PROTOCOL_VERSION})
    if message.kind == "probe":
        bundle = TrainingArtifactBundle.load(message.payload["artifact_dir"])
        return IpcMessage("probe_result", message.request_id, {"providers": [asdict(status) for status in _statuses(bundle)]})
    if message.kind == "train":
        bundle = TrainingArtifactBundle.load(message.payload["artifact_dir"])
        statuses = _statuses(bundle)
        provider = select_training_provider(str(message.payload.get("provider", "AUTO")), statuses)
        base_checkpoint = Path(message.payload["base_checkpoint"])
        resume = message.payload.get("resume_checkpoint")
        temporary: tempfile.TemporaryDirectory[str] | None = None
        try:
            extracted = None
            if resume:
                temporary = tempfile.TemporaryDirectory(prefix="nekovr-resume-")
                extracted = AtomicCheckpointArchive(resume).extract(temporary.name)
            if provider == "CPU":
                if extracted is not None:
                    base_checkpoint = extracted / "model-checkpoint.json"
                backend: TrainerBackend = PortableCpuAdapterBackend(
                    base_checkpoint, message.payload["training_input"],
                    float(message.payload["learning_rate"]), int(message.payload["seed"]),
                )
            else:
                backend = OrtTrainingBackend(
                    bundle, provider,
                    dict(message.payload["training_batches"]),
                    dict(message.payload["validation_batches"]),
                    extracted,
                )
            result = run_guarded_training(bundle, backend, _policy(message.payload["policy"]), message.payload["checkpoint"])
        finally:
            if temporary is not None:
                temporary.cleanup()
        return IpcMessage("train_result", message.request_id, {
            "provider": provider,
            "epochs_completed": result.epochs_completed,
            "best_validation_loss": result.best_validation_loss,
            "stopped_early": result.stopped_early,
            "checkpoint_sha256": result.checkpoint_sha256,
            "metrics": [asdict(value) for value in result.metrics],
        })
    raise ValueError(f"unsupported trainer IPC command: {message.kind}")


def serve(input_stream=sys.stdin, output_stream=sys.stdout) -> int:
    for line in input_stream:
        request_id = "invalid"
        try:
            message = IpcMessage.parse(line)
            request_id = message.request_id
            response = handle_message(message)
        except (KeyError, TypeError, ValueError, RuntimeError, OSError) as error:
            response = IpcMessage("error", request_id, {"code": type(error).__name__, "message": str(error)})
        output_stream.write(response.to_json() + "\n")
        output_stream.flush()
    return 0


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="nekovr-trainer-worker")
    parser.add_argument("--ipc-stdio", action="store_true", required=True)
    parser.parse_args(arguments)
    return serve()


if __name__ == "__main__":
    raise SystemExit(main())
