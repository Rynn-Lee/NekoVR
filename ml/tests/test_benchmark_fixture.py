from nekovr_ml.benchmark_fixture import generate
from nekovr_ml.model_metadata import load_sidecar


def test_benchmark_fixture_has_reference_shape_and_is_deterministic(tmp_path):
    first_model, first_sidecar = generate(tmp_path / "first")
    second_model, second_sidecar = generate(tmp_path / "second")
    first = load_sidecar(first_sidecar, first_model)
    second = load_sidecar(second_sidecar, second_model)
    assert first.model_sha256 == second.model_sha256
    assert first.performance_tier == "small"
    assert first.slot_bounds == {"minimum": 1, "maximum": 10}
    assert first.context_bounds == {"minimum": 1, "maximum": 60}
