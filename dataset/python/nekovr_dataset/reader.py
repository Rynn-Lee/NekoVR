from __future__ import annotations

from dataclasses import dataclass
import hashlib
import io
import json
import math
import struct
from pathlib import Path
from typing import BinaryIO, Iterable, Iterator
import zipfile

from .dataset_v1_generated import DatasetRecord, FILE_HEADER, TRACKER_ROSTER, FRAME_BATCH, EVENT_BATCH, FOOTER, float_quat, half


DATASET_SCHEMA_MAJOR = 1
FLAG_INSUFFICIENT_CONTEXT = 1 << 6
FLAG_WINDOW_TRUNCATED = 1 << 7
MAX_COMPRESSED_TELEMETRY_BYTES = 256 * 1024 * 1024
MAX_DECOMPRESSED_TELEMETRY_BYTES = 512 * 1024 * 1024
DECOMPRESSION_CHUNK_BYTES = 64 * 1024


class DatasetFormatError(ValueError):
    pass


def footer_checksum(records_before_footer: Iterable[bytes]) -> str:
    """Hash exact uncompressed uint32-LE framed records preceding Footer."""
    digest = hashlib.sha256()
    for payload in records_before_footer:
        if len(payload) > 0xFFFFFFFF:
            raise DatasetFormatError("record is too large for uint32 framing")
        digest.update(struct.pack("<I", len(payload)))
        digest.update(payload)
    return digest.hexdigest()


@dataclass(frozen=True)
class ChannelDescriptor:
    id: int
    name: str
    unit: str
    coordinate_frame: str
    cadence: str
    precision: str
    required_profile: int
    allowed_provenance: tuple[int, ...]


@dataclass(frozen=True)
class FileHeader:
    schema_major: int
    schema_minor: int
    session_id: str
    created_utc: str
    application_version: str
    application_commit: str
    profile: int
    canonical_rate_hz: int
    channels: tuple[ChannelDescriptor, ...]


@dataclass(frozen=True)
class RosterEntry:
    session_tracker_id: str
    device_local_tracker_number: int
    body_role: str
    imu_type: str
    transport: str
    board_type: str
    mcu_type: str
    firmware_version: str
    manufacturer: str
    capabilities: tuple[int, ...]
    initial_calibration: str
    pseudonymous_device_id: str | None


@dataclass(frozen=True)
class TrackerRoster:
    revision: int
    trackers: tuple[RosterEntry, ...]


@dataclass(frozen=True)
class NativeChannelSample:
    channel_id: int
    monotonic_ns: int
    values: tuple[float, ...]
    integer_value: int | None
    text_value: str | None
    validity: int
    provenance: int


@dataclass(frozen=True)
class CorrectionState:
    prediction_xyzw: tuple[float, float, float, float]
    applied_correction_xyzw: tuple[float, float, float, float]
    applied: bool
    rejection_reason: str | None
    model_hash: str | None
    provider: str | None
    slot: int
    history_valid: bool
    latency_us: int | None
    provenance: int


@dataclass(frozen=True)
class TrackerSample:
    session_tracker_id: str
    raw_orientation_xyzw: tuple[float, float, float, float]
    pre_ai_orientation_xyzw: tuple[float, float, float, float]
    final_orientation_xyzw: tuple[float, float, float, float]
    raw_acceleration_xyz: tuple[float, float, float]
    linear_acceleration_xyz: tuple[float, float, float]
    angular_velocity_xyz: tuple[float, float, float]
    magnetic_vector_xyz: tuple[float, float, float]
    orientation_validity: int
    acceleration_validity: int
    angular_velocity_validity: int
    angular_velocity_provenance: int
    drift_provenance: int
    tracker_status: str
    sample_sequence: int
    sample_age_ns: int
    correction: CorrectionState
    native_channels: tuple[NativeChannelSample, ...]


@dataclass(frozen=True)
class ReferenceSample:
    orientation_xyzw: tuple[float, float, float, float]
    position_xyz: tuple[float, float, float]
    validity: int
    sample_age_ns: int


@dataclass(frozen=True)
class ActivityInterval:
    activity: int
    confidence: float
    start_frame: int
    end_frame: int
    provenance: int


@dataclass(frozen=True)
class Frame:
    index: int
    monotonic_ns: int
    delta_ns: int
    trackers: tuple[TrackerSample, ...]
    hmd: ReferenceSample
    context_samples: tuple[TrackerSample, ...]
    activity: ActivityInterval


@dataclass(frozen=True)
class QualityCounters:
    sampled_frames: int
    written_frames: int
    dropped_frames: int
    gap_events: int
    invalid_samples: int
    queue_high_watermark: int
    packet_gaps: int
    packet_reordered: int
    packet_duplicates: int
    packet_corrupt: int


@dataclass(frozen=True)
class Footer:
    ended_monotonic_ns: int
    duration_ns: int
    counters: QualityCounters
    telemetry_sha256: str
    complete: bool


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
    gyro_fix_before_xyzw: tuple[float, float, float, float]
    gyro_fix_after_xyzw: tuple[float, float, float, float]
    mount_rot_fix_before_xyzw: tuple[float, float, float, float]
    mount_rot_fix_after_xyzw: tuple[float, float, float, float]
    tpose_down_fix_before_xyzw: tuple[float, float, float, float]
    tpose_down_fix_after_xyzw: tuple[float, float, float, float]
    constraint_fix_before_xyzw: tuple[float, float, float, float]
    constraint_fix_after_xyzw: tuple[float, float, float, float]
    calibration_epoch: int
    body_role: str
    hmd_sample_age_before_ns: int
    hmd_sample_age_after_ns: int


class DatasetReader:
    @staticmethod
    def _enum(value: int, size: int, field: str) -> int:
        if value < 0 or value >= size:
            raise DatasetFormatError(f"{field} has unknown ordinal {value}")
        return value

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

    def headers(self, stream: BinaryIO) -> Iterator[FileHeader]:
        for record in self.records(stream):
            if record.record_type != FILE_HEADER or record.header is None:
                continue
            table = record.header
            channels = []
            for index in range(table.vector_length(8)):
                channel = table.vector_table(8, index)
                required_profile = self._enum(channel.u8(6), 3, "channel.required_profile")
                allowed = tuple(
                    self._enum(channel.vector_u8(7, item), 8, "channel.allowed_provenance")
                    for item in range(channel.vector_length(7))
                )
                channels.append(ChannelDescriptor(
                    channel.u32(0), channel.string(1) or "", channel.string(2) or "",
                    channel.string(3) or "", channel.string(4) or "", channel.string(5) or "",
                    required_profile, allowed,
                ))
            yield FileHeader(
                table.u16(0, 1), table.u16(1), table.string(2) or "", table.string(3) or "",
                table.string(4) or "", table.string(5) or "",
                self._enum(table.u8(6), 3, "header.profile"), table.u16(7, 50), tuple(channels),
            )

    def rosters(self, stream: BinaryIO) -> Iterator[TrackerRoster]:
        for record in self.records(stream):
            if record.record_type != TRACKER_ROSTER or record.roster is None:
                continue
            table = record.roster
            trackers = []
            for index in range(table.vector_length(1)):
                tracker = table.vector_table(1, index)
                capabilities = tuple(tracker.vector_u32(9, item) for item in range(tracker.vector_length(9)))
                trackers.append(RosterEntry(
                    tracker.string(0) or "", tracker.i32(1), tracker.string(2) or "",
                    tracker.string(3) or "", tracker.string(4) or "", tracker.string(5) or "",
                    tracker.string(6) or "", tracker.string(7) or "", tracker.string(8) or "",
                    capabilities, tracker.string(10) or "", tracker.string(11) or None,
                ))
            yield TrackerRoster(table.u32(0), tuple(trackers))

    def footers(self, stream: BinaryIO) -> Iterator[Footer]:
        for record in self.records(stream):
            if record.record_type != FOOTER or record.footer is None:
                continue
            table = record.footer
            counters = table.table(2)
            if counters is None:
                raise DatasetFormatError("footer quality counters are missing")
            yield Footer(
                table.u64(0), table.u64(1),
                QualityCounters(
                    counters.u64(0), counters.u64(1), counters.u64(2), counters.u64(3),
                    counters.u64(4), counters.u32(5), counters.u64(6), counters.u64(7),
                    counters.u64(8), counters.u64(9),
                ),
                table.string(3) or "", bool(table.u8(4)),
            )

    def _tracker(self, tracker) -> TrackerSample:
        correction = tracker.table(16)
        native_channels = []
        for index in range(tracker.vector_length(17)):
            native = tracker.vector_table(17, index)
            values = tuple(native.vector_f32(2, item) for item in range(native.vector_length(2)))
            if not all(math.isfinite(value) for value in values):
                raise DatasetFormatError("native channel values must be finite")
            native_channels.append(NativeChannelSample(
                native.u32(0), native.u64(1), values,
                native.i64(3) if native.has_field(3) else None,
                native.string(4), self._enum(native.u8(5), 4, "native.validity"),
                self._enum(native.u8(6), 8, "native.provenance"),
            ))
        return TrackerSample(
            tracker.string(0) or "",
            self._quat(tracker.table(1), "raw_orientation"),
            self._quat(tracker.table(2), "calibrated_pre_ai_orientation"),
            self._quat(tracker.table(3), "final_orientation"),
            self._vec(tracker.table(4), 128.0, "raw_acceleration"),
            self._vec(tracker.table(5), 128.0, "linear_acceleration"),
            self._vec(tracker.table(6), 64.0, "angular_velocity"),
            self._vec(tracker.table(7), 4096.0, "magnetic_vector"),
            self._enum(tracker.u8(8), 4, "tracker.orientation_validity"),
            self._enum(tracker.u8(9), 4, "tracker.acceleration_validity"),
            self._enum(tracker.u8(10), 4, "tracker.angular_velocity_validity"),
            self._enum(tracker.u8(11), 8, "tracker.angular_velocity_provenance"),
            self._enum(tracker.u8(12), 8, "tracker.drift_provenance"),
            tracker.string(13) or "UNKNOWN", tracker.u64(14), tracker.u64(15),
            CorrectionState(
                self._float_quat(correction.table(0) if correction else None, "correction.prediction"),
                self._float_quat(correction.table(1) if correction else None, "correction.applied"),
                bool(correction.u8(2)) if correction else False,
                correction.string(3) if correction else None,
                correction.string(4) if correction else None,
                correction.string(5) if correction else None,
                correction.i32(6, -1) if correction else -1,
                bool(correction.u8(7)) if correction else False,
                correction.u64(8) if correction and correction.has_field(8) else None,
                self._enum(correction.u8(9) if correction else 0, 8, "correction.provenance"),
            ),
            tuple(native_channels),
        )

    def frames(self, stream: BinaryIO) -> Iterator[Frame]:
        for record in self.records(stream):
            if record.record_type != FRAME_BATCH or record.frame_batch is None:
                continue
            batch = record.frame_batch
            for frame_index in range(batch.vector_length(1)):
                table = batch.vector_table(1, frame_index)
                trackers = tuple(self._tracker(table.vector_table(4, i)) for i in range(table.vector_length(4)))
                context = tuple(self._tracker(table.vector_table(5, i)) for i in range(table.vector_length(5)))
                hmd = table.table(3)
                activity = table.table(6)
                confidence = activity.f32(1) if activity else 0.0
                start_frame = activity.u64(2) if activity else table.u64(0)
                end_frame = activity.u64(3) if activity else table.u64(0)
                if not math.isfinite(confidence) or not 0.0 <= confidence <= 1.0:
                    raise DatasetFormatError("activity confidence must be in [0, 1]")
                if start_frame > end_frame:
                    raise DatasetFormatError("activity frame interval is inverted")
                yield Frame(
                    table.u64(0), table.u64(1), table.u64(2), trackers,
                    ReferenceSample(
                        self._float_quat(hmd.table(0) if hmd else None, "hmd.orientation"),
                        self._float_vec(hmd.table(1) if hmd else None, 1000.0, "hmd.position"),
                        self._enum(hmd.u8(2) if hmd else 0, 4, "hmd.validity"),
                        hmd.u64(3) if hmd else 0,
                    ),
                    context,
                    ActivityInterval(
                        self._enum(activity.u8(0) if activity else 0, 9, "activity.type"),
                        confidence, start_frame, end_frame,
                        self._enum(activity.u8(4) if activity else 0, 8, "activity.provenance"),
                    ),
                )

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
                    correction_xyzw=self._float_quat(t.table(2), "reset.correction"),
                    diagnostic_yaw_radians=t.f32(3),
                    axis_mask=t.u8(4),
                    pre_start_frame=t.u64(5),
                    pre_end_frame=t.u64(6),
                    post_start_frame=t.u64(7),
                    post_end_frame=t.u64(8),
                    quality_flags=t.u32(9),
                    request_id=t.string(10),
                    request_monotonic_ns=t.u64(11),
                    applied_monotonic_ns=t.u64(12),
                    reset_domain=t.string(13) or "YAW",
                    raw_before_xyzw=self._float_quat(t.table(14), "reset.raw_before"),
                    raw_after_xyzw=self._float_quat(t.table(15), "reset.raw_after"),
                    pre_ai_before_xyzw=self._float_quat(t.table(16), "reset.pre_ai_before"),
                    pre_ai_after_xyzw=self._float_quat(t.table(17), "reset.pre_ai_after"),
                    attachment_before_xyzw=self._float_quat(t.table(18), "reset.attachment_before"),
                    attachment_after_xyzw=self._float_quat(t.table(19), "reset.attachment_after"),
                    mounting_before_xyzw=self._float_quat(t.table(20), "reset.mounting_before"),
                    mounting_after_xyzw=self._float_quat(t.table(21), "reset.mounting_after"),
                    yaw_before_xyzw=self._float_quat(t.table(22), "reset.yaw_before"),
                    yaw_after_xyzw=self._float_quat(t.table(23), "reset.yaw_after"),
                    hmd_before_xyzw=self._float_quat(t.table(24), "reset.hmd_before"),
                    hmd_after_xyzw=self._float_quat(t.table(25), "reset.hmd_after"),
                    hmd_valid=bool(t.u8(26, default=1)),
                    reset_epoch=t.u32(27),
                    training_policy=t.string(28) or "INCLUDE",
                    gyro_fix_before_xyzw=self._float_quat(t.table(29), "reset.gyro_fix_before"),
                    gyro_fix_after_xyzw=self._float_quat(t.table(30), "reset.gyro_fix_after"),
                    mount_rot_fix_before_xyzw=self._float_quat(t.table(31), "reset.mount_rot_fix_before"),
                    mount_rot_fix_after_xyzw=self._float_quat(t.table(32), "reset.mount_rot_fix_after"),
                    tpose_down_fix_before_xyzw=self._float_quat(t.table(33), "reset.tpose_down_fix_before"),
                    tpose_down_fix_after_xyzw=self._float_quat(t.table(34), "reset.tpose_down_fix_after"),
                    constraint_fix_before_xyzw=self._float_quat(t.table(35), "reset.constraint_fix_before"),
                    constraint_fix_after_xyzw=self._float_quat(t.table(36), "reset.constraint_fix_after"),
                    calibration_epoch=t.u64(37),
                    body_role=t.string(38) or "UNASSIGNED",
                    hmd_sample_age_before_ns=t.u64(39),
                    hmd_sample_age_after_ns=t.u64(40),
                )

    def inspect_archive(self, path: str | Path) -> dict:
        path = Path(path)
        try:
            with zipfile.ZipFile(path) as archive:
                names = set(archive.namelist())
                if "telemetry.bin" in names:
                    raise DatasetFormatError("unsupported GUI prototype: missing roster, schema, masks, and footer")
                if "telemetry.zst" in names and "telemetry.fbs.zst" not in names:
                    raise DatasetFormatError("unsupported server prototype: missing canonical FlatBuffer contract")
                manifest = json.loads(archive.read("manifest.json"))
                telemetry_info = archive.getinfo("telemetry.fbs.zst")
                if telemetry_info.file_size > MAX_COMPRESSED_TELEMETRY_BYTES:
                    raise DatasetFormatError(
                        f"compressed telemetry exceeds {MAX_COMPRESSED_TELEMETRY_BYTES} bytes"
                    )
                telemetry = archive.read(telemetry_info)
        except DatasetFormatError:
            raise
        except (OSError, KeyError, json.JSONDecodeError, zipfile.BadZipFile, RuntimeError) as error:
            raise DatasetFormatError(f"invalid dataset archive: {error}") from error

        try:
            if hashlib.sha256(telemetry).hexdigest() != manifest.get("telemetrySha256"):
                raise DatasetFormatError("telemetry checksum mismatch")
            payload = self._decompress_zstd(telemetry)
            records = list(self.records(io.BytesIO(payload)))
            if not records or records[0].record_type != FILE_HEADER or records[-1].record_type != FOOTER:
                raise DatasetFormatError("archive is missing header or footer")
            for expected_sequence, record in enumerate(records):
                if record.sequence != expected_sequence:
                    raise DatasetFormatError(
                        f"record sequence mismatch: expected {expected_sequence}, got {record.sequence}"
                    )
            header = records[0].header
            if header is None or header.u16(0, 1) != DATASET_SCHEMA_MAJOR:
                raise DatasetFormatError("unsupported telemetry schema")
            if manifest.get("schemaMajor") != DATASET_SCHEMA_MAJOR:
                raise DatasetFormatError("unsupported manifest schema")
            if header.string(2) != manifest.get("sessionId"):
                raise DatasetFormatError("header and manifest session IDs differ")
            if not manifest.get("privacy", {}).get("consent", False):
                raise DatasetFormatError("recording consent is missing")
            if not any(record.record_type == TRACKER_ROSTER for record in records):
                raise DatasetFormatError("tracker roster is missing")

            frames = tuple(self.frames(io.BytesIO(payload)))
            labels = tuple(self.reset_labels(io.BytesIO(payload)))
            frame_count = len(frames)
            expected_frames = int(manifest.get("quality", {}).get("writtenFrames", -1))
            if frame_count != expected_frames:
                raise DatasetFormatError(
                    f"frame count mismatch: manifest {expected_frames}, decoded {frame_count}"
                )
            last_frame = max((frame.index for frame in frames), default=-1)
            tracker_ids = {
                tracker.get("sessionTrackerId", "") for tracker in manifest.get("trackers", [])
            }
            invalid_window_mask = FLAG_INSUFFICIENT_CONTEXT | FLAG_WINDOW_TRUNCATED
            valid_windows = [
                {
                    "eventIndex": label.event_index,
                    "sessionTrackerId": label.session_tracker_id,
                    "preStartFrame": label.pre_start_frame,
                    "preEndFrame": label.pre_end_frame,
                    "postStartFrame": label.post_start_frame,
                    "postEndFrame": label.post_end_frame,
                    "qualityFlags": label.quality_flags,
                    "trainingPolicy": label.training_policy,
                }
                for label in labels
                if label.session_tracker_id in tracker_ids
                and 0 <= label.pre_start_frame <= label.pre_end_frame <= last_frame
                and 0 <= label.post_start_frame <= label.post_end_frame <= last_frame
                and not (label.quality_flags & invalid_window_mask)
                and label.training_policy != "EXCLUDE"
            ]
            header_channel_ids = {
                header.vector_table(8, index).u32(0)
                for index in range(header.vector_length(8))
            }
            manifest_channel_ids = {int(value) for value in manifest.get("channelIds", [])}
            if header_channel_ids != manifest_channel_ids:
                raise DatasetFormatError("header and manifest channel registries differ")
            return {
                "archive": str(path),
                "valid": True,
                "fatalFindings": [],
                "schemaMajor": manifest.get("schemaMajor"),
                "schemaMinor": manifest.get("schemaMinor"),
                "applicationCommit": manifest.get("applicationCommit", ""),
                "recordCount": len(records),
                "frames": frame_count,
                "resetLabels": len(labels),
                "rosterSize": len(manifest.get("trackers", [])),
                "transports": sorted(
                    {tracker.get("transport", "UNKNOWN") for tracker in manifest.get("trackers", [])}
                ),
                "channelIds": sorted(manifest_channel_ids),
                "quality": manifest.get("quality", {}),
                "validResetWindows": valid_windows,
            }
        except DatasetFormatError:
            raise
        except (IndexError, OverflowError, struct.error, UnicodeDecodeError, ValueError) as error:
            raise DatasetFormatError(f"invalid canonical telemetry: {error}") from error

    @staticmethod
    def _zstd_frame_end(telemetry: bytes) -> int:
        """Return the exact end of one ordinary Zstandard frame.

        Canonical telemetry is exactly one frame. Parsing block lengths before
        decompression lets us reject concatenated/skippable frames and trailing
        bytes without asking a decoder to expand a second frame.
        """
        if len(telemetry) < 5 or telemetry[:4] != b"\x28\xb5\x2f\xfd":
            raise DatasetFormatError("invalid Zstandard frame magic")
        cursor = 4
        descriptor = telemetry[cursor]
        cursor += 1
        if descriptor & 0x08:
            raise DatasetFormatError("reserved Zstandard frame-header bit is set")
        content_size_flag = descriptor >> 6
        single_segment = bool(descriptor & 0x20)
        checksum = bool(descriptor & 0x04)
        dictionary_size = (0, 1, 2, 4)[descriptor & 0x03]
        if not single_segment:
            cursor += 1  # window descriptor
        content_size_bytes = (1 if single_segment else 0, 2, 4, 8)[content_size_flag]
        cursor += dictionary_size + content_size_bytes
        if cursor > len(telemetry):
            raise DatasetFormatError("truncated Zstandard frame header")

        while True:
            if cursor + 3 > len(telemetry):
                raise DatasetFormatError("truncated Zstandard block header")
            header = int.from_bytes(telemetry[cursor : cursor + 3], "little")
            cursor += 3
            last_block = bool(header & 1)
            block_type = (header >> 1) & 0x03
            block_size = header >> 3
            if block_type == 3:
                raise DatasetFormatError("reserved Zstandard block type")
            cursor += 1 if block_type == 1 else block_size
            if cursor > len(telemetry):
                raise DatasetFormatError("truncated Zstandard block payload")
            if last_block:
                break
        if checksum:
            cursor += 4
            if cursor > len(telemetry):
                raise DatasetFormatError("truncated Zstandard content checksum")
        return cursor

    @staticmethod
    def _decompress_zstd(
        telemetry: bytes,
        maximum_output_bytes: int = MAX_DECOMPRESSED_TELEMETRY_BYTES,
    ) -> bytes:
        if len(telemetry) > MAX_COMPRESSED_TELEMETRY_BYTES:
            raise DatasetFormatError(
                f"compressed telemetry exceeds {MAX_COMPRESSED_TELEMETRY_BYTES} bytes"
            )
        if maximum_output_bytes < 0:
            raise DatasetFormatError("invalid decompressed telemetry limit")
        frame_end = DatasetReader._zstd_frame_end(telemetry)
        if frame_end != len(telemetry):
            raise DatasetFormatError("multiple Zstandard frames or trailing data are not canonical")

        try:
            import zstandard  # type: ignore
        except ImportError:
            try:
                from compression import zstd  # type: ignore
            except ImportError as error:
                raise DatasetFormatError(
                    "install zstandard (or use Python with compression.zstd) to inspect archives"
                ) from error
            reader_factory = lambda: zstd.open(io.BytesIO(telemetry), "rb")
        else:
            reader_factory = lambda: zstandard.ZstdDecompressor().stream_reader(
                io.BytesIO(telemetry), read_across_frames=False
            )

        output = bytearray()
        try:
            with reader_factory() as stream:
                while True:
                    remaining = maximum_output_bytes - len(output)
                    chunk = stream.read(min(DECOMPRESSION_CHUNK_BYTES, remaining + 1))
                    if not chunk:
                        break
                    output.extend(chunk)
                    if len(output) > maximum_output_bytes:
                        raise DatasetFormatError(
                            f"decompressed telemetry exceeds {maximum_output_bytes} bytes"
                        )
        except DatasetFormatError:
            raise
        except Exception as error:
            raise DatasetFormatError("invalid or truncated Zstandard telemetry") from error
        return bytes(output)

    @staticmethod
    def _half(table, index: int, minimum: float, maximum: float, channel: str, default: float = 0.0) -> float:
        value = half(table, index, default)
        if not math.isfinite(value):
            raise DatasetFormatError(f"{channel} contains non-finite binary16")
        if value < minimum or value > maximum:
            raise DatasetFormatError(f"{channel} decoded value {value} is outside [{minimum}, {maximum}]")
        return value

    @classmethod
    def _vec(cls, table, limit: float, channel: str) -> tuple[float, float, float]:
        if table is None:
            return (0.0, 0.0, 0.0)
        return tuple(
            cls._half(table, index, -limit, limit, f"{channel}.{component}")
            for index, component in enumerate("xyz")
        )

    @staticmethod
    def _float_vec(table, limit: float, channel: str) -> tuple[float, float, float]:
        if table is None:
            return (0.0, 0.0, 0.0)
        values = (table.f32(0), table.f32(1), table.f32(2))
        if not all(math.isfinite(value) and -limit <= value <= limit for value in values):
            raise DatasetFormatError(f"{channel} must contain finite components in [-{limit}, {limit}]")
        return values

    @staticmethod
    def _checked_quat(values: tuple[float, float, float, float], channel: str) -> tuple[float, float, float, float]:
        if not all(math.isfinite(value) and -1.0 <= value <= 1.0 for value in values):
            raise DatasetFormatError(f"{channel} must contain finite components in [-1, 1]")
        norm_squared = sum(value * value for value in values)
        if not 0.98 <= norm_squared <= 1.02:
            raise DatasetFormatError(f"{channel} must be normalized")
        return values

    @classmethod
    def _quat(cls, table, channel: str) -> tuple[float, float, float, float]:
        if table is None:
            return (0.0, 0.0, 0.0, 1.0)
        return cls._checked_quat(
            tuple(
                cls._half(
                    table,
                    index,
                    -1.0,
                    1.0,
                    f"{channel}.{component}",
                    1.0 if index == 3 else 0.0,
                )
                for index, component in enumerate("xyzw")
            ),
            channel,
        )

    @classmethod
    def _vec(cls, table, limit: float, channel: str) -> tuple[float, float, float]:
        if table is None:
            return (0.0, 0.0, 0.0)
        return tuple(
            cls._half(table, index, -limit, limit, f"{channel}.{component}")
            for index, component in enumerate("xyz")
        )

    @staticmethod
    def _float_vec(table, limit: float, channel: str) -> tuple[float, float, float]:
        if table is None:
            return (0.0, 0.0, 0.0)
        values = (table.f32(0), table.f32(1), table.f32(2))
        if not all(math.isfinite(value) and -limit <= value <= limit for value in values):
            raise DatasetFormatError(f"{channel} must contain finite components in [-{limit}, {limit}]")
        return values

    @classmethod
    def _float_quat(cls, table, channel: str) -> tuple[float, float, float, float]:
        return cls._checked_quat(float_quat(table), channel)
