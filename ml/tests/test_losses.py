import pytest

from nekovr_ml.losses import AXIS_PITCH, AXIS_ROLL, AXIS_YAW, Domain, SupervisionExample, TargetProvenance, build_loss_mask


def test_synthetic_dense_and_real_yaw_reset_have_distinct_masks():
    synthetic = build_loss_mask(SupervisionExample(Domain.SYNTHETIC, AXIS_ROLL | AXIS_PITCH | AXIS_YAW, TargetProvenance.SYNTHETIC))
    real = build_loss_mask(SupervisionExample(Domain.REAL, AXIS_ROLL | AXIS_PITCH | AXIS_YAW, TargetProvenance.HUMAN_RESET, reset_domain="YAW"))
    assert synthetic.dense_correction > 0 and synthetic.sparse_reset == 0
    assert real.dense_correction == 0 and real.sparse_reset > 0
    assert real.axes == (0.0, 0.0, 1.0)


def test_quality_policy_excludes_or_downweights_deterministically():
    excluded = build_loss_mask(SupervisionExample(Domain.REAL, AXIS_YAW, TargetProvenance.HUMAN_RESET, quality_flags=64))
    downweighted = build_loss_mask(SupervisionExample(Domain.REAL, AXIS_YAW, TargetProvenance.HUMAN_RESET, quality_flags=1))
    assert excluded.quality_weight == 0.0
    assert downweighted.quality_weight == 0.25


@pytest.mark.parametrize("target", [TargetProvenance.MODEL_OUTPUT, TargetProvenance.FINAL_OUTPUT])
def test_closed_loop_outputs_cannot_be_ground_truth(target):
    with pytest.raises(ValueError, match="cannot be independent"):
        build_loss_mask(SupervisionExample(Domain.REAL, AXIS_YAW, target))

