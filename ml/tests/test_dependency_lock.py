from pathlib import Path

from nekovr_ml.dependency_lock import locked_packages


def test_dependency_lock_contains_hashed_direct_and_transitive_closure():
    packages = locked_packages(Path(__file__).parents[1] / "requirements.lock")
    assert packages["numpy"] == "2.3.5"
    assert packages["onnx"] == "1.22.0"
    assert packages["onnxruntime"] == "1.29.0"
    assert packages["pytest"] == "8.3.5"
    assert packages["setuptools"] == "80.9.0"
    assert len(packages) > 7
