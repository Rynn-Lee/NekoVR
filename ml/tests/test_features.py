import pytest

from nekovr_ml.features import FeatureRow, FeatureSpec, ShortcutFinding, audit_unseen_shortcuts, augment_missingness, decide_feature_promotion, fit_feature_normalization, run_per_feature_ablation


def test_context_normalization_is_training_only_and_handles_unseen_device():
    specs = (FeatureSpec("temperature", "temperature", "device"),)
    rows = [
        FeatureRow("a", "train", "device-a", "session-a", {"temperature": 20.0}),
        FeatureRow("b", "train", "device-a", "session-a", {"temperature": 30.0}),
        FeatureRow("holdout", "test", "device-b", "session-b", {"temperature": 1000.0}),
    ]
    normalization = fit_feature_normalization(specs, rows)
    assert normalization.statistics["temperature"]["*"].mean == 25.0
    values, validity = normalization.transform(rows[-1])
    assert values == (195.0,)
    assert validity == (True,)


def test_missingness_augmentation_only_drops_contextual_features():
    specs = (FeatureSpec("orientation", "motion"), FeatureSpec("rssi", "network"))
    values, validity = augment_missingness((1.0, 2.0), (True, True), specs, 1.0, 3)
    assert values == (1.0, 0.0)
    assert validity == (True, False)


def test_spurious_temperature_shortcut_blocks_promotion():
    specs = (FeatureSpec("orientation", "motion"), FeatureSpec("temperature", "temperature", "device"))
    decisions = decide_feature_promotion(
        specs,
        normalized_features={"temperature"},
        missingness_tested_features={"temperature"},
        ablation_improvement={"temperature": 0.1},
        shortcut_findings=(ShortcutFinding("temperature", "device", "unseen", 0.2, 0.4, False),),
    )
    assert decisions[0].promoted
    assert not decisions[1].promoted
    assert "unseen_cohort_regression" in decisions[1].reasons


def test_per_feature_ablation_and_unseen_device_session_audits():
    contributions = run_per_feature_ablation(
        ("motion", "temperature"),
        lambda active: 0.2 + (0.1 if "motion" not in active else 0.0) - (0.01 if "temperature" not in active else 0.0),
    )
    assert contributions["motion"] == pytest.approx(0.1)
    assert contributions["temperature"] == pytest.approx(-0.01)
    findings = audit_unseen_shortcuts(
        "temperature", {"known-device"}, {"known-session"},
        {("device", "new-device"): 0.2, ("session", "new-session"): 0.3},
        {("device", "new-device"): 0.4, ("session", "new-session"): 0.5},
    )
    assert all(not finding.seen_in_training and finding.regression > 0 for finding in findings)
