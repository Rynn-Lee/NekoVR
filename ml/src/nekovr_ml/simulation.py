from __future__ import annotations

from dataclasses import dataclass
import math
import random
from typing import Sequence

from .layouts import VirtualLayout
from .math3d import IDENTITY, Quat, axis_angle_to_quat, correction, multiply


@dataclass(frozen=True)
class SimulationConfig:
    mounting_max_degrees: float = 25.0
    orientation_noise_degrees: float = 0.35
    bias_degrees_per_second: float = 0.025
    random_walk_degrees_per_sqrt_second: float = 0.015
    temperature_drift_degrees_per_celsius: float = 0.01
    latency_frames_min: int = 0
    latency_frames_max: int = 3
    packet_loss_probability: float = 0.01
    stale_probability: float = 0.01
    channel_unavailable_probability: float = 0.05
    reset_probability_per_second: float = 0.002

    def __post_init__(self) -> None:
        non_negative = (
            "mounting_max_degrees",
            "orientation_noise_degrees",
            "bias_degrees_per_second",
            "random_walk_degrees_per_sqrt_second",
            "temperature_drift_degrees_per_celsius",
            "reset_probability_per_second",
        )
        if any(float(getattr(self, name)) < 0.0 for name in non_negative):
            raise ValueError("simulation magnitudes and reset rate must be non-negative")
        probabilities = ("packet_loss_probability", "stale_probability", "channel_unavailable_probability")
        if any(not 0.0 <= float(getattr(self, name)) <= 1.0 for name in probabilities):
            raise ValueError("simulation probabilities must be between zero and one")
        if self.latency_frames_min < 0 or self.latency_frames_max < self.latency_frames_min:
            raise ValueError("latency frame bounds must be ordered and non-negative")

    @classmethod
    def from_mapping(cls, values: dict) -> "SimulationConfig":
        return cls(**{name: values[name] for name in cls.__dataclass_fields__ if name in values})


@dataclass(frozen=True)
class SimulatedFrame:
    time_s: float
    observed_xyzw: tuple[Quat, ...]
    correction_target_xyzw: tuple[Quat, ...]
    slot_mask: tuple[bool, ...]
    channel_valid_mask: tuple[bool, ...]
    stale_mask: tuple[bool, ...]
    temperature_c: tuple[float, ...]
    reset_mask: tuple[bool, ...]


def _random_rotation(rng: random.Random, maximum_degrees: float) -> Quat:
    if maximum_degrees <= 0.0:
        return IDENTITY
    axis = [rng.gauss(0.0, 1.0) for _ in range(3)]
    length = math.sqrt(sum(v * v for v in axis)) or 1.0
    angle = math.radians(rng.uniform(-maximum_degrees, maximum_degrees))
    return axis_angle_to_quat(v * angle / length for v in axis)


def simulate(layout: VirtualLayout, config: SimulationConfig, seed: int) -> tuple[SimulatedFrame, ...]:
    """Apply deterministic independent sensor corruptions and generate inverse targets."""
    rng = random.Random(seed)
    slots = len(layout.slot_mask)
    unavailable = [layout.slot_mask[i] and rng.random() < config.channel_unavailable_probability for i in range(slots)]
    mounting = [_random_rotation(rng, config.mounting_max_degrees) if layout.slot_mask[i] else IDENTITY for i in range(slots)]
    bias_rate = [math.radians(rng.uniform(-config.bias_degrees_per_second, config.bias_degrees_per_second)) for _ in range(slots)]
    bias_angle = [0.0] * slots
    walk = [0.0] * slots
    queues: list[list[Quat]] = [[] for _ in range(slots)]
    latency = [rng.randint(config.latency_frames_min, config.latency_frames_max) for _ in range(slots)]
    previous = [IDENTITY] * slots
    output = []
    previous_time = 0.0
    for source in layout.frames:
        dt = max(0.0, source.time_s - previous_time)
        previous_time = source.time_s
        observed, targets, valid, stale, resets, temperatures = [], [], [], [], [], []
        for slot, target in enumerate(source.orientations_xyzw):
            active = layout.slot_mask[slot]
            temperature = 25.0 + 5.0 * math.sin(source.time_s / 60.0 + slot)
            temperatures.append(temperature)
            reset_probability = 1.0 - math.exp(-config.reset_probability_per_second * dt)
            did_reset = active and rng.random() < reset_probability
            if did_reset:
                walk[slot] = 0.0
                bias_angle[slot] = 0.0
                bias_rate[slot] = math.radians(rng.uniform(-config.bias_degrees_per_second, config.bias_degrees_per_second))
            bias_angle[slot] += bias_rate[slot] * dt
            walk[slot] += math.radians(config.random_walk_degrees_per_sqrt_second) * math.sqrt(dt) * rng.gauss(0.0, 1.0)
            drift = bias_angle[slot] + walk[slot] + math.radians(config.temperature_drift_degrees_per_celsius) * (temperature - 25.0)
            drift_q = axis_angle_to_quat((0.0, drift, 0.0))
            noisy = multiply(multiply(target, mounting[slot]), drift_q)
            noisy = multiply(noisy, _random_rotation(rng, config.orientation_noise_degrees))
            queues[slot].append(noisy)
            if len(queues[slot]) > latency[slot] + 1:
                queues[slot].pop(0)
            delayed = queues[slot][0]
            lost = active and rng.random() < config.packet_loss_probability
            is_stale = active and (lost or rng.random() < config.stale_probability)
            if is_stale:
                delayed = previous[slot]
            else:
                previous[slot] = delayed
            is_valid = active and not unavailable[slot] and not is_stale
            observed.append(delayed if active else IDENTITY)
            targets.append(correction(delayed, target) if is_valid else IDENTITY)
            valid.append(is_valid)
            stale.append(is_stale)
            resets.append(did_reset)
        output.append(SimulatedFrame(source.time_s, tuple(observed), tuple(targets), layout.slot_mask, tuple(valid), tuple(stale), tuple(temperatures), tuple(resets)))
    return tuple(output)
