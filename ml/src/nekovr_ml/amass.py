from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
from typing import Any, Mapping, Sequence

from .math3d import IDENTITY, Quat, Vec3, inverse, multiply, rotate, slerp, axis_angle_to_quat


CANONICAL_FROM_AMASS: Quat = (-math.sqrt(0.5), 0.0, 0.0, math.sqrt(0.5))
SMPL_PARENTS = (-1, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9, 9, 12, 13, 14, 16, 17, 18, 19, 20, 21)
ROLE_TO_SMPL_JOINT = {
    "body:head": 15,
    "body:upper_chest": 9,
    "body:chest": 6,
    "body:waist": 3,
    "body:hip": 0,
    "body:left_upper_leg": 1,
    "body:right_upper_leg": 2,
    "body:left_lower_leg": 4,
    "body:right_lower_leg": 5,
    "body:left_foot": 10,
    "body:right_foot": 11,
    "body:left_upper_arm": 16,
    "body:right_upper_arm": 17,
    "body:left_lower_arm": 18,
    "body:right_lower_arm": 19,
    "body:left_hand": 22,
    "body:right_hand": 23,
}


class AssetError(ValueError):
    pass


@dataclass(frozen=True)
class MotionFrame:
    time_s: float
    orientations_xyzw: Mapping[str, Quat]
    positions_m: Mapping[str, Vec3]


@dataclass(frozen=True)
class CanonicalMotion:
    frames: tuple[MotionFrame, ...]
    sample_rate_hz: int
    source_hash: str
    body_model_hash: str
    source_license: str
    body_model_license: str


def _canonical_quat(q: Quat) -> Quat:
    return multiply(multiply(CANONICAL_FROM_AMASS, q), inverse(CANONICAL_FROM_AMASS))


def _canonical_vec(v: Sequence[float]) -> Vec3:
    return (float(v[0]), float(v[2]), -float(v[1]))


def _pose_frame(frame: Sequence[Any], joint_count: int) -> list[tuple[float, float, float]]:
    if len(frame) >= joint_count and hasattr(frame[0], "__len__"):
        return [tuple(float(v) for v in frame[i][:3]) for i in range(joint_count)]
    flat = [float(v) for v in frame]
    if len(flat) < joint_count * 3:
        raise AssetError(f"pose frame has {len(flat)} values; expected at least {joint_count * 3}")
    return [tuple(flat[i * 3 : i * 3 + 3]) for i in range(joint_count)]


def convert_pose_sequence(
    poses_axis_angle: Sequence[Sequence[Any]],
    translations: Sequence[Sequence[float]],
    rest_joints_m: Sequence[Sequence[float]],
    source_rate_hz: float,
    target_rate_hz: int = 50,
    parents: Sequence[int] = SMPL_PARENTS,
) -> tuple[MotionFrame, ...]:
    """Convert SMPL local axis-angle poses to canonical timed segment transforms."""
    if source_rate_hz <= 0 or target_rate_hz <= 0:
        raise ValueError("sample rates must be positive")
    if len(poses_axis_angle) == 0 or len(poses_axis_angle) != len(translations):
        raise AssetError("poses and translations must contain the same non-zero frame count")
    joint_count = min(len(parents), len(rest_joints_m))
    if joint_count < 24:
        raise AssetError("body model must expose at least 24 SMPL joints")
    local_rest = []
    for index in range(joint_count):
        value = tuple(float(v) for v in rest_joints_m[index][:3])
        parent = parents[index]
        if parent < 0:
            local_rest.append(value)
        else:
            p = rest_joints_m[parent]
            local_rest.append((value[0] - float(p[0]), value[1] - float(p[1]), value[2] - float(p[2])))

    converted: list[MotionFrame] = []
    for frame_index, pose_values in enumerate(poses_axis_angle):
        local = [axis_angle_to_quat(v) for v in _pose_frame(pose_values, joint_count)]
        global_q: list[Quat] = [IDENTITY] * joint_count
        global_p: list[Vec3] = [(0.0, 0.0, 0.0)] * joint_count
        root_translation = tuple(float(v) for v in translations[frame_index][:3])
        for joint in range(joint_count):
            parent = int(parents[joint])
            if parent < 0:
                global_q[joint] = local[joint]
                global_p[joint] = root_translation
            else:
                global_q[joint] = multiply(global_q[parent], local[joint])
                offset = rotate(global_q[parent], local_rest[joint])
                pp = global_p[parent]
                global_p[joint] = (pp[0] + offset[0], pp[1] + offset[1], pp[2] + offset[2])
        orientations = {role: _canonical_quat(global_q[joint]) for role, joint in ROLE_TO_SMPL_JOINT.items()}
        positions = {role: _canonical_vec(global_p[joint]) for role, joint in ROLE_TO_SMPL_JOINT.items()}
        converted.append(MotionFrame(frame_index / source_rate_hz, orientations, positions))
    return resample_frames(converted, target_rate_hz)


def resample_frames(frames: Sequence[MotionFrame], target_rate_hz: int) -> tuple[MotionFrame, ...]:
    if len(frames) == 1:
        return (MotionFrame(0.0, frames[0].orientations_xyzw, frames[0].positions_m),)
    duration = frames[-1].time_s
    count = int(math.floor(duration * target_rate_hz + 1e-9)) + 1
    result: list[MotionFrame] = []
    source_index = 0
    for index in range(count):
        t = index / target_rate_hz
        while source_index + 1 < len(frames) - 1 and frames[source_index + 1].time_s < t:
            source_index += 1
        left, right = frames[source_index], frames[min(source_index + 1, len(frames) - 1)]
        span = right.time_s - left.time_s
        alpha = 0.0 if span <= 0.0 else (t - left.time_s) / span
        orientations = {role: slerp(left.orientations_xyzw[role], right.orientations_xyzw[role], alpha) for role in left.orientations_xyzw}
        positions = {
            role: tuple(a + alpha * (b - a) for a, b in zip(left.positions_m[role], right.positions_m[role]))
            for role in left.positions_m
        }
        result.append(MotionFrame(t, orientations, positions))
    return tuple(result)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_amass(
    amass_path: str | Path,
    body_model_path: str | Path,
    target_rate_hz: int = 50,
    source_license: str = "AMASS",
    body_model_license: str = "SMPL",
) -> CanonicalMotion:
    amass = Path(amass_path)
    model = Path(body_model_path)
    if not amass.is_file():
        raise AssetError(f"AMASS asset not found: {amass}; download it under the AMASS license and pass --amass")
    if not model.is_file():
        raise AssetError(f"SMPL-family body model not found: {model}; accept its license and pass --body-model")
    try:
        import numpy as np
    except ImportError as error:
        raise AssetError("NumPy is required for licensed AMASS .npz ingestion; install requirements.lock") from error
    try:
        motion_npz = np.load(amass, allow_pickle=False)
        model_npz = np.load(model, allow_pickle=False)
        poses = motion_npz["poses"]
        translations = motion_npz["trans"]
        source_rate = float(motion_npz["mocap_framerate"])
        if "J" in model_npz:
            joints = model_npz["J"]
        elif "J_regressor" in model_npz and "v_template" in model_npz:
            joints = model_npz["J_regressor"].dot(model_npz["v_template"])
        else:
            raise AssetError("body model lacks J or J_regressor/v_template needed for canonical conversion")
        parents = SMPL_PARENTS
        if "kintree_table" in model_npz:
            candidate = [int(v) for v in model_npz["kintree_table"][0][:24]]
            candidate[0] = -1
            parents = tuple(candidate)
        frames = convert_pose_sequence(poses, translations, joints, source_rate, target_rate_hz, parents)
    except (KeyError, ValueError, OSError) as error:
        if isinstance(error, AssetError):
            raise
        raise AssetError(f"incompatible AMASS/body-model assets: {error}") from error
    return CanonicalMotion(frames, target_rate_hz, _sha256(amass), _sha256(model), source_license, body_model_license)


def write_motion_json(motion: CanonicalMotion, output: str | Path) -> None:
    payload = {
        "format": "nekovr-canonical-motion-v1",
        "coordinate_contract": "RH_X_RIGHT_Y_UP_Z_BACKWARD_XYZW_ACTIVE",
        "sample_rate_hz": motion.sample_rate_hz,
        "source_sha256": motion.source_hash,
        "body_model_sha256": motion.body_model_hash,
        "source_license": motion.source_license,
        "body_model_license": motion.body_model_license,
        "frames": [
            {"time_s": f.time_s, "orientations_xyzw": f.orientations_xyzw, "positions_m": f.positions_m}
            for f in motion.frames
        ],
    }
    Path(output).write_text(json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")

