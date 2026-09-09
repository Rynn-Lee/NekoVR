from __future__ import annotations

import hashlib
import io
import json
import math
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile


PYTHON_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(PYTHON_ROOT))

from nekovr_dataset import DatasetFormatError, DatasetReader, footer_checksum  # noqa: E402
from nekovr_dataset.dataset_v1_generated import half  # noqa: E402


try:
    import zstandard
except ImportError:
    from compression import zstd as _stdlib_zstd

    def compress_zstd(payload: bytes, *, write_content_size: bool = True) -> bytes:
        del write_content_size
        return _stdlib_zstd.compress(payload)
else:
    def compress_zstd(payload: bytes, *, write_content_size: bool = True) -> bytes:
        return zstandard.ZstdCompressor(write_content_size=write_content_size).compress(payload)


class _HalfTable:
    def __init__(self, bits: int):
        self.bits = bits

    def u16(self, index: int, default: int = 0) -> int:
        del index, default
        return self.bits


class DatasetDecompressionTests(unittest.TestCase):
    def test_unknown_content_size_stream_is_decoded(self) -> None:
        payload = (b"kotlin-streaming-record" * 1000) + b"!"
        telemetry = compress_zstd(payload, write_content_size=False)
        self.assertEqual(payload, DatasetReader._decompress_zstd(telemetry))

    def test_corrupt_truncated_oversized_and_multiple_frames_are_normalized(self) -> None:
        telemetry = compress_zstd(b"bounded telemetry", write_content_size=False)
        cases = (
            b"not-zstandard",
            telemetry[:-1],
            telemetry + compress_zstd(b"second frame", write_content_size=False),
        )
        for value in cases:
            with self.subTest(size=len(value)):
                with self.assertRaises(DatasetFormatError):
                    DatasetReader._decompress_zstd(value)
        with self.assertRaisesRegex(DatasetFormatError, "exceeds 4 bytes"):
            DatasetReader._decompress_zstd(compress_zstd(b"12345"), maximum_output_bytes=4)

    def test_kotlin_streaming_archives_are_read_by_public_reader(self) -> None:
        fixture_dir = os.environ.get("NEKOVR_KOTLIN_DATASET_FIXTURES")
        if not fixture_dir:
            self.skipTest("aggregate baseline supplies Kotlin-generated archives")
        archives = sorted(Path(fixture_dir).glob("*.nvrdata"))
        self.assertEqual(3, len(archives))
        for archive in archives:
            with self.subTest(archive=archive.name):
                report = DatasetReader().inspect_archive(archive)
                self.assertTrue(report["valid"])
                self.assertGreater(report["frames"], 0)

    def test_kotlin_conformance_fixture_preserves_every_expected_field(self) -> None:
        fixture_dir = os.environ.get("NEKOVR_KOTLIN_DATASET_FIXTURES")
        if not fixture_dir:
            self.skipTest("aggregate baseline supplies Kotlin-generated archives")
        expected = json.loads(
            (REPO_ROOT / "dataset/fixtures/kotlin-conformance-v1.expected.json").read_text()
        )
        archive_path = Path(fixture_dir) / expected["archive"]
        self.assertEqual(expected["archiveSha256"], hashlib.sha256(archive_path.read_bytes()).hexdigest())
        with zipfile.ZipFile(archive_path) as archive:
            payload = DatasetReader._decompress_zstd(archive.read("telemetry.fbs.zst"))
        reader = DatasetReader()
        records = tuple(reader.records(io.BytesIO(payload)))
        self.assertEqual(expected["recordTypes"], [record.record_type for record in records])
        self.assertEqual(expected["recordSequences"], [record.sequence for record in records])

        header = next(reader.headers(io.BytesIO(payload)))
        self.assertEqual(expected["sessionId"], header.session_id)
        header_expected = expected["header"]
        self.assertEqual(
            [header.schema_major, header.schema_minor, header.created_utc, header.application_version,
             header.application_commit, header.profile, header.canonical_rate_hz, len(header.channels)],
            [header_expected["schemaMajor"], header_expected["schemaMinor"], header_expected["createdUtc"],
             header_expected["applicationVersion"], header_expected["applicationCommit"],
             header_expected["profile"], header_expected["canonicalRateHz"], header_expected["channelCount"]],
        )
        self.assertEqual(
            (1, "raw_orientation", "quaternion", "sensor_to_world", "50 Hz", "fp16", 0, (1, 2)),
            (header.channels[0].id, header.channels[0].name, header.channels[0].unit,
             header.channels[0].coordinate_frame, header.channels[0].cadence,
             header.channels[0].precision, header.channels[0].required_profile,
             header.channels[0].allowed_provenance),
        )

        roster = next(reader.rosters(io.BytesIO(payload)))
        self.assertEqual(expected["roster"]["revision"], roster.revision)
        self.assertEqual(expected["roster"]["ids"], [entry.session_tracker_id for entry in roster.trackers])
        self.assertEqual(
            (7, "WAIST", "BMI270", "UDP", "SLIMEVR", "ESP32", "0.6.1", "NekoVR",
             (1, 2, 3, 4, 6, 8, 9, 21), "CALIBRATED", "device-a"),
            (roster.trackers[0].device_local_tracker_number, roster.trackers[0].body_role,
             roster.trackers[0].imu_type, roster.trackers[0].transport, roster.trackers[0].board_type,
             roster.trackers[0].mcu_type, roster.trackers[0].firmware_version,
             roster.trackers[0].manufacturer, roster.trackers[0].capabilities,
             roster.trackers[0].initial_calibration, roster.trackers[0].pseudonymous_device_id),
        )

        frame = next(reader.frames(io.BytesIO(payload)))
        frame_expected = expected["frame"]
        self.assertEqual((frame.index, frame.monotonic_ns, frame.delta_ns),
                         (frame_expected["index"], frame_expected["monotonicNs"], frame_expected["deltaNs"]))
        tracker = frame.trackers[0]
        tolerance = expected["floatAbsoluteTolerance"]
        self._assert_floats(frame_expected["rawOrientation"], tracker.raw_orientation_xyzw, tolerance)
        self._assert_floats(frame_expected["preAdjusted"], tracker.pre_ai_orientation_xyzw, tolerance)
        self._assert_floats(frame_expected["postAdjusted"], tracker.final_orientation_xyzw, tolerance)
        self._assert_floats(frame_expected["rawAcceleration"], tracker.raw_acceleration_xyz, tolerance)
        self._assert_floats(frame_expected["linearAcceleration"], tracker.linear_acceleration_xyz, tolerance)
        self._assert_floats(frame_expected["angularVelocity"], tracker.angular_velocity_xyz, tolerance)
        self._assert_floats(frame_expected["magneticVector"], tracker.magnetic_vector_xyz, tolerance)
        self.assertEqual(frame_expected["validity"], [tracker.orientation_validity,
                         tracker.acceleration_validity, tracker.angular_velocity_validity])
        self.assertEqual(frame_expected["provenance"],
                         [tracker.angular_velocity_provenance, tracker.drift_provenance])
        self.assertEqual((tracker.tracker_status, tracker.sample_sequence, tracker.sample_age_ns),
                         ("OK", 42, 3_000_000))
        native_expected = expected["native"]
        for key, actual in zip(("temperature", "sequence", "state"), tracker.native_channels):
            value = native_expected[key]
            self.assertEqual((actual.channel_id, actual.monotonic_ns, actual.integer_value,
                              actual.text_value, actual.validity, actual.provenance),
                             (value[0], value[1], value[3], value[4], value[5], value[6]))
            self._assert_floats(value[2], actual.values, tolerance)
        correction = tracker.correction
        correction_expected = expected["correction"]
        self._assert_floats(correction_expected["prediction"], correction.prediction_xyzw, tolerance)
        self._assert_floats(correction_expected["applied"], correction.applied_correction_xyzw, tolerance)
        self.assertEqual((correction.applied, correction.rejection_reason, correction.model_hash,
                          correction.provider, correction.slot, correction.history_valid,
                          correction.latency_us, correction.provenance),
                         (True, None, correction_expected["modelHash"], correction_expected["provider"],
                          correction_expected["slot"], True, correction_expected["latencyUs"],
                          correction_expected["provenance"]))
        self._assert_floats(frame_expected["hmdPosition"], frame.hmd.position_xyz, tolerance)
        self.assertEqual((frame.hmd.validity, frame.hmd.sample_age_ns), (3, 2_000_000))
        self.assertEqual(frame.context_samples[0].session_tracker_id, "controller-1")
        self.assertEqual(list(frame.activity.__dict__.values()), frame_expected["activity"])

        event = next(reader.events(io.BytesIO(payload)))
        self.assertEqual((event.type, event.event_index, event.request_id, event.affected_body_parts),
                         ("RESET", expected["event"]["index"], expected["event"]["requestId"],
                          tuple(expected["event"]["parts"])))
        label = next(reader.reset_labels(io.BytesIO(payload)))
        reset_expected = expected["reset"]
        self._assert_floats(reset_expected["correction"], label.correction_xyzw, tolerance)
        self.assertEqual((label.axis_mask, label.quality_flags, label.reset_epoch,
                          label.calibration_epoch, label.body_role, label.training_policy),
                         (reset_expected["axisMask"], reset_expected["qualityFlags"],
                          reset_expected["resetEpoch"], reset_expected["calibrationEpoch"],
                          reset_expected["bodyRole"], reset_expected["trainingPolicy"]))
        self.assertEqual((label.hmd_sample_age_before_ns, label.hmd_sample_age_after_ns),
                         (2_000_000, 3_000_000))

        composed = self._normalize(self._multiply(label.raw_after_xyzw, self._inverse(label.raw_before_xyzw)))
        reverse = self._normalize(self._multiply(self._inverse(label.raw_before_xyzw), label.raw_after_xyzw))
        self._assert_floats(label.correction_xyzw, composed, tolerance)
        self.assertGreater(abs(label.correction_xyzw[2] - reverse[2]), 0.9)

        footer = next(reader.footers(io.BytesIO(payload)))
        footer_expected = expected["footer"]
        self.assertEqual((footer.ended_monotonic_ns, footer.duration_ns, footer.complete),
                         (footer_expected["endedMonotonicNs"], footer_expected["durationNs"], True))
        self.assertEqual(list(footer.counters.__dict__.values()), footer_expected["counters"])
        self.assertEqual(64, len(footer.telemetry_sha256))
        self.assertEqual(footer.telemetry_sha256, footer_checksum(record.data for record in records[:-1]))

    def test_footer_checksum_has_fixed_pre_footer_byte_scope(self) -> None:
        self.assertEqual(
            "75b727446b0c555dd1c47ae5848c42b36f7c1b8f9fc207b12dc6da75313c29a3",
            footer_checksum((b"\x01\x02\x03", b"\xfe\xff")),
        )

    def _assert_floats(self, expected, actual, tolerance: float) -> None:
        self.assertEqual(len(expected), len(actual))
        for left, right in zip(expected, actual):
            self.assertAlmostEqual(left, right, delta=tolerance)

    @staticmethod
    def _multiply(a, b):
        ax, ay, az, aw = a
        bx, by, bz, bw = b
        return (aw * bx + ax * bw + ay * bz - az * by,
                aw * by - ax * bz + ay * bw + az * bx,
                aw * bz + ax * by - ay * bx + az * bw,
                aw * bw - ax * bx - ay * by - az * bz)

    @staticmethod
    def _inverse(q):
        return (-q[0], -q[1], -q[2], q[3])

    @staticmethod
    def _normalize(q):
        norm = math.sqrt(sum(value * value for value in q))
        return tuple(value / norm for value in q)
    def test_validate_ready_persists_reader_failure(self) -> None:
        with tempfile.TemporaryDirectory(prefix="nekovr-reader-failure-") as directory:
            root = Path(directory)
            telemetry = b"invalid-zstandard"
            archive = root / "broken.nvrdata"
            manifest = {
                "telemetrySha256": hashlib.sha256(telemetry).hexdigest(),
                "schemaMajor": 1,
                "privacy": {"consent": True},
            }
            with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as output:
                output.writestr("manifest.json", json.dumps(manifest))
                output.writestr("telemetry.fbs.zst", telemetry)
            report = root / "report.json"
            completed = subprocess.run(
                [
                    sys.executable,
                    str(REPO_ROOT / "dataset" / "validate_ready.py"),
                    "--skip-checks",
                    "--simulated-pilot",
                    str(archive),
                    "--report",
                    str(report),
                ],
                cwd=REPO_ROOT,
                text=True,
                capture_output=True,
            )
            self.assertEqual(1, completed.returncode)
            persisted = json.loads(report.read_text(encoding="utf-8"))
            self.assertFalse(persisted["ready"])
            self.assertTrue(persisted["pilots"][0]["fatalFindings"])


class DatasetNumericalTests(unittest.TestCase):
    def test_raw_binary16_codec_preserves_classes_and_boundaries(self) -> None:
        for bits in range(0x10000):
            value = half(_HalfTable(bits), 0)
            exponent = (bits >> 10) & 0x1F
            mantissa = bits & 0x3FF
            if exponent == 0x1F and mantissa:
                self.assertTrue(math.isnan(value))
            elif exponent == 0x1F:
                self.assertTrue(math.isinf(value))
            else:
                encoded = struct.unpack("<H", struct.pack("<e", value))[0]
                self.assertEqual(bits, encoded)
        self.assertEqual(0x0001, struct.unpack("<H", struct.pack("<e", math.ldexp(1.0, -24)))[0])
        self.assertEqual(0x3C00, struct.unpack("<H", struct.pack("<e", 1.00048828125))[0])
        self.assertEqual(0x3C02, struct.unpack("<H", struct.pack("<e", 1.00146484375))[0])

    def test_shared_negative_numerical_fixtures_are_rejected(self) -> None:
        fixture = json.loads(
            (REPO_ROOT / "dataset" / "fixtures" / "numerical-negative-v1.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(1, fixture["schemaVersion"])
        for case in fixture["fp16Cases"]:
            with self.subTest(case=case["name"]), self.assertRaises(DatasetFormatError):
                DatasetReader._half(
                    _HalfTable(case["bits"]),
                    0,
                    case["minimum"],
                    case["maximum"],
                    case["name"],
                )
        for case in fixture["quaternionCases"]:
            with self.subTest(case=case["name"]), self.assertRaises(DatasetFormatError):
                DatasetReader._checked_quat(tuple(case["values"]), case["name"])


if __name__ == "__main__":
    unittest.main()
