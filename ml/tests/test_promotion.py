from nekovr_ml.promotion import ActivityCohortMetric, PromotionPolicy, REQUIRED_ACTIVITIES, evaluate_activity_gate


def _metric(activity, candidate=0.9, baseline=1.0):
    return ActivityCohortMetric(activity, 200, 60.0, 0.9, baseline, candidate, 0.01, 0.01, 0.1, 0.1)


def test_every_required_activity_must_have_confident_coverage():
    result = evaluate_activity_gate([_metric(activity) for activity in REQUIRED_ACTIVITIES[:-1]], PromotionPolicy())
    assert not result.passed
    assert result.missing_activities == ("STATIONARY",)


def test_single_lying_regression_blocks_aggregate_improvement():
    metrics = [_metric(activity, candidate=1.1 if activity == "LYING" else 0.5) for activity in REQUIRED_ACTIVITIES]
    result = evaluate_activity_gate(metrics)
    assert not result.passed
    assert result.regressions == {"LYING": ("angular_error",)}


def test_all_covered_non_regressing_cohorts_pass():
    result = evaluate_activity_gate([_metric(activity) for activity in REQUIRED_ACTIVITIES])
    assert result.passed

