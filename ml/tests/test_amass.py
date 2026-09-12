import math
import json
import os
from pathlib import Path

import pytest

from nekovr_ml.amass import (
    CANONICAL_FROM_AMASS,
    CANONICAL_HMD_REFERENCE_ROLE,
    CANONICAL_ROOT_ROLE,
    ROLE_TO_SMPL_JOINT,
    AssetError,
    convert_pose_sequence,
    load_amass,
    validate_smpl_structure,
)
from nekovr_ml.math3d import angular_distance, axis_angle_to_quat, inverse, multiply


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


def test_distributable_structural_fixture_covers_tree_mapping_resampling_units_and_transform():
    fixture = json.loads((Path(__file__).parents[1] / "fixtures" / "smpl-structural-v1.json").read_text())
    parents = validate_smpl_structure(fixture["parents"], len(fixture["rest_joints_m"]))
    assert fixture["license"].startswith("CC0-1.0")
    assert fixture["canonical_root_role"] == CANONICAL_ROOT_ROLE
    assert fixture["canonical_hmd_reference_role"] == CANONICAL_HMD_REFERENCE_ROLE
    assert ROLE_TO_SMPL_JOINT[CANONICAL_ROOT_ROLE] == 0
    assert ROLE_TO_SMPL_JOINT[CANONICAL_HMD_REFERENCE_ROLE] == 15
    poses = [[0.0] * 72 for _ in fixture["translations_m"]]
    for joint, value in fixture["pose_overrides_axis_angle"]["frame_1"].items():
        poses[1][int(joint) * 3 : int(joint) * 3 + 3] = value
    frames = convert_pose_sequence(
        poses,
        fixture["translations_m"],
        fixture["rest_joints_m"],
        fixture["source_rate_hz"],
        fixture["target_rate_hz"],
        parents,
    )
    assert [frame.time_s for frame in frames] == pytest.approx([0.0, 0.02, 0.04])
    # AMASS metres (x, y, z) become canonical metres (x, z, -y).
    assert frames[-1].positions_m[CANONICAL_ROOT_ROLE] == pytest.approx((0.1, 0.3, -0.2))
    root = axis_angle_to_quat(fixture["pose_overrides_axis_angle"]["frame_1"]["0"])
    neck = axis_angle_to_quat(fixture["pose_overrides_axis_angle"]["frame_1"]["12"])
    head = axis_angle_to_quat(fixture["pose_overrides_axis_angle"]["frame_1"]["15"])
    expected = multiply(multiply(CANONICAL_FROM_AMASS, multiply(multiply(root, neck), head)), inverse(CANONICAL_FROM_AMASS))
    reversed_order = multiply(multiply(CANONICAL_FROM_AMASS, multiply(head, multiply(neck, root))), inverse(CANONICAL_FROM_AMASS))
    actual = frames[-1].orientations_xyzw[CANONICAL_HMD_REFERENCE_ROLE]
    assert angular_distance(actual, expected) < 1e-6
    assert angular_distance(actual, reversed_order) > 0.05


@pytest.mark.licensed_assets
def test_opt_in_licensed_amass_smpl_assets_cover_real_structure():
    amass_path = os.environ.get("NEKOVR_AMASS_FIXTURE")
    body_model_path = os.environ.get("NEKOVR_SMPL_MODEL")
    if not amass_path or not body_model_path:
        pytest.skip("set NEKOVR_AMASS_FIXTURE and NEKOVR_SMPL_MODEL for licensed integration")
    motion = load_amass(amass_path, body_model_path, target_rate_hz=50)
    assert motion.frames
    assert motion.sample_rate_hz == 50
    assert CANONICAL_ROOT_ROLE in motion.frames[0].orientations_xyzw
    assert CANONICAL_HMD_REFERENCE_ROLE in motion.frames[0].orientations_xyzw
    assert len(motion.source_hash) == len(motion.body_model_hash) == 64
