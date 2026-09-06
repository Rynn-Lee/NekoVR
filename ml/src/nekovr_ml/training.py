from __future__ import annotations

from dataclasses import asdict, dataclass
import json
import math
from pathlib import Path
import random
from typing import Sequence

from .model import CompactCausalModel, SequenceSample, collate_variable_layout


@dataclass(frozen=True)
class TrainingExample:
    sample_id: str
    split: str
    sequence: SequenceSample
    target_correction_rotation_vectors: tuple[tuple[float, ...], ...]
    target_confidence: tuple[float, ...]
    loss_weights: tuple[float, ...]


@dataclass(frozen=True)
class TrainingResult:
    epoch_losses: tuple[float, ...]
    updated_parameters: tuple[str, ...]


def _head_inputs(model: CompactCausalModel, example: TrainingExample) -> tuple[tuple[float, ...] | None, ...]:
    batch = collate_variable_layout([example.sequence], model.config.feature_count, model.config.max_slots)
    states = model.encode(batch)[0]
    last_time = max(index for index, valid in enumerate(batch.time_mask[0]) if valid)
    valid_slots = [slot for slot, valid in enumerate(batch.slot_mask[0]) if valid and any(batch.channel_validity[0][last_time][slot])]
    global_context = tuple(sum(states[last_time][slot][hidden] for slot in valid_slots) / len(valid_slots) if valid_slots else 0.0 for hidden in range(model.config.hidden_size))
    return tuple(
        states[last_time][slot] + global_context
        if batch.slot_mask[0][slot] and any(batch.channel_validity[0][last_time][slot])
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


def write_model_checkpoint(model: CompactCausalModel, output: str | Path) -> None:
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".partial")
    temporary.write_text(json.dumps({"format": "nekovr-framework-checkpoint-v1", "config": asdict(model.config), "parameters": model.state_dict()}, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
    temporary.replace(target)
