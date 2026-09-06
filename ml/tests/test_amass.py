import math

import pytest

from nekovr_ml.amass import AssetError, convert_pose_sequence, load_amass
from nekovr_ml.math3d import multiply


def _rest_joints():
    return [(0.0, 0.0, index / 10.0) for index in range(24)]


def test_known_pose_uses_canonical_y_up_and_xyzw_rotation():
    identity = [0.0] * 72
    yaw_in_canonical = [0.0] * 72
    yaw_in_canonical[2] = math.pi / 2.0  # AMASS +Z becomes canonical +Y.
    frames = convert_pose_sequence([identity, yaw_in_canonical], [(0, 0, 0), (0, 0, 0)], _rest_joints(), 50.0, 50)
    head = frames[0].positions_m["body:head"]
    assert head[0] == pytest.approx(0.0)
    assert head[1] == pytest.approx(1.5)
    assert head[2] == pytest.approx(0.0)
    q = frames[1].orientations_xyzw["body:head"]
    assert q == pytest.approx((0.0, math.sqrt(0.5), 0.0, math.sqrt(0.5)), abs=1e-6)


def test_missing_licensed_assets_fail_before_output(tmp_path):
    with pytest.raises(AssetError, match="AMASS asset not found"):
        load_amass(tmp_path / "missing-amass.npz", tmp_path / "missing-smpl.npz")

