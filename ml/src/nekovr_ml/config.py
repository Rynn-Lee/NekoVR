from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
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

