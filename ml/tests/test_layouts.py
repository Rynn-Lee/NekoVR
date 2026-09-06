from nekovr_ml.amass import CanonicalMotion, MotionFrame
from nekovr_ml.layouts import LAYOUT_ROLES, generate_layout


def _motion():
    roles = {role for values in LAYOUT_ROLES.values() for role in values}
    frame = MotionFrame(0.0, {role: (0.0, 0.0, 0.0, 1.0) for role in roles}, {role: (0.0, 0.0, 0.0) for role in roles})
    return CanonicalMotion((frame,), 50, "source", "model", "AMASS", "SMPL")


def test_preset_layouts_have_stable_padded_masks():
    for count in (5, 6, 8, 10):
        layout = generate_layout(_motion(), count, max_slots=12)
        assert len(layout.roles) == count
        assert sum(layout.slot_mask) == count
        assert len(layout.frames[0].orientations_xyzw) == 12
        assert all(role_id > 0 for role_id in layout.role_ids[:count])
        assert all(role_id == 0 for role_id in layout.role_ids[count:])


def test_additional_explicit_layout_is_supported():
    roles = ("body:hip", "body:left_foot", "body:right_foot")
    assert generate_layout(_motion(), roles, max_slots=4).roles == roles

