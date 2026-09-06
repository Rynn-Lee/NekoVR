import math

import pytest

from nekovr_ml.amass import CanonicalMotion, MotionFrame
from nekovr_ml.layouts import generate_layout
from nekovr_ml.math3d import multiply
from nekovr_ml.simulation import SimulationConfig, simulate


def _layout():
    roles = ("body:hip",)
    frames = tuple(MotionFrame(i / 50, {roles[0]: (0.0, 0.0, 0.0, 1.0)}, {roles[0]: (0.0, 0.0, 0.0)}) for i in range(4))
    return generate_layout(CanonicalMotion(frames, 50, "s", "m", "AMASS", "SMPL"), roles, max_slots=1)


def test_corruption_is_reproducible_and_targets_invert_observation():
    config = SimulationConfig(packet_loss_probability=0.0, stale_probability=0.0, channel_unavailable_probability=0.0, latency_frames_max=0, reset_probability_per_second=0.0)
    first = simulate(_layout(), config, 123)
    second = simulate(_layout(), config, 123)
    assert first == second
    for frame in first:
        restored = multiply(frame.correction_target_xyzw[0], frame.observed_xyzw[0])
        assert restored == pytest.approx((0.0, 0.0, 0.0, 1.0), abs=1e-6)


def test_loss_and_stale_channels_are_explicitly_masked():
    config = SimulationConfig(packet_loss_probability=0.0, stale_probability=1.0, channel_unavailable_probability=0.0, latency_frames_max=0)
    frame = simulate(_layout(), config, 1)[0]
    assert frame.channel_valid_mask == (False,)
    assert frame.stale_mask == (True,)


def test_reset_schedule_and_temperature_drift_are_seeded():
    config = SimulationConfig(
        mounting_max_degrees=0.0,
        orientation_noise_degrees=0.0,
        bias_degrees_per_second=0.0,
        random_walk_degrees_per_sqrt_second=0.0,
        temperature_drift_degrees_per_celsius=0.1,
        latency_frames_max=0,
        packet_loss_probability=0.0,
        stale_probability=0.0,
        channel_unavailable_probability=0.0,
        reset_probability_per_second=100.0,
    )
    frames = simulate(_layout(), config, 9)
    assert frames == simulate(_layout(), config, 9)
    assert any(frame.reset_mask[0] for frame in frames[1:])
    assert frames[1].temperature_c[0] != frames[0].temperature_c[0]
