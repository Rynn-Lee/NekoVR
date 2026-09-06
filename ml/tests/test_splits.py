from nekovr_ml.splits import SampleMetadata, fit_training_normalization, grouped_split


def _sample(name, subject, source, session, device):
    return SampleMetadata(name, "real", subject, source, session, device, "6", "BMI160")


def test_transitively_related_groups_never_leak():
    samples = [
        _sample("a", "person-a", "source-a", "session-a", "device-a"),
        _sample("b", "person-a", "source-b", "session-b", "device-b"),
        _sample("c", "person-c", "source-b", "session-c", "device-c"),
        _sample("d", "person-d", "source-d", "session-d", "device-d"),
    ]
    result = grouped_split(samples, 44)
    assert result.assignments["a"] == result.assignments["b"] == result.assignments["c"]
    assert result.audit["forbidden_overlaps"] == []


def test_normalization_uses_training_rows_only():
    normalization = fit_training_normalization({"train-a": (1.0, 2.0), "train-b": (3.0, 4.0), "test": (1000.0, 1000.0)}, {"train-a": "train", "train-b": "train", "test": "test"})
    assert normalization.mean == (2.0, 3.0)
    assert normalization.standard_deviation == (1.0, 1.0)
    assert normalization.training_sample_ids == ("train-a", "train-b")

