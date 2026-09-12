from __future__ import annotations

from dataclasses import asdict, dataclass
import json
from pathlib import Path
from typing import Any, Mapping, Sequence

from .model import CompactCausalModel
from .provenance import canonical_hash, file_sha256


@dataclass(frozen=True)
class BaseModelDescriptor:
    model_id: str
    model_sha256: str
    personalization_ready: bool
    parameter_shapes: Mapping[str, tuple[int, ...]]
    frozen_parameters: tuple[str, ...]
    adapter_parameters: tuple[str, ...]
    adapter_initial_values: Mapping[str, Any]
    supported_roles: tuple[str, ...]
    supported_layouts: tuple[int, ...]
    maximum_correction_radians: float
    model_config: Mapping[str, Any]
    parameter_values: Mapping[str, Any]

    def __post_init__(self) -> None:
        names = set(self.parameter_shapes)
        frozen, adapter = set(self.frozen_parameters), set(self.adapter_parameters)
        if len(self.model_sha256) != 64 or frozen & adapter or frozen | adapter != names:
            raise ValueError("descriptor must partition all parameters and provide a SHA-256 base hash")
        if self.personalization_ready and (not adapter or set(self.adapter_initial_values) != adapter):
            raise ValueError("personalization-ready model must declare adapter parameters")
        if set(self.parameter_values) != names:
            raise ValueError("personalization descriptor must carry every canonical parameter value")


def descriptor_from_model(model_id: str, model: CompactCausalModel, supported_roles: Sequence[str], supported_layouts: Sequence[int]) -> BaseModelDescriptor:
    cfg = model.config
    shapes = {
        "input_weights": (cfg.hidden_size, cfg.feature_count),
        "input_bias": (cfg.hidden_size,),
        "time_weights": (cfg.hidden_size,),
        "role_embeddings": (cfg.role_count, cfg.hidden_size),
        "depthwise_kernels": (cfg.temporal_layers, cfg.hidden_size, cfg.kernel_size),
        "pointwise_weights": (cfg.temporal_layers, cfg.hidden_size, cfg.hidden_size),
        "correction_head": (cfg.output_axes, cfg.hidden_size * 2),
        "correction_bias": (cfg.output_axes,),
        "confidence_head": (cfg.hidden_size * 2,),
        "confidence_bias": (1,),
        "drift_head": (cfg.hidden_size * 2,),
        "drift_bias": (1,),
    }
    parameters = model.state_dict()
    model_config = asdict(cfg)
    identity = {"format": "nekovr-framework-checkpoint-v1", "config": model_config, "parameters": parameters}
    return BaseModelDescriptor(
        model_id=model_id,
        model_sha256=canonical_hash(identity),
        personalization_ready=True,
        parameter_shapes=shapes,
        frozen_parameters=model.frozen_parameter_names,
        adapter_parameters=model.adapter_parameter_names,
        adapter_initial_values={name: parameters[name] for name in model.adapter_parameter_names},
        supported_roles=tuple(supported_roles),
        supported_layouts=tuple(supported_layouts),
        maximum_correction_radians=cfg.maximum_correction_radians,
        model_config=model_config,
        parameter_values=parameters,
    )


def _parameter_count(shape: Sequence[int]) -> int:
    count = 1
    for dimension in shape:
        count *= dimension
    return count


def _write_json(path: Path, value: Any) -> None:
    temporary = path.with_suffix(path.suffix + ".partial")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def generate_personalization_artifacts(descriptor: BaseModelDescriptor, output_root: str | Path) -> Path:
    if not descriptor.personalization_ready:
        raise ValueError(f"base model {descriptor.model_id} is not personalization-ready")
    root = Path(output_root) / descriptor.model_id
    root.mkdir(parents=True, exist_ok=True)
    trainable_count = sum(_parameter_count(descriptor.parameter_shapes[name]) for name in descriptor.adapter_parameters)
    frozen_count = sum(_parameter_count(descriptor.parameter_shapes[name]) for name in descriptor.frozen_parameters)
    training = {
        "format": "nekovr-adapter-training-graph-v1",
        "base_model_sha256": descriptor.model_sha256,
        "requires_grad": {name: name in descriptor.adapter_parameters for name in descriptor.parameter_shapes},
        "inputs": ["features", "role_ids", "slot_mask", "channel_validity", "time_deltas", "targets", "loss_masks"],
        "outputs": ["total_loss", "correction_loss", "confidence_loss", "adapter_gradients"],
        "maximum_correction_radians": descriptor.maximum_correction_radians,
        "worker_backend": "portable_cpu_adapter_v1",
    }
    evaluation = {
        "format": "nekovr-adapter-evaluation-graph-v1",
        "base_model_sha256": descriptor.model_sha256,
        "inputs": ["features", "role_ids", "slot_mask", "channel_validity", "time_deltas"],
        "outputs": ["correction", "confidence", "drift_rate"],
        "gradient_outputs": [],
        "worker_backend": "portable_cpu_adapter_v1",
    }
    optimizer = {
        "format": "nekovr-adapter-optimizer-v1",
        "algorithm": "adamw",
        "trainable_parameters": list(descriptor.adapter_parameters),
        "frozen_parameters": list(descriptor.frozen_parameters),
        "learning_rate": 0.001,
        "weight_decay": 0.0001,
        "gradient_clip_norm": 1.0,
    }
    checkpoint = {
        "format": "nekovr-nominal-adapter-checkpoint-v1",
        "base_model_sha256": descriptor.model_sha256,
        "step": 0,
        "parameters": {
            name: {"shape": list(descriptor.parameter_shapes[name]), "values": descriptor.adapter_initial_values[name]}
            for name in descriptor.adapter_parameters
        },
        "optimizer_state": "zero_initialized",
    }
    base_checkpoint = {
        "format": "nekovr-framework-checkpoint-v1",
        "config": descriptor.model_config,
        "parameters": descriptor.parameter_values,
    }
    feature_count = int(descriptor.model_config["feature_count"])
    probe_input = {
        "format": "nekovr-training-input-v1",
        "examples": [{
            "sample_id": "personalization-probe", "split": "train",
            "features": [[[0.0] * feature_count], [[0.01] * feature_count]],
            "role_ids": [1], "slot_mask": [True],
            "channel_validity": [[[True] * feature_count], [[True] * feature_count]],
            "time_deltas_s": [0.02, 0.02],
            "target_correction_rotation_vectors": [[0.0, 0.0, 0.0]],
            "target_confidence": [1.0], "loss_weights": [1.0],
        }],
    }
    worker_request = {
        "format": "nekovr-portable-adapter-worker-request-v1",
        "base_model_sha256": descriptor.model_sha256,
        "checkpoint": "base_model_checkpoint.json",
        "training_input": "probe-training-input.json",
        "training_graph": "training_graph.json",
        "evaluation_graph": "evaluation_graph.json",
        "optimizer": "optimizer.json",
        "nominal_checkpoint": "nominal_checkpoint.json",
    }
    files = {
        "training_graph.json": training,
        "evaluation_graph.json": evaluation,
        "optimizer.json": optimizer,
        "nominal_checkpoint.json": checkpoint,
        "base_model_checkpoint.json": base_checkpoint,
        "probe-training-input.json": probe_input,
        "worker-request.json": worker_request,
    }
    for name, value in files.items():
        _write_json(root / name, value)
    hashes = {name: file_sha256(root / name) for name in files}
    manifest = {
        "schema_version": 1,
        "model_id": descriptor.model_id,
        "base_model_sha256": descriptor.model_sha256,
        "base_identity_kind": "canonical-config-and-parameter-values",
        "personalization_ready": True,
        "frozen_parameters": list(descriptor.frozen_parameters),
        "trainable_parameters": list(descriptor.adapter_parameters),
        "frozen_parameter_count": frozen_count,
        "trainable_parameter_count": trainable_count,
        "supported_roles": list(descriptor.supported_roles),
        "supported_layouts": list(descriptor.supported_layouts),
        "resource_expectations": {"minimum_ram_mib": 256, "recommended_cpu_threads": 2, "maximum_checkpoint_mib": 8},
        "artifact_sha256": hashes,
        "portable_worker_request": "worker-request.json",
    }
    _write_json(root / "manifest.json", manifest)
    return root


def generate_all_personalization_artifacts(descriptors: Sequence[BaseModelDescriptor], output_root: str | Path) -> tuple[Path, ...]:
    ready = [descriptor for descriptor in descriptors if descriptor.personalization_ready]
    paths = tuple(generate_personalization_artifacts(descriptor, output_root) for descriptor in ready)
    if len(paths) != len(ready):
        raise AssertionError("not every personalization-ready model received artifacts")
    return paths


def verify_personalization_artifacts(root: str | Path) -> dict[str, Any]:
    directory = Path(root)
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    for name, expected in manifest["artifact_sha256"].items():
        if file_sha256(directory / name) != expected:
            raise ValueError(f"personalization artifact hash mismatch: {name}")
    training = json.loads((directory / "training_graph.json").read_text(encoding="utf-8"))
    trainable = set(manifest["trainable_parameters"])
    actual = {name for name, enabled in training["requires_grad"].items() if enabled}
    if actual != trainable or actual & set(manifest["frozen_parameters"]):
        raise ValueError("training graph does not enforce frozen-backbone adapter-only gradients")
    base = json.loads((directory / "base_model_checkpoint.json").read_text(encoding="utf-8"))
    if canonical_hash(base) != manifest["base_model_sha256"]:
        raise ValueError("personalization base identity differs from canonical parameter values")
    request = json.loads((directory / manifest["portable_worker_request"]).read_text(encoding="utf-8"))
    if request.get("base_model_sha256") != manifest["base_model_sha256"]:
        raise ValueError("portable worker request is bound to another base model")
    return manifest


def execute_portable_personalization_probe(root: str | Path) -> dict[str, Any]:
    """Load the generated bundle through the production portable worker backend and execute one step."""
    from .personal_trainer import TrainingArtifactBundle
    from .trainer_worker import PortableCpuAdapterBackend

    directory = Path(root)
    manifest = verify_personalization_artifacts(directory)
    bundle = TrainingArtifactBundle.load(directory)
    request = json.loads((directory / manifest["portable_worker_request"]).read_text(encoding="utf-8"))
    backend = PortableCpuAdapterBackend(
        directory / request["checkpoint"], directory / request["training_input"], 0.001, 0,
    )
    frozen_before = dict(backend.frozen_parameter_hashes())
    result = backend.train_epoch(0)
    if frozen_before != dict(backend.frozen_parameter_hashes()):
        raise AssertionError("portable personalization probe mutated the global backbone")
    return {
        "format": "nekovr-portable-personalization-probe-v1",
        "base_model_sha256": manifest["base_model_sha256"],
        "validation_loss": result.validation_loss,
        "trainable_parameter_count": bundle.trainable_parameter_count,
        "frozen_parameter_count": bundle.frozen_parameter_count,
        "passed": True,
    }
