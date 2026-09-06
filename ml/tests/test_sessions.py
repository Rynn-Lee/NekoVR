from dataclasses import dataclass
import hashlib
import json
import types
import zipfile

import nekovr_ml.sessions as sessions


class FakeTable:
    def __init__(self, strings=None, tables=None, u8s=None, u64s=None, vectors=None):
        self.strings = strings or {}
        self.tables = tables or {}
        self.u8s = u8s or {}
        self.u64s = u64s or {}
        self.vectors = vectors or {}

    def string(self, index):
        return self.strings.get(index)

    def table(self, index):
        return self.tables.get(index)

    def u8(self, index, default=0):
        return self.u8s.get(index, default)

    def u64(self, index, default=0):
        return self.u64s.get(index, default)

    def vector_length(self, index):
        return len(self.vectors.get(index, ()))

    def vector_table(self, index, element):
        return self.vectors[index][element]


@dataclass
class FakeLabel:
    event_index: int = 1
    session_tracker_id: str = "tracker-1"
    reset_domain: str = "YAW"
    correction_xyzw: tuple = (0.0, 0.1, 0.0, 0.995)
    axis_mask: int = 1
    quality_flags: int = 0
    training_policy: str = "INCLUDE"
    pre_start_frame: int = 0
    pre_end_frame: int = 0
    post_start_frame: int = 0
    post_end_frame: int = 0


def test_session_loader_validates_then_extracts_pre_ai_and_provenance(tmp_path, monkeypatch):
    archive = tmp_path / "session.nvrdata"
    manifest = {"trackers": [{"sessionTrackerId": "tracker-1"}], "channelIds": [1, 2], "quality": {"writtenFrames": 1}}
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr("manifest.json", json.dumps(manifest))
        output.writestr("telemetry.fbs.zst", b"telemetry")
    quat = FakeTable()
    tracker = FakeTable(strings={0: "tracker-1"}, tables={1: quat, 2: quat}, u8s={8: 3, 11: 3, 12: 7})
    frame = FakeTable(u64s={0: 0, 1: 100, 2: 20}, vectors={4: [tracker]})
    batch = FakeTable(vectors={1: [frame]})
    record = types.SimpleNamespace(record_type=2, frame_batch=batch)

    class FakeReader:
        def inspect_archive(self, path):
            return {"valid": True, "fatalFindings": [], "validResetWindows": [{}]}

        def _decompress_zstd(self, data):
            return data

        def records(self, stream):
            return iter([record])

        def reset_labels(self, stream):
            return iter([FakeLabel()])

    fake_module = types.SimpleNamespace(DatasetReader=FakeReader, DatasetFormatError=ValueError)
    fake_generated = types.SimpleNamespace(FRAME_BATCH=2, half=lambda table, index, default=0.0: 1.0 if index == 3 else 0.0)
    monkeypatch.setattr(sessions, "_dataset_module", lambda: fake_module)
    real_import = sessions.importlib.import_module
    monkeypatch.setattr(sessions.importlib, "import_module", lambda name: fake_generated if name == "nekovr_dataset.dataset_v1_generated" else real_import(name))
    prepared = sessions.load_session(archive)
    assert prepared.archive_sha256 == hashlib.sha256(archive.read_bytes()).hexdigest()
    assert prepared.frames[0].trackers[0].pre_ai_orientation_xyzw == (0.0, 0.0, 0.0, 1.0)
    assert prepared.frames[0].trackers[0].drift_provenance == 7
    assert prepared.reset_windows[0].axis_mask == 1
    assert prepared.quality_report["valid_reset_windows"] == 1
