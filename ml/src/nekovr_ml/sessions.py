from __future__ import annotations

from dataclasses import asdict, dataclass
import hashlib
import importlib
import io
import json
from pathlib import Path
import sys
from typing import Any
import zipfile


@dataclass(frozen=True)
class SessionTrackerFrame:
    session_tracker_id: str
    raw_orientation_xyzw: tuple[float, float, float, float]
    pre_ai_orientation_xyzw: tuple[float, float, float, float]
    orientation_validity: int
    angular_velocity_provenance: int
    drift_provenance: int


@dataclass(frozen=True)
class SessionFrame:
    index: int
    monotonic_ns: int
    delta_ns: int
    trackers: tuple[SessionTrackerFrame, ...]


@dataclass(frozen=True)
class SessionResetWindow:
    event_index: int
    session_tracker_id: str
    reset_domain: str
    correction_xyzw: tuple[float, float, float, float]
    axis_mask: int
    quality_flags: int
    training_policy: str
    pre_range: tuple[int, int]
    post_range: tuple[int, int]


@dataclass(frozen=True)
class PreparedSession:
    archive_sha256: str
    manifest: dict[str, Any]
    frames: tuple[SessionFrame, ...]
    reset_windows: tuple[SessionResetWindow, ...]
    quality_report: dict[str, Any]

    def to_json_dict(self) -> dict[str, Any]:
        return asdict(self)


def _dataset_module():
    try:
        return importlib.import_module("nekovr_dataset")
    except ImportError:
        repository_reader = Path(__file__).resolve().parents[3] / "dataset" / "python"
        if repository_reader.is_dir():
            sys.path.insert(0, str(repository_reader))
            return importlib.import_module("nekovr_dataset")
        raise RuntimeError("canonical nekovr_dataset reader is unavailable; run from the NekoVR repository")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_session(path: str | Path) -> PreparedSession:
    """Validate and decode a canonical archive without using corrected output as input."""
    archive_path = Path(path)
    module = _dataset_module()
    reader = module.DatasetReader()
    report = reader.inspect_archive(archive_path)
    if not report.get("valid") or report.get("fatalFindings"):
        raise module.DatasetFormatError(f"fatal archive findings: {report.get('fatalFindings', [])}")
    generated = importlib.import_module("nekovr_dataset.dataset_v1_generated")
    with zipfile.ZipFile(archive_path) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        payload = reader._decompress_zstd(archive.read("telemetry.fbs.zst"))
    frames: list[SessionFrame] = []
    for record in reader.records(io.BytesIO(payload)):
        if record.record_type != generated.FRAME_BATCH or record.frame_batch is None:
            continue
        batch = record.frame_batch
        for frame_index in range(batch.vector_length(1)):
            table = batch.vector_table(1, frame_index)
            trackers = []
            for tracker_index in range(table.vector_length(4)):
                tracker = table.vector_table(4, tracker_index)
                trackers.append(SessionTrackerFrame(
                    session_tracker_id=tracker.string(0) or "",
                    raw_orientation_xyzw=(generated.half(tracker.table(1), 0), generated.half(tracker.table(1), 1), generated.half(tracker.table(1), 2), generated.half(tracker.table(1), 3, 1.0)),
                    pre_ai_orientation_xyzw=(generated.half(tracker.table(2), 0), generated.half(tracker.table(2), 1), generated.half(tracker.table(2), 2), generated.half(tracker.table(2), 3, 1.0)),
                    orientation_validity=tracker.u8(8),
                    angular_velocity_provenance=tracker.u8(11),
                    drift_provenance=tracker.u8(12),
                ))
            frames.append(SessionFrame(table.u64(0), table.u64(1), table.u64(2), tuple(trackers)))
    labels = tuple(reader.reset_labels(io.BytesIO(payload)))
    windows = tuple(SessionResetWindow(
        event_index=label.event_index,
        session_tracker_id=label.session_tracker_id,
        reset_domain=label.reset_domain,
        correction_xyzw=label.correction_xyzw,
        axis_mask=label.axis_mask,
        quality_flags=label.quality_flags,
        training_policy=label.training_policy,
        pre_range=(label.pre_start_frame, label.pre_end_frame),
        post_range=(label.post_start_frame, label.post_end_frame),
    ) for label in labels)
    tracker_counts = {tracker["sessionTrackerId"]: 0 for tracker in manifest.get("trackers", [])}
    valid_orientation_samples = 0
    provenance_counts: dict[str, int] = {}
    for frame in frames:
        for tracker in frame.trackers:
            tracker_counts[tracker.session_tracker_id] = tracker_counts.get(tracker.session_tracker_id, 0) + 1
            valid_orientation_samples += int(tracker.orientation_validity == 3)
            key = str(tracker.drift_provenance)
            provenance_counts[key] = provenance_counts.get(key, 0) + 1
    quality_report = {
        "frames": len(frames),
        "roster_size": len(manifest.get("trackers", [])),
        "channel_ids": sorted(int(v) for v in manifest.get("channelIds", [])),
        "reset_labels": len(windows),
        "valid_reset_windows": len(report.get("validResetWindows", [])),
        "valid_orientation_samples": valid_orientation_samples,
        "tracker_sample_counts": tracker_counts,
        "drift_provenance_counts": provenance_counts,
        "archive_quality": manifest.get("quality", {}),
    }
    return PreparedSession(_sha256(archive_path), manifest, tuple(frames), windows, quality_report)


def write_prepared_sessions(paths: list[str | Path], output: str | Path) -> None:
    prepared = [load_session(path).to_json_dict() for path in paths]
    Path(output).write_text(json.dumps({"format": "nekovr-prepared-sessions-v1", "sessions": prepared}, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")

