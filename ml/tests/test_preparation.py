import json

from nekovr_ml import cli
from nekovr_ml.amass import CanonicalMotion, MotionFrame
from nekovr_ml.layouts import LAYOUT_ROLES
from nekovr_ml.preparation import (
    FEATURE_SCHEMA,
    PREPARATION_FORMAT,
    prepare_amass_motion,
    prepare_real_session,
    write_canonical_preparation,
)
from nekovr_ml.sessions import PreparedSession, SessionFrame, SessionResetWindow, SessionTrackerFrame
from nekovr_ml.simulation import SimulationConfig


def _motion():
    role = "body:hip"
    frames = (
        MotionFrame(0.0, {role: (0.0, 0.0, 0.0, 1.0)}, {role: (0.0, 0.0, 0.0)}),
        MotionFrame(0.02, {role: (0.0, 0.1, 0.0, 0.995)}, {role: (0.01, 0.0, 0.0)}),
    )
    return CanonicalMotion(frames, 50, "a" * 64, "b" * 64, "AMASS", "SMPL")


def _session():
    tracker = SessionTrackerFrame(
        "tracker-1", (0.0, 0.0, 0.0, 1.0), (0.0, 0.0, 0.0, 1.0), 3, 2, 4
    )
    return PreparedSession(
        "c" * 64,
        {"sessionId": "session-1", "subjectPseudonym": "person-1"},
        (SessionFrame(0, 100, 20_000_000, (tracker,)), SessionFrame(1, 200, 20_000_000, (tracker,))),
        (SessionResetWindow(7, "tracker-1", "YAW", (0.0, 0.1, 0.0, 0.995), 1, 0, "INCLUDE", (0, 1), (1, 2)),),
        {"valid_reset_windows": 1},
    )


def test_amass_simulation_and_real_archive_share_one_training_example_contract(tmp_path):
    synthetic_source, synthetic = prepare_amass_motion(
        _motion(), [("body:hip",)], 1,
        SimulationConfig(
            mounting_max_degrees=0.0,
            orientation_noise_degrees=0.0,
            bias_degrees_per_second=0.0,
            random_walk_degrees_per_sqrt_second=0.0,
            temperature_drift_degrees_per_celsius=0.0,
            latency_frames_max=0,
            packet_loss_probability=0.0,
            stale_probability=0.0,
            channel_unavailable_probability=0.0,
            reset_probability_per_second=0.0,
        ),
        4,
    )
    real_source, real = prepare_real_session(_session())
    output = tmp_path / "prepared.json"
    write_canonical_preparation([synthetic_source, real_source], [*synthetic, *real], output)
    payload = json.loads(output.read_text())
    assert payload["format"] == "nekovr-training-input-v1"
    assert payload["preparation_format"] == PREPARATION_FORMAT
    assert payload["feature_schema"] == list(FEATURE_SCHEMA)
    assert {example["domain"] for example in payload["examples"]} == {"synthetic", "real"}
    for example in payload["examples"]:
        assert example["source_sha256"] in {"a" * 64, "c" * 64}
        assert len(example["features"][0][0]) == len(FEATURE_SCHEMA)
        assert len(example["channel_validity"][0][0]) == len(FEATURE_SCHEMA)
        assert example["quality_decision"]["policy"] == "INCLUDE"
    assert real[0]["masks"]["reset_window"] == {"pre": [0, 1], "post": [1, 2]}
    assert real[0]["loss_weights"] == [1.0]


def test_real_quality_policy_is_carried_into_loss_mask():
    session = _session()
    excluded = SessionResetWindow(8, "tracker-1", "YAW", (0.0, 0.0, 0.0, 1.0), 1, 64, "EXCLUDE", (0, 1), (1, 2))
    source, examples = prepare_real_session(
        PreparedSession(session.archive_sha256, session.manifest, session.frames, (excluded,), session.quality_report)
    )
    assert source["source_sha256"] == "c" * 64
    assert examples[0]["quality_decision"] == {"policy": "EXCLUDE", "weight": 0.0}
    assert examples[0]["loss_weights"] == [0.0]


def test_one_prepare_cli_combines_amass_simulation_and_validated_archives(tmp_path, monkeypatch):
    roles = LAYOUT_ROLES[5]
    frame = MotionFrame(
        0.0,
        {role: (0.0, 0.0, 0.0, 1.0) for role in roles},
        {role: (0.0, 0.0, 0.0) for role in roles},
    )
    motion = CanonicalMotion((frame,), 50, "a" * 64, "b" * 64, "AMASS", "SMPL")
    monkeypatch.setattr(cli, "load_amass", lambda *args, **kwargs: motion)
    monkeypatch.setattr(cli, "load_session", lambda path: _session())
    output = tmp_path / "combined.json"
    assert cli._run([
        "prepare", "--amass", "licensed-motion.npz", "--body-model", "licensed-smpl.npz",
        "--archive", "validated.nvrdata", "--layout", "5", "--output", str(output),
    ]) == 0
    payload = json.loads(output.read_text())
    assert [source["kind"] for source in payload["sources"]] == ["amass", "nvrdata"]
    assert {example["domain"] for example in payload["examples"]} == {"synthetic", "real"}
