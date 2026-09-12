from __future__ import annotations

from pathlib import Path
import re


REQUIREMENT = re.compile(r"^([A-Za-z0-9_.-]+)==([^ ;\\]+)")
HASH = re.compile(r"--hash=sha256:([0-9a-f]{64})")


def locked_packages(path: Path) -> dict[str, str]:
    packages: dict[str, str] = {}
    active_name: str | None = None
    hashes: dict[str, int] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        match = REQUIREMENT.match(raw_line)
        if match:
            active_name = match.group(1).lower().replace("_", "-")
            if active_name in packages:
                raise ValueError(f"duplicate lock entry: {active_name}")
            packages[active_name] = match.group(2)
            hashes[active_name] = 0
        if active_name:
            hashes[active_name] += len(HASH.findall(raw_line))
        if raw_line and not raw_line.startswith((" ", "#")) and not match:
            active_name = None
    missing = sorted(name for name, count in hashes.items() if count == 0)
    if missing:
        raise ValueError(f"lock entries without SHA-256 hashes: {', '.join(missing)}")
    required_direct = {"numpy", "onnx", "onnxruntime", "zstandard", "pytest", "setuptools", "wheel"}
    required_transitive = {"colorama", "flatbuffers", "iniconfig", "ml-dtypes", "packaging", "pluggy", "protobuf", "typing-extensions"}
    absent = sorted((required_direct | required_transitive) - packages.keys())
    if absent:
        raise ValueError(f"resolved dependency closure is incomplete: {', '.join(absent)}")
    return packages
