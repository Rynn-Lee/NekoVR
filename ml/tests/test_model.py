import math

import pytest

from nekovr_ml.model import CompactCausalModel, ModelConfig, SequenceSample, collate_variable_layout


def _sample(slot_values, roles=None, time=3):
    roles = roles or tuple(range(1, len(slot_values) + 1))
    features = tuple(tuple((value + frame, value - frame) for value in slot_values) for frame in range(time))
    validity = tuple(tuple((True, True) for _ in slot_values) for _ in range(time))
    return SequenceSample(features, tuple(roles), (True,) * len(slot_values), validity, (0.02,) * time)


def test_variable_layout_batching_left_pads_time_and_slots():
    batch = collate_variable_layout([_sample((1.0,), time=2), _sample((2.0, 3.0), time=3)], 2, 4)
    assert batch.time_mask[0] == (False, True, True)
    assert batch.slot_mask[0] == (True, False, False, False)
    assert batch.role_ids[1] == (1, 2, 0, 0)


def test_masked_slots_do_not_affect_context_and_outputs_are_bounded():
    model = CompactCausalModel(ModelConfig(feature_count=2, hidden_size=6, max_slots=4), seed=4)
    sample = _sample((1.0, 2.0))
    batch = collate_variable_layout([sample], 2, 4)
    output = model.forward(batch)
    assert output.confidence[0][2:] == (0.0, 0.0)
    assert output.correction_rotation_vectors[0][2:] == ((0.0, 0.0, 0.0), (0.0, 0.0, 0.0))
    assert all(abs(value) <= model.config.maximum_correction_radians for vector in output.correction_rotation_vectors[0] for value in vector)


def test_slot_permutation_is_equivariant():
    model = CompactCausalModel(ModelConfig(feature_count=2, hidden_size=6, max_slots=2), seed=9)
    original = model.forward(collate_variable_layout([_sample((1.0, 5.0), roles=(7, 11))], 2, 2))
    permuted = model.forward(collate_variable_layout([_sample((5.0, 1.0), roles=(11, 7))], 2, 2))
    assert original.correction_rotation_vectors[0][0] == pytest.approx(permuted.correction_rotation_vectors[0][1])
    assert original.correction_rotation_vectors[0][1] == pytest.approx(permuted.correction_rotation_vectors[0][0])
    assert original.confidence[0][0] == pytest.approx(permuted.confidence[0][1])


def test_invalid_latest_channels_fail_closed_per_slot():
    sample = _sample((1.0,))
    validity = sample.channel_validity[:-1] + (((False, False),),)
    sample = SequenceSample(sample.features, sample.role_ids, sample.slot_mask, validity, sample.time_deltas_s)
    output = CompactCausalModel(ModelConfig(feature_count=2, max_slots=1), seed=1).forward(collate_variable_layout([sample], 2, 1))
    assert output.confidence == ((0.0,),)
    assert output.correction_rotation_vectors == (((0.0, 0.0, 0.0),),)
