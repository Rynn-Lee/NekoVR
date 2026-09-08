from __future__ import annotations

from dataclasses import replace

import pytest

from nekovr_ml.personal_data import (
    EligibilityPolicy,
    ExcludedRange,
    PersonalWindow,
    SessionEligibilityInput,
    analyze_session_eligibility,
    balanced_window_stream,
    create_personal_split,
    load_personal_split,
    write_personal_split,
)


BASE = "a" * 64
FEATURES = "b" * 64


def _session(index: int, **changes: object) -> SessionEligibilityInput:
    value = SessionEligibilityInput(
        session_sha256=f"{index:x}" * 64,
        profile_id="profile-one",
        started_utc=f"2026-09-0{index}T00:00:00Z",
        schema_major=1,
        integrity_valid=True,
        fatal_findings=(),
        feature_schema_sha256=FEATURES,
        compatible_base_hashes=(BASE,),
        duration_s=3600,
        valid_duration_s=3500,
        usable_windows=120,
        reset_labels=2,
        clean_interval_s=600,
        stable_body_assignments=True,
        body_role_ids=(1, 2, 3, 4, 5),
        sensor_families=("BMI160",),
        layout=(1, 2, 3, 4, 5),
        activity_seconds={name: 60 for name in ("standing", "seated", "lying", "crouching", "transition", "locomotion", "dance", "stationary")},
        channel_quality=0.98,
        excluded_ranges=(ExcludedRange(10, 20, "packet gap"),),
    )
    return replace(value, **changes)


def _policy() -> EligibilityPolicy:
    return EligibilityPolicy(1, FEATURES, BASE)


def test_eligibility_reports_usable_evidence_coverage_and_exclusions():
    sessions = [_session(1), _session(2, sensor_families=("BNO085",)), _session(3)]
    report = analyze_session_eligibility(sessions, "profile-one", _policy())

    assert report.ready
    assert report.usable_seconds == 10_500
    assert report.usable_windows == 360
    assert report.reset_labels == 6
    assert report.clean_interval_s == 1800
    assert report.sensor_families == ("BMI160", "BNO085")
    assert report.excluded_ranges == 3
    assert report.eligible_session_hashes == tuple(session.session_sha256 for session in sessions)


def test_eligibility_excludes_integrity_profile_feature_assignment_and_quality_failures():
    sessions = [
        _session(1),
        _session(2, integrity_valid=False, fatal_findings=("checksum",)),
        _session(3, profile_id="profile-two"),
        _session(4, feature_schema_sha256="c" * 64),
        _session(5, stable_body_assignments=False),
        _session(6, channel_quality=0.1),
    ]
    report = analyze_session_eligibility(sessions, "profile-one", _policy())

    assert not report.ready
    assert report.eligible_session_hashes == (sessions[0].session_sha256,)
    codes = {finding.code for session in report.sessions for finding in session.findings}
    assert {"integrity", "profile", "feature_schema", "body_assignment", "channel_quality"} <= codes
    assert any(finding.code == "held_out_sessions" and finding.severity == "blocking" for finding in report.findings)


def test_personal_split_uses_recent_whole_session_holdouts_and_resumable_balancing(tmp_path):
    sessions = [_session(1), _session(2), _session(3), _session(4)]
    report = analyze_session_eligibility(sessions, "profile-one", _policy())
    windows = []
    for session_index, session in enumerate(sessions):
        for window_index, (activity, evidence) in enumerate((("standing", "clean"), ("dance", "reset"))):
            windows.append(PersonalWindow(
                f"w-{session_index}-{window_index}", session.session_sha256, window_index * 10, window_index * 10 + 5,
                activity, session.layout, evidence, 1.0,
            ))

    manifest = create_personal_split(report, sessions, windows, seed=42)

    assert manifest.session_assignments[sessions[3].session_sha256] == "test"
    assert manifest.session_assignments[sessions[2].session_sha256] == "validation"
    assert {manifest.session_assignments[sessions[0].session_sha256], manifest.session_assignments[sessions[1].session_sha256]} == {"train"}
    for window in windows:
        assert manifest.window_assignments[window.window_id] == manifest.session_assignments[window.session_sha256]
    first = list(balanced_window_stream(manifest, windows, "train", epoch=3))
    resumed = list(balanced_window_stream(manifest, windows, "train", epoch=3, offset=2))
    assert resumed == first[2:]
    assert list(balanced_window_stream(manifest, windows, "train", epoch=3)) == first
    assert {window.activity for window in first[:2]} == {"standing", "dance"}
    output = tmp_path / "split.json"
    write_personal_split(manifest, output)
    assert load_personal_split(output) == manifest
    output.write_text(output.read_text().replace(manifest.sha256, "0" * 64), encoding="utf-8")
    with pytest.raises(ValueError, match="hash mismatch"):
        load_personal_split(output)


def test_production_split_refuses_inseparable_holdout_and_overlapping_windows():
    session = _session(1)
    report = analyze_session_eligibility([session], "profile-one", _policy())
    window = PersonalWindow("w-1", session.session_sha256, 0, 10, "standing", session.layout, "clean", 1.0)
    with pytest.raises(ValueError, match="three separable sessions"):
        create_personal_split(report, [session], [window], 1)

    sessions = [_session(1), _session(2), _session(3)]
    report = analyze_session_eligibility(sessions, "profile-one", _policy())
    overlapping = [
        PersonalWindow("w-1", sessions[0].session_sha256, 0, 10, "standing", sessions[0].layout, "clean", 1.0),
        PersonalWindow("w-2", sessions[0].session_sha256, 9, 20, "dance", sessions[0].layout, "reset", 1.0),
        PersonalWindow("w-3", sessions[1].session_sha256, 0, 10, "standing", sessions[1].layout, "clean", 1.0),
        PersonalWindow("w-4", sessions[2].session_sha256, 0, 10, "standing", sessions[2].layout, "clean", 1.0),
    ]
    with pytest.raises(ValueError, match="must not overlap"):
        create_personal_split(report, sessions, overlapping, 1)
