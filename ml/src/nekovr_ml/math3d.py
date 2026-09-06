from __future__ import annotations

import math
from typing import Iterable

Quat = tuple[float, float, float, float]
Vec3 = tuple[float, float, float]
IDENTITY: Quat = (0.0, 0.0, 0.0, 1.0)


def normalize(q: Iterable[float]) -> Quat:
    x, y, z, w = (float(v) for v in q)
    norm = math.sqrt(x * x + y * y + z * z + w * w)
    if not math.isfinite(norm) or norm <= 1e-12:
        raise ValueError("quaternion must be finite and non-zero")
    qn = (x / norm, y / norm, z / norm, w / norm)
    return tuple(-v for v in qn) if qn[3] < 0.0 else qn


def multiply(a: Quat, b: Quat) -> Quat:
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return normalize((
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz,
    ))


def inverse(q: Quat) -> Quat:
    x, y, z, w = normalize(q)
    return (-x, -y, -z, w)


def axis_angle_to_quat(value: Iterable[float]) -> Quat:
    x, y, z = (float(v) for v in value)
    angle = math.sqrt(x * x + y * y + z * z)
    if angle < 1e-12:
        return IDENTITY
    scale = math.sin(angle / 2.0) / angle
    return normalize((x * scale, y * scale, z * scale, math.cos(angle / 2.0)))


def rotation_vector_to_quat(value: Iterable[float]) -> Quat:
    return axis_angle_to_quat(value)


def angular_distance(a: Quat, b: Quat) -> float:
    """Shortest angular distance in radians, treating q and -q as equivalent."""
    a = normalize(a)
    b = normalize(b)
    dot = abs(sum(x * y for x, y in zip(a, b)))
    return 2.0 * math.acos(max(-1.0, min(1.0, dot)))


def rotate(q: Quat, value: Vec3) -> Vec3:
    x, y, z, w = normalize(q)
    vx, vy, vz = value
    tx, ty, tz = (2.0 * (y * vz - z * vy), 2.0 * (z * vx - x * vz), 2.0 * (x * vy - y * vx))
    return (
        vx + w * tx + (y * tz - z * ty),
        vy + w * ty + (z * tx - x * tz),
        vz + w * tz + (x * ty - y * tx),
    )


def correction(observed: Quat, target: Quat) -> Quat:
    return multiply(target, inverse(observed))


def slerp(a: Quat, b: Quat, t: float) -> Quat:
    a = normalize(a)
    b = normalize(b)
    dot = sum(x * y for x, y in zip(a, b))
    if dot < 0.0:
        b = tuple(-v for v in b)
        dot = -dot
    if dot > 0.9995:
        return normalize(tuple(x + t * (y - x) for x, y in zip(a, b)))
    theta = math.acos(max(-1.0, min(1.0, dot)))
    scale = math.sin(theta)
    return normalize(tuple((math.sin((1.0 - t) * theta) * x + math.sin(t * theta) * y) / scale for x, y in zip(a, b)))
