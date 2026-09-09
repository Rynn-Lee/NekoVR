from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import random
import re
from typing import Iterable, Iterator, Mapping, Sequence


ACTIVITIES = (
    "standing",
    "seated",
    "lying",
    "crouching",
    "transition",
    "locomotion",
    "dance",
    "stationary",
)
SPLITS = ("train", "validation", "test")
_HASH = re.compile(r"^[0-9a-f]{64}$")
MAX_PERSONAL_SESSION_MANIFEST_BYTES = 1024 * 1024


@dataclass(frozen=True)
class ExcludedRange:
    start_s: float
    end_s: float
    reason: str

    def __post_init__(self) -> None:
        if not (math.isfinite(self.start_s) and math.isfinite(self.end_s) and 0 <= self.start_s < self.end_s):
            raise ValueError("excluded range must be a finite positive interval")
        if not self.reason:
            raise ValueError("excluded range needs a reason")


@dataclass(frozen=True)
class SessionEligibilityInput:
    session_sha256: str
    profile_id: str
    started_utc: str
    schema_major: int
    integrity_valid: bool
    fatal_findings: tuple[str, ...]
    feature_schema_sha256: str
    compatible_base_hashes: tuple[str, ...]
    duration_s: float
    valid_duration_s: float
    usable_windows: int
    reset_labels: int
    clean_interval_s: float
    stable_body_assignments: bool
    body_role_ids: tuple[int, ...]
    sensor_families: tuple[str, ...]
    layout: tuple[int, ...]
    activity_seconds: Mapping[str, float]
    channel_quality: float
    excluded_ranges: tuple[ExcludedRange, ...] = ()

    def __post_init__(self) -> None:
        for value in (self.session_sha256, self.feature_schema_sha256, *self.compatible_base_hashes):
            if not _HASH.fullmatch(value):
                raise ValueError("session compatibility hashes must be lowercase SHA-256")
        datetime.fromisoformat(self.started_utc.replace("Z", "+00:00"))
        if not all(math.isfinite(value) and value >= 0 for value in (self.duration_s, self.valid_duration_s, self.clean_interval_s)):
            raise ValueError("session durations must be finite and non-negative")
        if self.valid_duration_s > self.duration_s or self.usable_windows < 0 or self.reset_labels < 0:
            raise ValueError("session counters are inconsistent")
        if not 0 <= self.channel_quality <= 1:
            raise ValueError("channel quality must be in [0, 1]")
        if len(set(self.body_role_ids)) != len(self.body_role_ids) or tuple(sorted(self.body_role_ids)) != self.body_role_ids:
            raise ValueError("body roles must be unique and canonical")
        if self.layout != self.body_role_ids or not self.layout:
            raise ValueError("layout must contain the canonical assigned body roles")
        if any(name not in ACTIVITIES or not math.isfinite(seconds) or seconds < 0 for name, seconds in self.activity_seconds.items()):
            raise ValueError("activity coverage is invalid")


def iter_session_eligibility_manifests(paths: Iterable[str | Path]) -> Iterator[SessionEligibilityInput]:
    """Read bounded session summaries one at a time; canonical telemetry is never materialized here."""
    seen: set[Path] = set()
    for value in paths:
        path = Path(value).resolve()
        if path in seen:
            raise ValueError("personal session manifest paths must be unique")
        seen.add(path)
        if not path.is_file() or path.is_symlink() or path.stat().st_size > MAX_PERSONAL_SESSION_MANIFEST_BYTES:
            raise ValueError("personal session manifest must be a bounded regular file")
        with path.open("rb") as stream:
            encoded = stream.read(MAX_PERSONAL_SESSION_MANIFEST_BYTES + 1)
        if len(encoded) > MAX_PERSONAL_SESSION_MANIFEST_BYTES:
            raise ValueError("personal session manifest exceeds the size limit")
        try:
            payload = json.loads(encoded.decode("utf-8"))
            if payload.pop("format") != "nekovr-personal-session-summary-v1" or payload.pop("schema_version") != 1:
                raise ValueError("unsupported personal session manifest")
            payload["fatal_findings"] = tuple(payload["fatal_findings"])
            payload["compatible_base_hashes"] = tuple(payload["compatible_base_hashes"])
            payload["body_role_ids"] = tuple(int(item) for item in payload["body_role_ids"])
            payload["sensor_families"] = tuple(payload["sensor_families"])
            payload["layout"] = tuple(int(item) for item in payload["layout"])
            payload["excluded_ranges"] = tuple(ExcludedRange(**item) for item in payload.get("excluded_ranges", ()))
            yield SessionEligibilityInput(**payload)
        except (KeyError, TypeError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError(f"invalid personal session manifest: {path.name}") from error


@dataclass(frozen=True)
class EligibilityPolicy:
    schema_major: int
    feature_schema_sha256: str
    base_model_sha256: str
    minimum_session_valid_seconds: float = 300.0
    minimum_total_valid_seconds: float = 1800.0
    minimum_usable_windows: int = 100
    minimum_reset_labels: int = 1
    minimum_clean_seconds: float = 300.0
    minimum_channel_quality: float = 0.8
    minimum_activity_seconds: float = 30.0
    required_activities: tuple[str, ...] = ACTIVITIES

    def __post_init__(self) -> None:
        if not _HASH.fullmatch(self.feature_schema_sha256) or not _HASH.fullmatch(self.base_model_sha256):
            raise ValueError("eligibility policy hashes must be lowercase SHA-256")
        if any(activity not in ACTIVITIES for activity in self.required_activities):
            raise ValueError("unknown required activity")


@dataclass(frozen=True)
class EligibilityFinding:
    code: str
    severity: str
    message: str
    session_sha256: str | None = None


@dataclass(frozen=True)
class SessionEligibility:
    session_sha256: str
    eligible: bool
    usable_seconds: float
    usable_windows: int
    excluded_ranges: tuple[ExcludedRange, ...]
    findings: tuple[EligibilityFinding, ...]


@dataclass(frozen=True)
class EligibilityReport:
    profile_id: str
    base_model_sha256: str
    sessions: tuple[SessionEligibility, ...]
    usable_seconds: float
    usable_windows: int
    reset_labels: int
    clean_interval_s: float
    layouts: tuple[tuple[int, ...], ...]
    sensor_families: tuple[str, ...]
    activity_seconds: Mapping[str, float]
    excluded_ranges: int
    findings: tuple[EligibilityFinding, ...]
    ready: bool

    @property
    def eligible_session_hashes(self) -> tuple[str, ...]:
        return tuple(session.session_sha256 for session in self.sessions if session.eligible)

    @property
    def usable_hours(self) -> float:
        return self.usable_seconds / 3600.0


def analyze_session_eligibility(
    sessions: Sequence[SessionEligibilityInput],
    profile_id: str,
    policy: EligibilityPolicy,
) -> EligibilityReport:
    if not sessions:
        raise ValueError("eligibility analysis needs selected sessions")
    if len({session.session_sha256 for session in sessions}) != len(sessions):
        raise ValueError("selected session hashes must be unique")
    results: list[SessionEligibility] = []
    aggregate_findings: list[EligibilityFinding] = []
    eligible_inputs: list[SessionEligibilityInput] = []
    blocking_codes = {
        "integrity",
        "profile",
        "schema",
        "feature_schema",
        "base_model",
        "body_assignment",
        "duration",
        "windows",
        "channel_quality",
    }
    for session in sorted(sessions, key=lambda value: (value.started_utc, value.session_sha256)):
        findings: list[EligibilityFinding] = []

        def finding(code: str, severity: str, message: str) -> None:
            findings.append(EligibilityFinding(code, severity, message, session.session_sha256))

        if not session.integrity_valid or session.fatal_findings:
            finding("integrity", "blocking", "archive integrity validation failed")
        if session.profile_id != profile_id:
            finding("profile", "blocking", "session belongs to another personal profile")
        if session.schema_major != policy.schema_major:
            finding("schema", "blocking", "dataset major schema is incompatible")
        if session.feature_schema_sha256 != policy.feature_schema_sha256:
            finding("feature_schema", "blocking", "feature schema differs from the selected base")
        if policy.base_model_sha256 not in session.compatible_base_hashes:
            finding("base_model", "blocking", "session cannot be replayed with the selected base")
        if not session.stable_body_assignments:
            finding("body_assignment", "blocking", "body assignments are not stable")
        if session.valid_duration_s < policy.minimum_session_valid_seconds:
            finding("duration", "blocking", "valid duration is below policy")
        if session.usable_windows <= 0:
            finding("windows", "blocking", "session has no usable training windows")
        if session.channel_quality < policy.minimum_channel_quality:
            finding("channel_quality", "blocking", "channel quality is below policy")
        if session.reset_labels == 0:
            finding("reset_evidence", "warning", "session contains no usable reset labels")
        if session.clean_interval_s == 0:
            finding("clean_evidence", "warning", "session contains no clean no-reset interval")
        eligible = not any(item.code in blocking_codes for item in findings)
        results.append(SessionEligibility(
            session.session_sha256, eligible, session.valid_duration_s if eligible else 0.0,
            session.usable_windows if eligible else 0, session.excluded_ranges, tuple(findings),
        ))
        if eligible:
            eligible_inputs.append(session)

    usable_seconds = sum(session.valid_duration_s for session in eligible_inputs)
    usable_windows = sum(session.usable_windows for session in eligible_inputs)
    reset_labels = sum(session.reset_labels for session in eligible_inputs)
    clean_seconds = sum(session.clean_interval_s for session in eligible_inputs)
    activity = {name: sum(session.activity_seconds.get(name, 0.0) for session in eligible_inputs) for name in ACTIVITIES}
    if usable_seconds < policy.minimum_total_valid_seconds:
        aggregate_findings.append(EligibilityFinding("total_duration", "blocking", "total usable duration is below policy"))
    if usable_windows < policy.minimum_usable_windows:
        aggregate_findings.append(EligibilityFinding("total_windows", "blocking", "usable window count is below policy"))
    if reset_labels < policy.minimum_reset_labels:
        aggregate_findings.append(EligibilityFinding("total_resets", "blocking", "reset-label evidence is below policy"))
    if clean_seconds < policy.minimum_clean_seconds:
        aggregate_findings.append(EligibilityFinding("total_clean", "blocking", "clean no-reset evidence is below policy"))
    for name in policy.required_activities:
        if activity[name] < policy.minimum_activity_seconds:
            aggregate_findings.append(EligibilityFinding("activity_coverage", "warning", f"activity {name} is underrepresented"))
    if len(eligible_inputs) < 3:
        aggregate_findings.append(EligibilityFinding("held_out_sessions", "blocking", "at least three separable eligible sessions are required for production holdouts"))
    ready = bool(eligible_inputs) and not any(item.severity == "blocking" for item in aggregate_findings)
    return EligibilityReport(
        profile_id=profile_id,
        base_model_sha256=policy.base_model_sha256,
        sessions=tuple(results),
        usable_seconds=usable_seconds,
        usable_windows=usable_windows,
        reset_labels=reset_labels,
        clean_interval_s=clean_seconds,
        layouts=tuple(sorted({session.layout for session in eligible_inputs})),
        sensor_families=tuple(sorted({family for session in eligible_inputs for family in session.sensor_families})),
        activity_seconds=activity,
        excluded_ranges=sum(len(session.excluded_ranges) for session in sessions),
        findings=tuple(aggregate_findings),
        ready=ready,
    )


@dataclass(frozen=True)
class PersonalWindow:
    window_id: str
    session_sha256: str
    start_s: float
    end_s: float
    activity: str
    layout: tuple[int, ...]
    evidence: str
    quality: float

    def __post_init__(self) -> None:
        if not self.window_id or not _HASH.fullmatch(self.session_sha256):
            raise ValueError("window identity is invalid")
        if not (math.isfinite(self.start_s) and math.isfinite(self.end_s) and 0 <= self.start_s < self.end_s):
            raise ValueError("window interval is invalid")
        if self.activity not in ACTIVITIES or self.evidence not in ("reset", "clean"):
            raise ValueError("window cohort is invalid")
        if not 0 <= self.quality <= 1:
            raise ValueError("window quality must be in [0, 1]")


@dataclass(frozen=True)
class PersonalSplitManifest:
    schema_version: int
    seed: int
    session_assignments: Mapping[str, str]
    window_assignments: Mapping[str, str]
    session_started_utc: Mapping[str, str]
    production_grade: bool
    sha256: str


def _split_payload(manifest: PersonalSplitManifest) -> dict[str, object]:
    return {
        "schema_version": manifest.schema_version,
        "seed": manifest.seed,
        "session_assignments": dict(sorted(manifest.session_assignments.items())),
        "window_assignments": dict(sorted(manifest.window_assignments.items())),
        "session_started_utc": dict(sorted(manifest.session_started_utc.items())),
        "production_grade": manifest.production_grade,
    }


def validate_personal_split(manifest: PersonalSplitManifest) -> None:
    if manifest.schema_version != 1 or set(manifest.session_assignments.values()) - set(SPLITS):
        raise ValueError("personal split schema or assignment is invalid")
    if set(manifest.session_assignments) != set(manifest.session_started_utc):
        raise ValueError("personal split session timestamps are incomplete")
    if any(split not in manifest.session_assignments.values() for split in SPLITS) and manifest.production_grade:
        raise ValueError("production split must contain train, validation, and test sessions")
    expected = hashlib.sha256(json.dumps(_split_payload(manifest), sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    if not hmac.compare_digest(expected, manifest.sha256):
        raise ValueError("personal split manifest hash mismatch")


def write_personal_split(manifest: PersonalSplitManifest, path: str | Path) -> None:
    validate_personal_split(manifest)
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".partial")
    try:
        temporary.write_text(json.dumps({**_split_payload(manifest), "sha256": manifest.sha256}, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
        with temporary.open("r+b") as stream:
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def load_personal_split(path: str | Path) -> PersonalSplitManifest:
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if set(value) != {"schema_version", "seed", "session_assignments", "window_assignments", "session_started_utc", "production_grade", "sha256"}:
        raise ValueError("personal split manifest fields differ from schema")
    manifest = PersonalSplitManifest(**value)
    validate_personal_split(manifest)
    return manifest


def create_personal_split(
    report: EligibilityReport,
    selected_sessions: Sequence[SessionEligibilityInput],
    windows: Sequence[PersonalWindow],
    seed: int,
    production_grade: bool = True,
) -> PersonalSplitManifest:
    eligible = set(report.eligible_session_hashes)
    selected = {session.session_sha256: session for session in selected_sessions if session.session_sha256 in eligible}
    if production_grade and (not report.ready or len(selected) < 3):
        raise ValueError("production split requires a ready eligibility report and three separable sessions")
    if len(selected) < 1:
        raise ValueError("split requires an eligible session")
    if len({window.window_id for window in windows}) != len(windows):
        raise ValueError("window IDs must be unique")
    relevant = [window for window in windows if window.session_sha256 in selected]
    if any(window.session_sha256 not in selected for window in windows):
        raise ValueError("window belongs to an ineligible or unselected session")
    by_session = {session_hash: [window for window in relevant if window.session_sha256 == session_hash] for session_hash in selected}
    if any(not values for values in by_session.values()):
        raise ValueError("every split session needs at least one usable window")
    for values in by_session.values():
        ordered = sorted(values, key=lambda value: (value.start_s, value.end_s, value.window_id))
        if any(left.end_s > right.start_s for left, right in zip(ordered, ordered[1:])):
            raise ValueError("usable windows within a session must not overlap")
    ordered_sessions = sorted(selected.values(), key=lambda value: (value.started_utc, value.session_sha256), reverse=True)
    assignments: dict[str, str] = {}
    if len(ordered_sessions) >= 3:
        assignments[ordered_sessions[0].session_sha256] = "test"
        assignments[ordered_sessions[1].session_sha256] = "validation"
        for session in ordered_sessions[2:]:
            assignments[session.session_sha256] = "train"
    elif len(ordered_sessions) == 2:
        assignments[ordered_sessions[0].session_sha256] = "test"
        assignments[ordered_sessions[1].session_sha256] = "train"
    else:
        assignments[ordered_sessions[0].session_sha256] = "train"
    window_assignments = {window.window_id: assignments[window.session_sha256] for window in relevant}
    if any(len({window_assignments[window.window_id] for window in relevant if window.session_sha256 == session_hash}) != 1 for session_hash in selected):
        raise AssertionError("whole-session leakage detected")
    stable: dict[str, object] = {
        "schema_version": 1,
        "seed": seed,
        "session_assignments": dict(sorted(assignments.items())),
        "window_assignments": dict(sorted(window_assignments.items())),
        "session_started_utc": dict(sorted((key, value.started_utc) for key, value in selected.items())),
        "production_grade": production_grade,
    }
    digest = hashlib.sha256(json.dumps(stable, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    manifest = PersonalSplitManifest(
        schema_version=1, seed=seed,
        session_assignments=assignments, window_assignments=window_assignments,
        session_started_utc={key: value.started_utc for key, value in selected.items()},
        production_grade=production_grade, sha256=digest,
    )
    validate_personal_split(manifest)
    return manifest


def balanced_window_stream(
    manifest: PersonalSplitManifest,
    windows: Sequence[PersonalWindow],
    split: str,
    epoch: int,
    offset: int = 0,
) -> Iterator[PersonalWindow]:
    """Yield only window descriptors, deterministically balanced across activity/layout/evidence cohorts."""
    if split not in SPLITS or epoch < 0 or offset < 0:
        raise ValueError("invalid sampler state")
    validate_personal_split(manifest)
    selected = [window for window in windows if manifest.window_assignments.get(window.window_id) == split]
    cohorts: dict[tuple[str, tuple[int, ...], str], list[PersonalWindow]] = {}
    for window in selected:
        cohorts.setdefault((window.activity, window.layout, window.evidence), []).append(window)
    rng = random.Random(f"{manifest.seed}:{manifest.sha256}:{split}:{epoch}")
    for values in cohorts.values():
        values.sort(key=lambda value: value.window_id)
        rng.shuffle(values)
    ordered: list[PersonalWindow] = []
    keys = sorted(cohorts)
    maximum = max((len(values) for values in cohorts.values()), default=0)
    for index in range(maximum):
        round_keys = list(keys)
        rng.shuffle(round_keys)
        for key in round_keys:
            values = cohorts[key]
            ordered.append(values[index % len(values)])
    yield from ordered[offset:]
