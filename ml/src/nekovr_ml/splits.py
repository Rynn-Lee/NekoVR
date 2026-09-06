from __future__ import annotations

from dataclasses import dataclass
import hashlib
import math
from typing import Iterable, Mapping, Sequence


SPLIT_NAMES = ("train", "validation", "test")


@dataclass(frozen=True)
class SampleMetadata:
    sample_id: str
    domain: str
    subject_id: str
    source_id: str
    session_id: str
    device_cohort: str
    layout: str
    chipset: str = "UNKNOWN"


@dataclass(frozen=True)
class SplitResult:
    assignments: Mapping[str, str]
    audit: Mapping[str, object]


class _UnionFind:
    def __init__(self, size: int):
        self.parent = list(range(size))

    def find(self, value: int) -> int:
        while self.parent[value] != value:
            self.parent[value] = self.parent[self.parent[value]]
            value = self.parent[value]
        return value

    def union(self, left: int, right: int) -> None:
        left, right = self.find(left), self.find(right)
        if left != right:
            self.parent[right] = left


def grouped_split(
    samples: Sequence[SampleMetadata],
    seed: int,
    fractions: Mapping[str, float] | None = None,
    group_fields: Sequence[str] = ("subject_id", "source_id", "session_id", "device_cohort"),
) -> SplitResult:
    fractions = fractions or {"train": 0.7, "validation": 0.15, "test": 0.15}
    if set(fractions) != set(SPLIT_NAMES) or not math.isclose(sum(fractions.values()), 1.0, abs_tol=1e-9):
        raise ValueError("split fractions must define train/validation/test and sum to 1")
    if len({sample.sample_id for sample in samples}) != len(samples):
        raise ValueError("sample_id values must be unique")
    union = _UnionFind(len(samples))
    owners: dict[tuple[str, str, str], int] = {}
    for index, sample in enumerate(samples):
        for field in group_fields:
            value = str(getattr(sample, field))
            if not value:
                continue
            # Domain prevents coincidental synthetic and real identifiers from coupling.
            token = (sample.domain, field, value)
            if token in owners:
                union.union(index, owners[token])
            else:
                owners[token] = index
    components: dict[int, list[int]] = {}
    for index in range(len(samples)):
        components.setdefault(union.find(index), []).append(index)
    assignments: dict[str, str] = {}
    train_edge = fractions["train"]
    validation_edge = train_edge + fractions["validation"]
    for indices in components.values():
        stable_ids = "\0".join(sorted(samples[i].sample_id for i in indices))
        value = int.from_bytes(hashlib.sha256(f"{seed}\0{stable_ids}".encode()).digest()[:8], "big") / 2**64
        split = "train" if value < train_edge else "validation" if value < validation_edge else "test"
        for index in indices:
            assignments[samples[index].sample_id] = split
    audit = audit_split(samples, assignments, group_fields)
    if audit["forbidden_overlaps"]:
        raise AssertionError(f"group leakage detected: {audit['forbidden_overlaps']}")
    return SplitResult(assignments, audit)


def audit_split(samples: Sequence[SampleMetadata], assignments: Mapping[str, str], group_fields: Sequence[str]) -> dict[str, object]:
    overlap: list[dict[str, object]] = []
    for field in group_fields:
        locations: dict[tuple[str, str], set[str]] = {}
        for sample in samples:
            locations.setdefault((sample.domain, str(getattr(sample, field))), set()).add(assignments[sample.sample_id])
        overlap.extend({"field": field, "value": key[1], "domain": key[0], "splits": sorted(values)} for key, values in locations.items() if key[1] and len(values) > 1)
    coverage = {split: {"layouts": [], "chipsets": [], "domains": [], "samples": 0} for split in SPLIT_NAMES}
    for split in SPLIT_NAMES:
        selected = [sample for sample in samples if assignments[sample.sample_id] == split]
        coverage[split] = {
            "layouts": sorted({sample.layout for sample in selected}),
            "chipsets": sorted({sample.chipset for sample in selected}),
            "domains": sorted({sample.domain for sample in selected}),
            "samples": len(selected),
        }
    return {"forbidden_overlaps": overlap, "coverage": coverage}


@dataclass(frozen=True)
class Normalization:
    mean: tuple[float, ...]
    standard_deviation: tuple[float, ...]
    training_sample_ids: tuple[str, ...]

    def apply(self, values: Sequence[float]) -> tuple[float, ...]:
        if len(values) != len(self.mean):
            raise ValueError("feature width differs from fitted normalization")
        return tuple((float(value) - mean) / scale for value, mean, scale in zip(values, self.mean, self.standard_deviation))


def fit_training_normalization(features: Mapping[str, Sequence[float]], assignments: Mapping[str, str]) -> Normalization:
    training_ids = tuple(sorted(sample_id for sample_id, split in assignments.items() if split == "train"))
    if not training_ids:
        raise ValueError("normalization requires at least one training sample")
    rows = [features[sample_id] for sample_id in training_ids]
    width = len(rows[0])
    if width == 0 or any(len(row) != width for row in rows):
        raise ValueError("all feature rows must have the same non-zero width")
    means = tuple(sum(float(row[index]) for row in rows) / len(rows) for index in range(width))
    variances = tuple(sum((float(row[index]) - means[index]) ** 2 for row in rows) / len(rows) for index in range(width))
    scales = tuple(math.sqrt(value) if value > 1e-12 else 1.0 for value in variances)
    return Normalization(means, scales, training_ids)

