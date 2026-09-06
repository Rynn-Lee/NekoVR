from __future__ import annotations

from dataclasses import dataclass
import math
import random
from typing import Sequence


@dataclass(frozen=True)
class ModelConfig:
    feature_count: int
    hidden_size: int = 12
    temporal_layers: int = 3
    kernel_size: int = 3
    role_count: int = 64
    max_slots: int = 16
    output_axes: int = 3
    maximum_correction_radians: float = math.radians(30.0)
    maximum_drift_rate_radians_per_second: float = math.radians(5.0)

    def __post_init__(self) -> None:
        integers = (self.feature_count, self.hidden_size, self.temporal_layers, self.kernel_size, self.role_count, self.max_slots, self.output_axes)
        if any(value <= 0 for value in integers):
            raise ValueError("model dimensions must be positive")
        if self.maximum_correction_radians <= 0 or self.maximum_drift_rate_radians_per_second <= 0:
            raise ValueError("model output bounds must be positive")


@dataclass(frozen=True)
class SequenceSample:
    """One causal history with [time][slot][feature] values and explicit masks."""

    features: tuple[tuple[tuple[float, ...], ...], ...]
    role_ids: tuple[int, ...]
    slot_mask: tuple[bool, ...]
    channel_validity: tuple[tuple[tuple[bool, ...], ...], ...]
    time_deltas_s: tuple[float, ...]


@dataclass(frozen=True)
class ModelBatch:
    features: tuple[tuple[tuple[tuple[float, ...], ...], ...], ...]
    role_ids: tuple[tuple[int, ...], ...]
    slot_mask: tuple[tuple[bool, ...], ...]
    channel_validity: tuple[tuple[tuple[tuple[bool, ...], ...], ...], ...]
    time_deltas_s: tuple[tuple[float, ...], ...]
    time_mask: tuple[tuple[bool, ...], ...]


@dataclass(frozen=True)
class ModelOutput:
    correction_rotation_vectors: tuple[tuple[tuple[float, ...], ...], ...]
    confidence: tuple[tuple[float, ...], ...]
    drift_rate: tuple[tuple[float, ...], ...]


def _validate_sample(sample: SequenceSample, feature_count: int) -> None:
    if not sample.features or len(sample.features) != len(sample.channel_validity):
        raise ValueError("features and channel_validity need the same non-zero time length")
    if len(sample.time_deltas_s) != len(sample.features):
        raise ValueError("one time delta is required per history frame")
    slots = len(sample.role_ids)
    if slots == 0 or len(sample.slot_mask) != slots:
        raise ValueError("role_ids and slot_mask need the same non-zero slot length")
    for frame, validity in zip(sample.features, sample.channel_validity):
        if len(frame) != slots or len(validity) != slots:
            raise ValueError("all frames must have the declared slot length")
        if any(len(values) != feature_count for values in frame):
            raise ValueError("feature width differs from model contract")
        if any(len(values) != feature_count for values in validity):
            raise ValueError("channel-validity width differs from model contract")
    if any(role < 0 for role in sample.role_ids) or any(delta < 0 or not math.isfinite(delta) for delta in sample.time_deltas_s):
        raise ValueError("role IDs and finite time deltas must be non-negative")


def collate_variable_layout(samples: Sequence[SequenceSample], feature_count: int, max_slots: int) -> ModelBatch:
    if not samples:
        raise ValueError("cannot collate an empty batch")
    for sample in samples:
        _validate_sample(sample, feature_count)
        if len(sample.role_ids) > max_slots:
            raise ValueError("sample layout exceeds max_slots")
    max_time = max(len(sample.features) for sample in samples)
    batched_features = []
    batched_validity = []
    batched_roles = []
    batched_slots = []
    batched_deltas = []
    batched_time_mask = []
    zero_slot = tuple(0.0 for _ in range(feature_count))
    false_slot = tuple(False for _ in range(feature_count))
    for sample in samples:
        time_padding = max_time - len(sample.features)
        slot_padding = max_slots - len(sample.role_ids)
        padded_features = [tuple(zero_slot for _ in range(max_slots)) for _ in range(time_padding)]
        padded_validity = [tuple(false_slot for _ in range(max_slots)) for _ in range(time_padding)]
        for frame, validity in zip(sample.features, sample.channel_validity):
            padded_features.append(tuple(frame) + tuple(zero_slot for _ in range(slot_padding)))
            padded_validity.append(tuple(validity) + tuple(false_slot for _ in range(slot_padding)))
        batched_features.append(tuple(padded_features))
        batched_validity.append(tuple(padded_validity))
        batched_roles.append(sample.role_ids + (0,) * slot_padding)
        batched_slots.append(sample.slot_mask + (False,) * slot_padding)
        batched_deltas.append((0.0,) * time_padding + sample.time_deltas_s)
        batched_time_mask.append((False,) * time_padding + (True,) * len(sample.features))
    return ModelBatch(
        tuple(batched_features),
        tuple(batched_roles),
        tuple(batched_slots),
        tuple(batched_validity),
        tuple(batched_deltas),
        tuple(batched_time_mask),
    )


class CompactCausalModel:
    """Small shared-weight causal encoder suitable as the framework reference."""

    def __init__(self, config: ModelConfig, seed: int = 0):
        self.config = config
        rng = random.Random(seed)
        scale = 1.0 / math.sqrt(config.hidden_size)
        self.input_weights = self._matrix(rng, config.hidden_size, config.feature_count, scale)
        self.input_bias = self._vector(rng, config.hidden_size, scale)
        self.time_weights = self._vector(rng, config.hidden_size, scale)
        self.role_embeddings = self._matrix(rng, config.role_count, config.hidden_size, scale)
        self.depthwise_kernels = tuple(
            self._matrix(rng, config.hidden_size, config.kernel_size, scale)
            for _ in range(config.temporal_layers)
        )
        self.pointwise_weights = tuple(
            self._matrix(rng, config.hidden_size, config.hidden_size, scale)
            for _ in range(config.temporal_layers)
        )
        head_width = config.hidden_size * 2
        self.correction_head = self._matrix(rng, config.output_axes, head_width, scale)
        self.correction_bias = self._vector(rng, config.output_axes, scale)
        self.confidence_head = self._vector(rng, head_width, scale)
        self.confidence_bias = rng.uniform(-0.2, 0.2)
        self.drift_head = self._vector(rng, head_width, scale)
        self.drift_bias = rng.uniform(-0.2, 0.2)

    @staticmethod
    def _vector(rng: random.Random, size: int, scale: float) -> tuple[float, ...]:
        return tuple(rng.uniform(-scale, scale) for _ in range(size))

    @classmethod
    def _matrix(cls, rng: random.Random, rows: int, columns: int, scale: float) -> tuple[tuple[float, ...], ...]:
        return tuple(cls._vector(rng, columns, scale) for _ in range(rows))

    @staticmethod
    def _dot(left: Sequence[float], right: Sequence[float]) -> float:
        return sum(a * b for a, b in zip(left, right))

    @property
    def frozen_parameter_names(self) -> tuple[str, ...]:
        return ("input_weights", "input_bias", "time_weights", "role_embeddings", "depthwise_kernels", "pointwise_weights")

    @property
    def adapter_parameter_names(self) -> tuple[str, ...]:
        return ("correction_head", "correction_bias", "confidence_head", "confidence_bias", "drift_head", "drift_bias")

    def state_dict(self) -> dict[str, object]:
        return {name: getattr(self, name) for name in self.frozen_parameter_names + self.adapter_parameter_names}

    def encode(self, batch: ModelBatch) -> tuple[tuple[tuple[tuple[float, ...], ...], ...], ...]:
        cfg = self.config
        encoded_batches = []
        for batch_index, histories in enumerate(batch.features):
            encoded_time = []
            for time_index, slots in enumerate(histories):
                encoded_slots = []
                for slot_index, values in enumerate(slots):
                    if not batch.time_mask[batch_index][time_index] or not batch.slot_mask[batch_index][slot_index]:
                        encoded_slots.append((0.0,) * cfg.hidden_size)
                        continue
                    valid = batch.channel_validity[batch_index][time_index][slot_index]
                    masked = tuple(value if is_valid and math.isfinite(value) else 0.0 for value, is_valid in zip(values, valid))
                    role = batch.role_ids[batch_index][slot_index]
                    if role >= cfg.role_count:
                        raise ValueError(f"body-role ID {role} exceeds role_count")
                    delta = math.log1p(batch.time_deltas_s[batch_index][time_index])
                    hidden = tuple(math.tanh(
                        self._dot(weights, masked) + self.input_bias[index] + self.role_embeddings[role][index] + self.time_weights[index] * delta
                    ) for index, weights in enumerate(self.input_weights))
                    encoded_slots.append(hidden)
                encoded_time.append(tuple(encoded_slots))
            states = tuple(encoded_time)
            for layer in range(cfg.temporal_layers):
                next_time = []
                dilation = 2**layer
                for time_index in range(len(states)):
                    next_slots = []
                    for slot_index in range(cfg.max_slots):
                        if not batch.slot_mask[batch_index][slot_index] or not batch.time_mask[batch_index][time_index]:
                            next_slots.append((0.0,) * cfg.hidden_size)
                            continue
                        depth = []
                        for hidden_index in range(cfg.hidden_size):
                            value = 0.0
                            for kernel_index in range(cfg.kernel_size):
                                source_time = time_index - kernel_index * dilation
                                if source_time >= 0 and batch.time_mask[batch_index][source_time]:
                                    value += self.depthwise_kernels[layer][hidden_index][kernel_index] * states[source_time][slot_index][hidden_index]
                            depth.append(value)
                        mixed = tuple(math.tanh(states[time_index][slot_index][out] + self._dot(self.pointwise_weights[layer][out], depth)) for out in range(cfg.hidden_size))
                        next_slots.append(mixed)
                    next_time.append(tuple(next_slots))
                states = tuple(next_time)
            encoded_batches.append(states)
        return tuple(encoded_batches)

    def forward(self, batch: ModelBatch) -> ModelOutput:
        states = self.encode(batch)
        corrections, confidences, drift_rates = [], [], []
        for batch_index, history in enumerate(states):
            last_time = max(index for index, valid in enumerate(batch.time_mask[batch_index]) if valid)
            valid_slots = [
                slot for slot, valid in enumerate(batch.slot_mask[batch_index])
                if valid and any(batch.channel_validity[batch_index][last_time][slot])
            ]
            global_context = tuple(
                sum(history[last_time][slot][hidden] for slot in valid_slots) / len(valid_slots)
                if valid_slots else 0.0
                for hidden in range(self.config.hidden_size)
            )
            batch_corrections, batch_confidence, batch_drift = [], [], []
            for slot in range(self.config.max_slots):
                latest_channels_valid = any(batch.channel_validity[batch_index][last_time][slot])
                if not batch.slot_mask[batch_index][slot] or not latest_channels_valid:
                    batch_corrections.append((0.0,) * self.config.output_axes)
                    batch_confidence.append(0.0)
                    batch_drift.append(0.0)
                    continue
                head_input = history[last_time][slot] + global_context
                correction = tuple(
                    math.tanh(self._dot(weights, head_input) + self.correction_bias[axis]) * self.config.maximum_correction_radians
                    for axis, weights in enumerate(self.correction_head)
                )
                confidence = 1.0 / (1.0 + math.exp(-(self._dot(self.confidence_head, head_input) + self.confidence_bias)))
                drift = math.tanh(self._dot(self.drift_head, head_input) + self.drift_bias) * self.config.maximum_drift_rate_radians_per_second
                batch_corrections.append(correction)
                batch_confidence.append(confidence)
                batch_drift.append(drift)
            corrections.append(tuple(batch_corrections))
            confidences.append(tuple(batch_confidence))
            drift_rates.append(tuple(batch_drift))
        return ModelOutput(tuple(corrections), tuple(confidences), tuple(drift_rates))
