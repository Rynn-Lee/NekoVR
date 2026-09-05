from __future__ import annotations

from dataclasses import dataclass
import hashlib
import io
import json
import struct
from pathlib import Path
from typing import BinaryIO, Iterator
import zipfile

from .dataset_v1_generated import DatasetRecord, FILE_HEADER, FRAME_BATCH, EVENT_BATCH, FOOTER, half, float_quat


class DatasetFormatError(ValueError):
    pass


@dataclass(frozen=True)
class TrackerSample:
    session_tracker_id: str
    raw_orientation_xyzw: tuple[float, float, float, float]
    pre_ai_orientation_xyzw: tuple[float, float, float, float]
    final_orientation_xyzw: tuple[float, float, float, float]


@dataclass(frozen=True)
class Frame:
    index: int
    monotonic_ns: int
    delta_ns: int
    trackers: tuple[TrackerSample, ...]


@dataclass(frozen=True)
class DatasetEventRecord:
    type: str
    monotonic_ns: int
    frame_index: int
    session_tracker_id: str | None
    old_value: str | None
    new_value: str | None
    detail: str | None
    request_id: str | None
    request_monotonic_ns: int
    applied_monotonic_ns: int
    event_index: int
    reset_outcome: str | None
    reset_source: str | None
    reset_kind: str | None
    affected_body_parts: tuple[str, ...]


@dataclass(frozen=True)
class ResetLabel:
    event_index: int
    session_tracker_id: str
    correction_xyzw: tuple[float, float, float, float]
    diagnostic_yaw_radians: float
    axis_mask: int
    pre_start_frame: int
    pre_end_frame: int
    post_start_frame: int
    post_end_frame: int
    quality_flags: int
    request_id: str | None
    request_monotonic_ns: int
    applied_monotonic_ns: int
    reset_domain: str
    raw_before_xyzw: tuple[float, float, float, float]
    raw_after_xyzw: tuple[float, float, float, float]
    pre_ai_before_xyzw: tuple[float, float, float, float]
    pre_ai_after_xyzw: tuple[float, float, float, float]
    attachment_before_xyzw: tuple[float, float, float, float]
    attachment_after_xyzw: tuple[float, float, float, float]
    mounting_before_xyzw: tuple[float, float, float, float]
    mounting_after_xyzw: tuple[float, float, float, float]
    yaw_before_xyzw: tuple[float, float, float, float]
    yaw_after_xyzw: tuple[float, float, float, float]
    hmd_before_xyzw: tuple[float, float, float, float]
    hmd_after_xyzw: tuple[float, float, float, float]
    hmd_valid: bool
    reset_epoch: int
    training_policy: str


class DatasetReader:
    def records(self, stream: BinaryIO) -> Iterator[DatasetRecord]:
        while True:
            prefix = stream.read(4)
            if not prefix:
                return
            if len(prefix) != 4:
                raise DatasetFormatError("truncated record length")
            length = struct.unpack("<I", prefix)[0]
            if length < 8 or length > 16 * 1024 * 1024:
                raise DatasetFormatError(f"invalid record length {length}")
            payload = stream.read(length)
            if len(payload) != length:
                raise DatasetFormatError("truncated FlatBuffer record")
            try:
                yield DatasetRecord.parse(payload)
            except (ValueError, IndexError, struct.error) as error:
                raise DatasetFormatError(str(error)) from error

    def frames(self, stream: BinaryIO) -> Iterator[Frame]:
        for record in self.records(stream):
            if record.record_type != FRAME_BATCH or record.frame_batch is None:
                continue
            batch = record.frame_batch
            for frame_index in range(batch.vector_length(1)):
                table = batch.vector_table(1, frame_index)
                trackers = []
                for tracker_index in range(table.vector_length(4)):
                    tracker = table.vector_table(4, tracker_index)
                    trackers.append(
                        TrackerSample(
                            tracker.string(0) or "",
                            self._quat(tracker.table(1)),
                            self._quat(tracker.table(2)),
                            self._quat(tracker.table(3)),
                        )
                    )
                yield Frame(table.u64(0), table.u64(1), table.u64(2), tuple(trackers))

    def events(self, stream: BinaryIO) -> Iterator[DatasetEventRecord]:
        event_types = ["CONNECT", "DISCONNECT", "ASSIGNMENT", "CALIBRATION", "CAPABILITY_CHANGE", "GAP", "RECORDING_MARKER", "RESET", "MODEL_CHANGE"]
        for record in self.records(stream):
            if record.record_type != EVENT_BATCH or record.event_batch is None:
                continue
            batch = record.event_batch
            for i in range(batch.vector_length(0)):
                t = batch.vector_table(0, i)
                type_idx = t.u8(0)
                etype = event_types[type_idx] if type_idx < len(event_types) else "EVENT"
                parts_len = t.vector_length(14)
                parts = tuple(t.vector_string(14, p) for p in range(parts_len))
                yield DatasetEventRecord(
                    type=etype,
                    monotonic_ns=t.u64(1),
                    frame_index=t.u64(2),
                    session_tracker_id=t.string(3),
                    old_value=t.string(4),
                    new_value=t.string(5),
                    detail=t.string(6),
                    request_id=t.string(7),
                    request_monotonic_ns=t.u64(8),
                    applied_monotonic_ns=t.u64(9),
                    event_index=t.u64(10),
                    reset_outcome=t.string(11),
                    reset_source=t.string(12),
                    reset_kind=t.string(13),
                    affected_body_parts=parts,
                )

    def reset_labels(self, stream: BinaryIO) -> Iterator[ResetLabel]:
        for record in self.records(stream):
            if record.record_type != EVENT_BATCH or record.event_batch is None:
                continue
            batch = record.event_batch
            for i in range(batch.vector_length(1)):
                t = batch.vector_table(1, i)
                yield ResetLabel(
                    event_index=t.u64(0),
                    session_tracker_id=t.string(1) or "",
                    correction_xyzw=float_quat(t.table(2)),
                    diagnostic_yaw_radians=t.f32(3),
                    axis_mask=t.u8(4),
                    pre_start_frame=t.u64(5),
                    pre_end_frame=t.u64(6),
                    post_start_frame=t.u64(7),
                    post_end_frame=t.u64(8),
                    quality_flags=t.u16(9) | (t.u16(9, default=0) << 16), # or read u32
                    request_id=t.string(10),
                    request_monotonic_ns=t.u64(11),
                    applied_monotonic_ns=t.u64(12),
                    reset_domain=t.string(13) or "YAW",
                    raw_before_xyzw=float_quat(t.table(14)),
                    raw_after_xyzw=float_quat(t.table(15)),
                    pre_ai_before_xyzw=float_quat(t.table(16)),
                    pre_ai_after_xyzw=float_quat(t.table(17)),
                    attachment_before_xyzw=float_quat(t.table(18)),
                    attachment_after_xyzw=float_quat(t.table(19)),
                    mounting_before_xyzw=float_quat(t.table(20)),
                    mounting_after_xyzw=float_quat(t.table(21)),
                    yaw_before_xyzw=float_quat(t.table(22)),
                    yaw_after_xyzw=float_quat(t.table(23)),
                    hmd_before_xyzw=float_quat(t.table(24)),
                    hmd_after_xyzw=float_quat(t.table(25)),
                    hmd_valid=bool(t.u8(26, default=1)),
                    reset_epoch=t.u16(27),
                    training_policy=t.string(28) or "INCLUDE",
                )

    def inspect_archive(self, path: str | Path) -> dict:
        path = Path(path)
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
            if "telemetry.bin" in names:
                raise DatasetFormatError("unsupported GUI prototype: missing roster, schema, masks, and footer")
            if "telemetry.zst" in names and "telemetry.fbs.zst" not in names:
                raise DatasetFormatError("unsupported server prototype: missing canonical FlatBuffer contract")
            manifest = json.loads(archive.read("manifest.json"))
            telemetry = archive.read("telemetry.fbs.zst")
            if hashlib.sha256(telemetry).hexdigest() != manifest.get("telemetrySha256"):
                raise DatasetFormatError("telemetry checksum mismatch")
            try:
                import zstandard  # type: ignore
            except ImportError as error:
                raise DatasetFormatError("install zstandard to inspect compressed archives") from error
            records = list(self.records(io.BytesIO(zstandard.ZstdDecompressor().decompress(telemetry))))
            if not records or records[0].record_type != FILE_HEADER or records[-1].record_type != FOOTER:
                raise DatasetFormatError("archive is missing header or footer")
            frame_count = sum(1 for _ in self.frames(io.BytesIO(zstandard.ZstdDecompressor().decompress(telemetry))))
            reset_count = sum(1 for _ in self.reset_labels(io.BytesIO(zstandard.ZstdDecompressor().decompress(telemetry))))
            return {"manifest": manifest, "record_count": len(records), "frame_count": frame_count, "reset_count": reset_count}

    @staticmethod
    def _quat(table) -> tuple[float, float, float, float]:
        return (half(table, 0), half(table, 1), half(table, 2), half(table, 3, 1.0))

