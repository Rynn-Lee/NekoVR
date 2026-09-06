from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


AXIS_YAW = 1
AXIS_PITCH = 2
AXIS_ROLL = 4
AXIS_MOUNTING = 8


class Domain(str, Enum):
    SYNTHETIC = "synthetic"
    REAL = "real"


class TargetProvenance(str, Enum):
    SYNTHETIC = "synthetic"
    HUMAN_RESET = "human_reset"
    EXTERNAL_REFERENCE = "external_reference"
    GLOBAL_TEACHER = "global_teacher"
    MODEL_OUTPUT = "model_output"
    FINAL_OUTPUT = "final_output"


@dataclass(frozen=True)
class SupervisionExample:
    domain: Domain
    axis_mask: int
    target_provenance: TargetProvenance
    observation_source: str = "pre_ai"
    reset_domain: str | None = None
    quality_flags: int = 0
    training_policy: str = "INCLUDE"
    temporal_valid: bool = True
    self_supervised_valid: bool = True


@dataclass(frozen=True)
class LossWeights:
    synthetic_dense: float = 1.0
    real_reset: float = 2.0
    temporal: float = 0.2
    self_supervised: float = 0.2
    domain: float = 0.1
    quality_downweight: float = 0.25
    exclude_quality_flags: tuple[int, ...] = (64, 128)


@dataclass(frozen=True)
class LossMask:
    dense_correction: float
    sparse_reset: float
    temporal: float
    self_supervised: float
    domain: float
    axes: tuple[float, float, float]
    quality_weight: float
    target_kind: str


def build_loss_mask(example: SupervisionExample, weights: LossWeights = LossWeights()) -> LossMask:
    if example.observation_source not in {"raw", "pre_ai"}:
        raise ValueError("training observations must come from raw or pre_ai channels")
    if example.target_provenance in {TargetProvenance.MODEL_OUTPUT, TargetProvenance.FINAL_OUTPUT}:
        raise ValueError("model/final output cannot be independent correction ground truth")
    excluded = example.training_policy.upper() == "EXCLUDE" or any(example.quality_flags & flag for flag in weights.exclude_quality_flags)
    quality = 0.0 if excluded else weights.quality_downweight if example.quality_flags or example.training_policy.upper() == "DOWNWEIGHT" else 1.0
    mask = example.axis_mask
    if example.reset_domain and example.reset_domain.upper() == "YAW":
        mask &= AXIS_YAW
    axes = tuple(quality if mask & bit else 0.0 for bit in (AXIS_ROLL, AXIS_PITCH, AXIS_YAW))
    dense = weights.synthetic_dense * quality if example.domain is Domain.SYNTHETIC and example.target_provenance is TargetProvenance.SYNTHETIC else 0.0
    sparse = weights.real_reset * quality if example.domain is Domain.REAL and example.target_provenance is TargetProvenance.HUMAN_RESET else 0.0
    temporal = weights.temporal * quality if example.temporal_valid else 0.0
    self_supervised = weights.self_supervised * quality if example.self_supervised_valid else 0.0
    domain = weights.domain * quality
    return LossMask(dense, sparse, temporal, self_supervised, domain, axes, quality, example.target_provenance.value)


def build_loss_masks(examples: list[SupervisionExample], weights: LossWeights = LossWeights()) -> tuple[LossMask, ...]:
    return tuple(build_loss_mask(example, weights) for example in examples)
