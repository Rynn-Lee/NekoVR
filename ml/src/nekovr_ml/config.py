from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import math
import os
import random
from pathlib import Path
from typing import Any


class ConfigurationError(ValueError):
    pass


@dataclass(frozen=True)
class RunConfiguration:
    values: dict[str, Any]
    canonical_json: str
    sha256: str

    @property
    def seed(self) -> int:
        return int(self.values["seed"])


def load_config(path: str | Path) -> RunConfiguration:
    source = Path(path)
    values = json.loads(source.read_text(encoding="utf-8"))
    if values.get("schema_version") != 1:
        raise ConfigurationError("configuration schema_version must be 1")
    if not isinstance(values.get("seed"), int) or values["seed"] < 0:
        raise ConfigurationError("seed must be a non-negative integer")
    if values.get("deterministic") is not True:
        raise ConfigurationError("production preparation requires deterministic=true")
    rate = values.get("sample_rate_hz")
    if not isinstance(rate, int) or rate <= 0:
        raise ConfigurationError("sample_rate_hz must be a positive integer")
    layouts = values.get("layouts")
    if not isinstance(layouts, list) or not layouts or any(not isinstance(v, int) or v <= 0 for v in layouts):
        raise ConfigurationError("layouts must be a non-empty list of positive integers")
    personalization = values.get("personalization")
    if personalization is not None:
        if personalization.get("adapter_only") is not True:
            raise ConfigurationError("personalization requires adapter_only=true")
        positive_integers = ("maximum_checkpoint_mib", "maximum_trainable_parameters", "maximum_epochs", "early_stopping_patience")
        if any(not isinstance(personalization.get(name), int) or personalization[name] <= 0 for name in positive_integers):
            raise ConfigurationError("personalization integer limits must be positive")
        try:
            bounded = float(personalization.get("maximum_trainable_fraction", -1))
            safety_values = tuple(float(personalization.get(name, -1)) for name in (
                "early_stopping_min_delta", "maximum_correction_degrees",
                "maximum_clean_false_correction_degrees", "maximum_temporal_jitter_degrees",
            ))
        except (TypeError, ValueError) as error:
            raise ConfigurationError("personalization numeric limits must be numbers") from error
        if not 0 < bounded <= 1:
            raise ConfigurationError("maximum_trainable_fraction must be in (0, 1]")
        if any(not math.isfinite(value) or value < 0 for value in safety_values):
            raise ConfigurationError("personalization safety limits must be finite and non-negative")
        if personalization.get("provider") not in ("AUTO", "CPU", "CUDA"):
            raise ConfigurationError("personalization provider must be AUTO, CPU or CUDA")
    canonical = json.dumps(values, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return RunConfiguration(values, canonical, hashlib.sha256(canonical.encode()).hexdigest())


def deterministic_random(config: RunConfiguration, namespace: str = "") -> random.Random:
    digest = hashlib.sha256(f"{config.seed}:{namespace}".encode()).digest()
    return random.Random(int.from_bytes(digest[:8], "big"))


def configure_process_determinism(config: RunConfiguration) -> None:
    os.environ.setdefault("PYTHONHASHSEED", str(config.seed))
    random.seed(config.seed)
    try:
        import numpy as np

        np.random.seed(config.seed)
    except ImportError:
        pass
