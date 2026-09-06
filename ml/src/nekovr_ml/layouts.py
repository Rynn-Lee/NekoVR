from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Sequence

from .amass import CanonicalMotion
from .math3d import IDENTITY, Quat, Vec3, multiply, rotate


LAYOUT_ROLES: dict[int, tuple[str, ...]] = {
    5: ("body:hip", "body:left_upper_leg", "body:right_upper_leg", "body:left_lower_leg", "body:right_lower_leg"),
    6: ("body:upper_chest", "body:hip", "body:left_upper_leg", "body:right_upper_leg", "body:left_lower_leg", "body:right_lower_leg"),
    8: ("body:upper_chest", "body:hip", "body:left_upper_leg", "body:right_upper_leg", "body:left_lower_leg", "body:right_lower_leg", "body:left_foot", "body:right_foot"),
    10: ("body:upper_chest", "body:hip", "body:left_upper_leg", "body:right_upper_leg", "body:left_lower_leg", "body:right_lower_leg", "body:left_foot", "body:right_foot", "body:left_upper_arm", "body:right_upper_arm"),
}


@dataclass(frozen=True)
class SensorPlacement:
    body_role: str
    segment_to_sensor_xyzw: Quat = IDENTITY
    offset_m: Vec3 = (0.0, 0.0, 0.0)


@dataclass(frozen=True)
class LayoutFrame:
    time_s: float
    orientations_xyzw: tuple[Quat, ...]
    positions_m: tuple[Vec3, ...]
    slot_mask: tuple[bool, ...]


@dataclass(frozen=True)
class VirtualLayout:
    roles: tuple[str, ...]
    role_ids: tuple[int, ...]
    slot_mask: tuple[bool, ...]
    frames: tuple[LayoutFrame, ...]


def roles_for_layout(layout: int | Sequence[str]) -> tuple[str, ...]:
    if isinstance(layout, int):
        if layout not in LAYOUT_ROLES:
            raise ValueError(f"unsupported preset layout {layout}; pass an explicit body-role list")
        return LAYOUT_ROLES[layout]
    roles = tuple(layout)
    if not roles or len(set(roles)) != len(roles):
        raise ValueError("explicit layout roles must be non-empty and unique")
    return roles


def generate_layout(
    motion: CanonicalMotion,
    layout: int | Sequence[str],
    max_slots: int = 16,
    placements: Mapping[str, SensorPlacement] | None = None,
) -> VirtualLayout:
    roles = roles_for_layout(layout)
    if len(roles) > max_slots:
        raise ValueError("layout exceeds max_slots")
    placements = placements or {}
    role_catalog = tuple(sorted(set(role for frame in motion.frames for role in frame.orientations_xyzw)))
    missing = [role for role in roles if role not in role_catalog]
    if missing:
        raise ValueError(f"motion lacks body roles: {', '.join(missing)}")
    padded_mask = tuple(index < len(roles) for index in range(max_slots))
    frames = []
    for source in motion.frames:
        orientations = []
        positions = []
        for role in roles:
            placement = placements.get(role, SensorPlacement(role))
            segment_q = source.orientations_xyzw[role]
            orientations.append(multiply(segment_q, placement.segment_to_sensor_xyzw))
            offset = rotate(segment_q, placement.offset_m)
            segment_p = source.positions_m[role]
            positions.append(tuple(a + b for a, b in zip(segment_p, offset)))
        orientations.extend([IDENTITY] * (max_slots - len(roles)))
        positions.extend([(0.0, 0.0, 0.0)] * (max_slots - len(roles)))
        frames.append(LayoutFrame(source.time_s, tuple(orientations), tuple(positions), padded_mask))
    role_ids = tuple(role_catalog.index(role) + 1 for role in roles) + (0,) * (max_slots - len(roles))
    return VirtualLayout(roles, role_ids, padded_mask, tuple(frames))

