from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
from typing import Any, Sequence

from .amass import CanonicalMotion
from .layouts import VirtualLayout, generate_layout
from .math3d import Quat, normalize
from .sessions import PreparedSession
from .simulation import SimulationConfig, simulate


PREPARATION_FORMAT = "nekovr-canonical-preparation-v1"
TRAINING_INPUT_FORMAT = "nekovr-training-input-v1"
FEATURE_SCHEMA = (
    "orientation_x", "orientation_y", "orientation_z", "orientation_w",
    "position_x_m", "position_y_m", "position_z_m", "temperature_c",
    "delta_s", "stale", "orientation_validity", "angular_velocity_provenance",
    "drift_provenance", "reset_context", "synthetic_domain", "real_domain",
)


def _rotation_vector(quaternion: Quat) -> tuple[float, float, float]:
    x, y, z, w = normalize(quaternion)
    vector_length = math.sqrt(x * x + y * y + z * z)
    if vector_length <= 1e-12:
        return (0.0, 0.0, 0.0)
    angle = 2.0 * math.atan2(vector_length, max(-1.0, min(1.0, w)))
    scale = angle / vector_length
    return (x * scale, y * scale, z * scale)


def _stable_id(*values: object) -> str:
    payload = json.dumps(values, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(payload).hexdigest()[:24]


def _synthetic_example(
    motion: CanonicalMotion,
    layout: VirtualLayout,
    layout_name: str,
    simulation: SimulationConfig,
    seed: int,
) -> dict[str, Any]:
    simulated = simulate(layout, simulation, seed)
    feature_frames, validity_frames = [], []
    previous_time = 0.0
    for source, observed in zip(layout.frames, simulated):
        delta = max(0.0, source.time_s - previous_time)
        previous_time = source.time_s
        feature_slots, validity_slots = [], []
        for slot, active in enumerate(layout.slot_mask):
            orientation = observed.observed_xyzw[slot]
            position = source.positions_m[slot]
            valid = bool(active and observed.channel_valid_mask[slot])
            feature_slots.append([
                *orientation, *position, observed.temperature_c[slot], delta,
                float(observed.stale_mask[slot]), 1.0 if valid else 0.0,
                2.0 if valid else 0.0, 4.0 if valid else 0.0,
                float(observed.reset_mask[slot]), 1.0, 0.0,
            ])
            validity_slots.append([
                *([valid] * 4), *([active] * 3), active, True, active, True,
                valid, valid, active, True, True,
            ])
        feature_frames.append(feature_slots)
        validity_frames.append(validity_slots)
    latest = simulated[-1]
    reset_indexes = [index for index, frame in enumerate(simulated) if any(frame.reset_mask)]
    return {
        "sample_id": f"synthetic-{_stable_id(motion.source_hash, layout_name, seed)}",
        "split": "unassigned",
        "domain": "synthetic",
        "source_sha256": motion.source_hash,
        "group": {"source_id": motion.source_hash, "layout": layout_name},
        "features": feature_frames,
        "role_ids": list(layout.role_ids),
        "slot_mask": list(layout.slot_mask),
        "channel_validity": validity_frames,
        "time_deltas_s": [frame[0][8] for frame in feature_frames],
        "target_correction_rotation_vectors": [
            list(_rotation_vector(latest.correction_target_xyzw[slot])) if active else [0.0, 0.0, 0.0]
            for slot, active in enumerate(layout.slot_mask)
        ],
        "target_confidence": [1.0 if latest.channel_valid_mask[slot] else 0.0 for slot in range(len(layout.slot_mask))],
        "loss_weights": [1.0 if latest.channel_valid_mask[slot] else 0.0 for slot in range(len(layout.slot_mask))],
        "masks": {"reset_frame_indexes": reset_indexes, "quality": "INCLUDE"},
        "quality_decision": {"policy": "INCLUDE", "reason": "deterministic_simulation"},
    }


def prepare_amass_motion(
    motion: CanonicalMotion,
    layouts: Sequence[int | Sequence[str]],
    max_slots: int,
    simulation: SimulationConfig,
    seed: int,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    examples = []
    for index, layout_value in enumerate(layouts):
        name = str(layout_value) if isinstance(layout_value, int) else "+".join(layout_value)
        layout = generate_layout(motion, layout_value, max_slots=max_slots)
        examples.append(_synthetic_example(motion, layout, name, simulation, seed + index))
    source = {
        "kind": "amass",
        "source_sha256": motion.source_hash,
        "body_model_sha256": motion.body_model_hash,
        "source_license": motion.source_license,
        "body_model_license": motion.body_model_license,
    }
    return source, examples


def _real_example(session: PreparedSession, window: Any, tracker_ids: tuple[str, ...]) -> dict[str, Any]:
    start = window.pre_range[0]
    end = max(window.post_range[1], start + 1)
    selected = [frame for frame in session.frames if start <= frame.index < end]
    if not selected:
        raise ValueError(f"reset window {window.event_index} has no retained frames")
    frames, validity = [], []
    for frame in selected:
        by_id = {tracker.session_tracker_id: tracker for tracker in frame.trackers}
        feature_slots, validity_slots = [], []
        in_reset = window.pre_range[0] <= frame.index < window.post_range[1]
        for tracker_id in tracker_ids:
            tracker = by_id.get(tracker_id)
            if tracker is None:
                feature_slots.append([0.0] * len(FEATURE_SCHEMA))
                validity_slots.append([False] * len(FEATURE_SCHEMA))
                continue
            orientation_valid = tracker.orientation_validity == 3
            feature_slots.append([
                *tracker.pre_ai_orientation_xyzw, 0.0, 0.0, 0.0, 0.0,
                frame.delta_ns / 1_000_000_000.0, 0.0,
                tracker.orientation_validity / 3.0,
                float(tracker.angular_velocity_provenance), float(tracker.drift_provenance),
                float(in_reset and tracker_id == window.session_tracker_id), 0.0, 1.0,
            ])
            validity_slots.append([
                *([orientation_valid] * 4), False, False, False, False, True,
                True, True, True, True, True, True, True,
            ])
        frames.append(feature_slots)
        validity.append(validity_slots)
    target_slot = tracker_ids.index(window.session_tracker_id)
    targets = [[0.0, 0.0, 0.0] for _ in tracker_ids]
    targets[target_slot] = list(_rotation_vector(window.correction_xyzw))
    policy = window.training_policy.upper()
    weight = 1.0 if policy == "INCLUDE" else 0.25 if policy == "DOWNWEIGHT" else 0.0
    weights = [0.0 for _ in tracker_ids]
    weights[target_slot] = weight
    confidences = [0.0 for _ in tracker_ids]
    confidences[target_slot] = 1.0 if weight > 0 else 0.0
    return {
        "sample_id": f"real-{_stable_id(session.archive_sha256, window.event_index, window.session_tracker_id)}",
        "split": "unassigned",
        "domain": "real",
        "source_sha256": session.archive_sha256,
        "group": {
            "session_id": session.manifest.get("sessionId", session.archive_sha256),
            "subject_id": session.manifest.get("subjectPseudonym", "unknown"),
            "device_cohort": session.manifest.get("deviceCohort", "UNKNOWN"),
            "layout": str(session.manifest.get("trackerLayout", len(tracker_ids))),
            "chipset": session.manifest.get("chipset", "UNKNOWN"),
            "transport": session.manifest.get("transport", "UNKNOWN"),
        },
        "activity": session.manifest.get("activity", "UNKNOWN"),
        "activity_confidence": float(session.manifest.get("activityConfidence", 0.0)),
        "drift_severity": session.manifest.get("driftSeverity", "UNKNOWN"),
        "features": frames,
        "role_ids": list(range(1, len(tracker_ids) + 1)),
        "slot_mask": [True] * len(tracker_ids),
        "channel_validity": validity,
        "time_deltas_s": [frame.delta_ns / 1_000_000_000.0 for frame in selected],
        "target_correction_rotation_vectors": targets,
        "target_confidence": confidences,
        "loss_weights": weights,
        "masks": {
            "reset_window": {"pre": list(window.pre_range), "post": list(window.post_range)},
            "axis_mask": window.axis_mask,
            "quality_flags": window.quality_flags,
        },
        "quality_decision": {"policy": policy, "weight": weight},
    }


def prepare_real_session(session: PreparedSession, max_slots: int = 16) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    tracker_ids = tuple(sorted({tracker.session_tracker_id for frame in session.frames for tracker in frame.trackers}))
    if not tracker_ids:
        raise ValueError("validated real archive contains no tracker samples")
    if len(tracker_ids) > max_slots:
        raise ValueError(f"validated real archive has {len(tracker_ids)} trackers; maximum is {max_slots}")
    examples = [_real_example(session, window, tracker_ids) for window in session.reset_windows]
    source = {
        "kind": "nvrdata",
        "source_sha256": session.archive_sha256,
        "quality_report": session.quality_report,
        "quality_decision": (
            "INCLUDE"
            if any(example["quality_decision"]["weight"] > 0.0 for example in examples)
            else "EXCLUDE_NO_USABLE_RESET_WINDOWS"
        ),
    }
    return source, examples


def write_canonical_preparation(
    sources: Sequence[dict[str, Any]],
    examples: Sequence[dict[str, Any]],
    output: str | Path,
) -> None:
    payload = {
        "format": TRAINING_INPUT_FORMAT,
        "preparation_format": PREPARATION_FORMAT,
        "feature_schema": list(FEATURE_SCHEMA),
        "sources": list(sources),
        "examples": list(examples),
    }
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
