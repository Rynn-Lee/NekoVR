from __future__ import annotations

from pathlib import Path

from .model import CompactCausalModel, ModelConfig
from .onnx_export import export_model


def generate(output_directory: str | Path) -> tuple[Path, Path]:
    """Generate the deterministic 10-slot/60-frame small-tier benchmark fixture."""
    output = Path(output_directory)
    model = CompactCausalModel(
        ModelConfig(feature_count=4, hidden_size=6, temporal_layers=2, max_slots=10),
        seed=1102,
    )
    return export_model(
        model,
        output / "small.onnx",
        model_id="nekovr-small-benchmark-fixture",
        model_version="1.0.0",
        feature_schema={"version": 1, "features": ["orientation", "gyro", "acceleration", "sample_age"]},
        normalization={"mean": [0.0] * 4, "standard_deviation": [1.0] * 4, "scope": "fixture"},
        supported_roles=range(1, 17),
        minimum_slots=1,
        minimum_context=1,
        maximum_context=60,
        provenance={
            "seed": 1102,
            "config_sha256": "0" * 64,
            "source_commit": "DETERMINISTIC_BENCHMARK_FIXTURE",
            "dataset_hashes": {},
        },
        validation_metrics={"purpose": "runtime_performance_fixture", "not_for_correction": True},
        performance_tier="small",
    )


if __name__ == "__main__":
    generate(Path(__file__).resolve().parents[2] / "artifacts" / "benchmark-small-v1")
