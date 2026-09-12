from __future__ import annotations

from dataclasses import asdict, dataclass
import json
import math
from pathlib import Path
import random
from typing import Any, Mapping, Sequence

from .losses import AXIS_PITCH, AXIS_ROLL, AXIS_YAW, Domain, LossMask, LossWeights, SupervisionExample, TargetProvenance, build_loss_mask
from .model import CompactCausalModel, ModelConfig, SequenceSample, collate_variable_layout


@dataclass(frozen=True)
class TrainingExample:
    sample_id: str
    split: str
    sequence: SequenceSample
    target_correction_rotation_vectors: tuple[tuple[float, ...], ...]
    target_confidence: tuple[float, ...]
    loss_weights: tuple[float, ...]
    loss_mask: LossMask | None = None
    metadata: Mapping[str, Any] | None = None


@dataclass(frozen=True)
class TrainingResult:
    epoch_losses: tuple[float, ...]
    updated_parameters: tuple[str, ...]
    loss_summary: Mapping[str, float] | None = None


def training_example_from_mapping(value: Mapping[str, Any], loss_values: Mapping[str, Any] | None = None) -> TrainingExample:
    sequence = SequenceSample(
        tuple(tuple(tuple(float(x) for x in slot) for slot in frame) for frame in value["features"]),
        tuple(int(x) for x in value["role_ids"]), tuple(bool(x) for x in value["slot_mask"]),
        tuple(tuple(tuple(bool(x) for x in slot) for slot in frame) for frame in value["channel_validity"]),
        tuple(float(x) for x in value["time_deltas_s"]),
    )
    masks = value.get("masks", {})
    quality = value.get("quality_decision", {})
    domain = Domain(str(value["domain"]))
    axis_mask = int(masks.get("axis_mask", AXIS_ROLL | AXIS_PITCH | AXIS_YAW))
    provenance = TargetProvenance.SYNTHETIC if domain is Domain.SYNTHETIC else TargetProvenance.HUMAN_RESET
    reset_domain = "YAW" if domain is Domain.REAL and axis_mask == AXIS_YAW else None
    configured = loss_values or {}
    weights = LossWeights(
        synthetic_dense=float(configured.get("synthetic_dense", 1.0)),
        real_reset=float(configured.get("real_reset", 2.0)),
        temporal=float(configured.get("temporal", 0.2)),
        self_supervised=float(configured.get("self_supervised", 0.2)),
        domain=float(configured.get("domain", 0.1)),
        quality_downweight=float(configured.get("quality_downweight", 0.25)),
        exclude_quality_flags=tuple(int(item) for item in configured.get("exclude_quality_flags", (64, 128))),
    )
    loss_mask = build_loss_mask(SupervisionExample(
        domain, axis_mask, provenance,
        reset_domain=reset_domain, quality_flags=int(masks.get("quality_flags", 0)),
        training_policy=str(quality.get("policy", "INCLUDE")),
        temporal_valid=len(sequence.features) > 1,
        self_supervised_valid=any(any(slot) for frame in sequence.channel_validity for slot in frame),
    ), weights)
    return TrainingExample(
        str(value["sample_id"]), str(value["split"]), sequence,
        tuple(tuple(float(x) for x in slot) for slot in value["target_correction_rotation_vectors"]),
        tuple(float(x) for x in value["target_confidence"]),
        tuple(float(x) for x in value["loss_weights"]), loss_mask, dict(value),
    )


def _head_inputs(model: CompactCausalModel, example: TrainingExample) -> tuple[tuple[float, ...] | None, ...]:
    batch = collate_variable_layout([example.sequence], model.config.feature_count, model.config.max_slots)
    states = model.encode(batch)[0]
    last_time = max(index for index, valid in enumerate(batch.time_mask[0]) if valid)
    valid_slots = [
        slot for slot, valid in enumerate(batch.slot_mask[0])
        if valid and any(
            channel_valid and math.isfinite(value)
            for value, channel_valid in zip(batch.features[0][last_time][slot], batch.channel_validity[0][last_time][slot])
        )
    ]
    global_context = tuple(sum(states[last_time][slot][hidden] for slot in valid_slots) / len(valid_slots) if valid_slots else 0.0 for hidden in range(model.config.hidden_size))
    return tuple(
        states[last_time][slot] + global_context
        if batch.slot_mask[0][slot] and any(
            channel_valid and math.isfinite(value)
            for value, channel_valid in zip(batch.features[0][last_time][slot], batch.channel_validity[0][last_time][slot])
        )
        else None
        for slot in range(model.config.max_slots)
    )


def train_adapter_heads(
    model: CompactCausalModel,
    examples: Sequence[TrainingExample],
    epochs: int,
    learning_rate: float,
    seed: int,
) -> TrainingResult:
    """Deterministic SGD for bounded correction/confidence heads; backbone stays frozen."""
    if epochs <= 0 or learning_rate <= 0 or not examples:
        raise ValueError("training needs examples and positive epochs/learning_rate")
    correction_head = [list(row) for row in model.correction_head]
    correction_bias = list(model.correction_bias)
    confidence_head = list(model.confidence_head)
    confidence_bias = float(model.confidence_bias)
    rng = random.Random(seed)
    losses = []
    for _ in range(epochs):
        order = list(range(len(examples)))
        rng.shuffle(order)
        epoch_loss = 0.0
        weight_sum = 0.0
        for example_index in order:
            example = examples[example_index]
            if not (len(example.target_correction_rotation_vectors) == len(example.target_confidence) == len(example.loss_weights) == len(example.sequence.slot_mask)):
                raise ValueError("training target slot widths differ")
            inputs = _head_inputs(model, example)
            for slot, head_input in enumerate(inputs[:len(example.sequence.slot_mask)]):
                weight = float(example.loss_weights[slot])
                if head_input is None or weight <= 0.0:
                    continue
                target_vector = example.target_correction_rotation_vectors[slot]
                if len(target_vector) != model.config.output_axes:
                    raise ValueError("correction target axis width differs from model")
                for axis in range(model.config.output_axes):
                    activation = sum(value * coefficient for value, coefficient in zip(head_input, correction_head[axis])) + correction_bias[axis]
                    tanh_value = math.tanh(activation)
                    predicted = tanh_value * model.config.maximum_correction_radians
                    error = predicted - float(target_vector[axis])
                    epoch_loss += weight * error * error
                    gradient = 2.0 * weight * error * model.config.maximum_correction_radians * (1.0 - tanh_value * tanh_value)
                    for index, value in enumerate(head_input):
                        correction_head[axis][index] -= learning_rate * gradient * value
                    correction_bias[axis] -= learning_rate * gradient
                target_confidence = max(0.0, min(1.0, float(example.target_confidence[slot])))
                logit = sum(value * coefficient for value, coefficient in zip(head_input, confidence_head)) + confidence_bias
                predicted_confidence = 1.0 / (1.0 + math.exp(-max(-40.0, min(40.0, logit))))
                confidence_error = predicted_confidence - target_confidence
                epoch_loss += weight * -(target_confidence * math.log(max(predicted_confidence, 1e-8)) + (1.0 - target_confidence) * math.log(max(1.0 - predicted_confidence, 1e-8)))
                for index, value in enumerate(head_input):
                    confidence_head[index] -= learning_rate * weight * confidence_error * value
                confidence_bias -= learning_rate * weight * confidence_error
                weight_sum += weight
        losses.append(epoch_loss / max(weight_sum, 1.0))
    model.correction_head = tuple(tuple(row) for row in correction_head)
    model.correction_bias = tuple(correction_bias)
    model.confidence_head = tuple(confidence_head)
    model.confidence_bias = confidence_bias
    return TrainingResult(tuple(losses), ("correction_head", "correction_bias", "confidence_head", "confidence_bias"))


def _prefix(sequence: SequenceSample, end: int) -> SequenceSample:
    return SequenceSample(
        sequence.features[:end], sequence.role_ids, sequence.slot_mask,
        sequence.channel_validity[:end], sequence.time_deltas_s[:end],
    )


def global_loss(model: CompactCausalModel, examples: Sequence[TrainingExample]) -> tuple[float, dict[str, float]]:
    """Evaluate every declared global objective against actual temporal model outputs."""
    totals = {name: 0.0 for name in ("dense_synthetic", "sparse_real_reset", "temporal", "self_supervised", "domain", "confidence")}
    weights = {name: 0.0 for name in totals}
    for example in examples:
        mask = example.loss_mask
        if mask is None:
            raise ValueError("global training examples require an executable loss mask")
        batch = collate_variable_layout([example.sequence], model.config.feature_count, model.config.max_slots)
        output = model.forward(batch)
        previous = None
        temporal_deltas: list[float] = []
        if mask.temporal > 0 and len(example.sequence.features) > 1:
            for end in range(1, len(example.sequence.features) + 1):
                prefix_output = model.forward(collate_variable_layout([_prefix(example.sequence, end)], model.config.feature_count, model.config.max_slots))
                current = prefix_output.correction_rotation_vectors[0]
                if previous is not None:
                    temporal_deltas.extend((a - b) ** 2 for left, right in zip(previous, current) for a, b in zip(left, right))
                previous = current
        for slot, active in enumerate(example.sequence.slot_mask):
            slot_weight = float(example.loss_weights[slot]) if slot < len(example.loss_weights) else 0.0
            if not active:
                continue
            predicted = output.correction_rotation_vectors[0][slot]
            target = example.target_correction_rotation_vectors[slot]
            for axis, axis_weight in enumerate(mask.axes):
                if axis_weight <= 0:
                    continue
                error = predicted[axis] - float(target[axis])
                if mask.dense_correction > 0:
                    value = mask.dense_correction * slot_weight * axis_weight
                    totals["dense_synthetic"] += value * error * error
                    weights["dense_synthetic"] += value
                if mask.sparse_reset > 0:
                    value = mask.sparse_reset * slot_weight * axis_weight
                    totals["sparse_real_reset"] += value * error * error
                    weights["sparse_real_reset"] += value
            supervised = (mask.dense_correction + mask.sparse_reset) * slot_weight
            if supervised > 0:
                confidence = min(1.0 - 1e-8, max(1e-8, output.confidence[0][slot]))
                target_confidence = min(1.0, max(0.0, float(example.target_confidence[slot])))
                totals["confidence"] += supervised * -(target_confidence * math.log(confidence) + (1 - target_confidence) * math.log(1 - confidence))
                weights["confidence"] += supervised
            if mask.self_supervised > 0:
                magnitude = sum(value * value for value in predicted)
                totals["self_supervised"] += mask.self_supervised * mask.quality_weight * magnitude
                weights["self_supervised"] += mask.self_supervised * mask.quality_weight
            if mask.domain > 0:
                domain = str((example.metadata or {}).get("domain", "synthetic"))
                target_drift = model.config.maximum_drift_rate_radians_per_second * (1.0 if domain == "synthetic" else -1.0)
                totals["domain"] += mask.domain * (output.drift_rate[0][slot] - target_drift) ** 2
                weights["domain"] += mask.domain
        if temporal_deltas:
            totals["temporal"] += mask.temporal * mask.quality_weight * sum(temporal_deltas) / len(temporal_deltas)
            weights["temporal"] += mask.temporal * mask.quality_weight
    normalized = {name: totals[name] / max(weights[name], 1.0) for name in totals}
    normalized["total"] = sum(normalized.values())
    normalized.update({f"active_{name}": weights[name] for name in weights})
    return normalized["total"], normalized


def _map_parameter(value: object, transform, path: tuple[int, ...] = ()) -> object:
    if isinstance(value, tuple):
        return tuple(_map_parameter(item, transform, path + (index,)) for index, item in enumerate(value))
    return transform(float(value), path)


def train_global_model(
    model: CompactCausalModel,
    examples: Sequence[TrainingExample],
    epochs: int,
    learning_rate: float,
    seed: int,
) -> TrainingResult:
    """Deterministic simultaneous-perturbation SGD over the encoder and all global heads."""
    training = tuple(example for example in examples if example.split == "train")
    if epochs <= 0 or learning_rate <= 0 or not training:
        raise ValueError("global training needs train examples and positive epochs/learning_rate")
    names = model.frozen_parameter_names + model.adapter_parameter_names
    rng = random.Random(seed)
    losses: list[float] = []
    last_summary: dict[str, float] = {}
    epsilon = 1e-3
    for _ in range(epochs):
        originals = {name: getattr(model, name) for name in names}
        deltas = {
            name: _map_parameter(value, lambda _number, _path: 1.0 if rng.random() >= 0.5 else -1.0)
            for name, value in originals.items()
        }
        for sign in (1.0, -1.0):
            for name in names:
                delta = deltas[name]
                setattr(model, name, _map_parameter(originals[name], lambda number, path, d=delta: number + sign * epsilon * _at(d, path)))
            value, _ = global_loss(model, training)
            if sign > 0:
                plus = value
            else:
                minus = value
        gradient_scale = (plus - minus) / (2.0 * epsilon)
        parameter_count = sum(_parameter_count(value) for value in originals.values())
        step = learning_rate / math.sqrt(parameter_count)
        original_loss, _ = _loss_with_parameters(model, originals, training)
        accepted = False
        for _attempt in range(8):
            for name in names:
                delta = deltas[name]
                setattr(model, name, _map_parameter(originals[name], lambda number, path, d=delta: max(-5.0, min(5.0, number - step * gradient_scale * _at(d, path)))))
            candidate_loss, candidate_summary = global_loss(model, training)
            if candidate_loss <= original_loss or step <= 1e-12:
                accepted = True
                break
            step *= 0.5
        if not accepted:
            for name, value in originals.items():
                setattr(model, name, value)
            candidate_loss, candidate_summary = global_loss(model, training)
        losses.append(candidate_loss)
        last_summary = candidate_summary
    return TrainingResult(tuple(losses), names, last_summary)


def _at(value: object, path: tuple[int, ...]) -> float:
    for index in path:
        value = value[index]  # type: ignore[index]
    return float(value)


def _parameter_count(value: object) -> int:
    return sum(_parameter_count(item) for item in value) if isinstance(value, tuple) else 1


def _loss_with_parameters(
    model: CompactCausalModel, parameters: Mapping[str, object], examples: Sequence[TrainingExample]
) -> tuple[float, dict[str, float]]:
    for name, value in parameters.items():
        setattr(model, name, value)
    return global_loss(model, examples)


def write_model_checkpoint(model: CompactCausalModel, output: str | Path) -> None:
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".partial")
    temporary.write_text(json.dumps({"format": "nekovr-framework-checkpoint-v1", "config": asdict(model.config), "parameters": model.state_dict()}, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
    temporary.replace(target)


def load_model_checkpoint(path: str | Path) -> CompactCausalModel:
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    if payload.get("format") != "nekovr-framework-checkpoint-v1":
        raise ValueError("unsupported framework checkpoint format")
    try:
        model = CompactCausalModel(ModelConfig(**payload["config"]))
        parameters = payload["parameters"]
    except (KeyError, TypeError) as error:
        raise ValueError(f"invalid framework checkpoint: {error}") from error
    expected = set(model.frozen_parameter_names + model.adapter_parameter_names)
    if set(parameters) != expected:
        raise ValueError("framework checkpoint parameter names differ from model contract")

    def tuples(value: object) -> object:
        return tuple(tuples(item) for item in value) if isinstance(value, list) else value

    for name in sorted(expected):
        setattr(model, name, tuples(parameters[name]))
    return model
