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
    def _kotlin_fixture(self) -> Path:
        fixture_dir = os.environ.get("NEKOVR_KOTLIN_DATASET_FIXTURES")
        if not fixture_dir:
            self.skipTest("aggregate baseline supplies Kotlin-generated archives")
        return Path(fixture_dir) / "kotlin-conformance-v1.nvrdata"

    def test_reset_relationship_mutation_archives_are_rejected(self) -> None:
        fixture_dir = os.environ.get("NEKOVR_KOTLIN_DATASET_FIXTURES")
        if not fixture_dir:
            self.skipTest("aggregate baseline supplies Kotlin-generated archives")
        for name in (
            "reset-wrong-axis.nvrdata",
            "reset-regressing-epoch.nvrdata",
            "reset-unrostered-label.nvrdata",
            "reset-unordered-window.nvrdata",
            "reset-unresolved-window.nvrdata",
        ):
            with self.subTest(name=name), self.assertRaises(DatasetFormatError):
                DatasetReader().inspect_archive(Path(fixture_dir) / name)

    @staticmethod
    def _write_archive(path: Path, manifest: dict, telemetry: bytes, *,
                       compression: int = zipfile.ZIP_STORED, extra: tuple[str, bytes] | None = None) -> None:
        with zipfile.ZipFile(path, "w", compression=compression) as output:
            output.writestr("manifest.json", json.dumps(manifest))
            output.writestr("telemetry.fbs.zst", telemetry)
            if extra is not None:
                output.writestr(*extra)

    def test_canonical_archive_mutations_are_rejected(self) -> None:
        source = self._kotlin_fixture()
        with zipfile.ZipFile(source) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            telemetry = archive.read("telemetry.fbs.zst")
        reader = DatasetReader()
        payload = reader._decompress_zstd(telemetry)

        with tempfile.TemporaryDirectory(prefix="nekovr-archive-mutations-") as directory:
            root = Path(directory)
            extra = root / "extra.nvrdata"
            self._write_archive(extra, manifest, telemetry, extra=("unexpected.txt", b"no"))
            with self.assertRaisesRegex(DatasetFormatError, "ZIP members must be exactly"):
                reader.inspect_archive(extra)

            for unsafe_name in ("/absolute", "../traversal", "C:/absolute"):
                unsafe = root / f"unsafe-{len(unsafe_name)}-{unsafe_name[0].isalnum()}.nvrdata"
                self._write_archive(unsafe, manifest, telemetry, extra=(unsafe_name, b"no"))
                with self.subTest(unsafe_name=unsafe_name), self.assertRaises(DatasetFormatError):
                    reader.inspect_archive(unsafe)

            duplicate = root / "duplicate.nvrdata"
            with zipfile.ZipFile(duplicate, "w", compression=zipfile.ZIP_STORED) as output:
                output.writestr("manifest.json", json.dumps(manifest))
                output.writestr("manifest.json", json.dumps(manifest))
                output.writestr("telemetry.fbs.zst", telemetry)
            with self.assertRaisesRegex(DatasetFormatError, "duplicate ZIP members"):
                reader.inspect_archive(duplicate)

            compressed_again = root / "compressed-again.nvrdata"
            self._write_archive(compressed_again, manifest, telemetry, compression=zipfile.ZIP_DEFLATED)
            with self.assertRaisesRegex(DatasetFormatError, "compressed again"):
                reader.inspect_archive(compressed_again)

            wrong_duration = root / "footer-duration.nvrdata"
            changed_manifest = dict(manifest, durationNs=int(manifest["durationNs"]) + 1)
            self._write_archive(wrong_duration, changed_manifest, telemetry)
            with self.assertRaisesRegex(DatasetFormatError, "durations differ"):
                reader.inspect_archive(wrong_duration)

            wrong_counters = root / "footer-counters.nvrdata"
            changed_manifest = dict(manifest)
            changed_manifest["quality"] = dict(manifest["quality"], sampledFrames=99)
            self._write_archive(wrong_counters, changed_manifest, telemetry)
            with self.assertRaises(DatasetFormatError):
                reader.inspect_archive(wrong_counters)

            bad_footer = root / "footer-checksum.nvrdata"
            footer = next(reader.footers(io.BytesIO(payload)))
            mutated_payload = payload.replace(footer.telemetry_sha256.encode(), b"0" * 64, 1)
            mutated_telemetry = compress_zstd(mutated_payload, write_content_size=False)
            changed_manifest = dict(
                manifest,
                telemetrySha256=hashlib.sha256(mutated_telemetry).hexdigest(),
                telemetryBytes=len(mutated_telemetry),
            )
            self._write_archive(bad_footer, changed_manifest, mutated_telemetry)
            with self.assertRaisesRegex(DatasetFormatError, "footer checksum"):
                reader.inspect_archive(bad_footer)

            bad_registry = root / "registry-semantics.nvrdata"
            mutated_payload = payload.replace(b"raw_orientation", b"bad_orientation", 1)
            mutated_telemetry = compress_zstd(mutated_payload, write_content_size=False)
            changed_manifest = dict(
                manifest,
                telemetrySha256=hashlib.sha256(mutated_telemetry).hexdigest(),
                telemetryBytes=len(mutated_telemetry),
            )
            self._write_archive(bad_registry, changed_manifest, mutated_telemetry)
            with self.assertRaisesRegex(DatasetFormatError, "canonical semantics"):
                reader.inspect_archive(bad_registry)

            wrong_schema = root / "schema.nvrdata"
            self._write_archive(wrong_schema, dict(manifest, schemaMajor=2), telemetry)
            with self.assertRaisesRegex(DatasetFormatError, "unsupported manifest schema"):
                reader.inspect_archive(wrong_schema)

            ordered_records = []
            cursor = 0
            while cursor < len(payload):
                size = struct.unpack_from("<I", payload, cursor)[0]
                ordered_records.append(payload[cursor:cursor + 4 + size])
                cursor += 4 + size
            ordered_records[0], ordered_records[1] = ordered_records[1], ordered_records[0]
            mutated_telemetry = compress_zstd(b"".join(ordered_records), write_content_size=False)
            changed_manifest = dict(
                manifest,
                telemetrySha256=hashlib.sha256(mutated_telemetry).hexdigest(),
                telemetryBytes=len(mutated_telemetry),
            )
            wrong_order = root / "record-order.nvrdata"
            self._write_archive(wrong_order, changed_manifest, mutated_telemetry)
            with self.assertRaisesRegex(DatasetFormatError, "records must be"):
                reader.inspect_archive(wrong_order)

    def test_both_historical_prototypes_remain_structured_failures(self) -> None:
        with tempfile.TemporaryDirectory(prefix="nekovr-prototypes-") as directory:
            for member, expected in (("telemetry.bin", "GUI prototype"), ("telemetry.zst", "server prototype")):
                archive_path = Path(directory) / f"{member}.nvrdata"
                with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_STORED) as archive:
                    archive.writestr(member, b"prototype")
                with self.subTest(member=member), self.assertRaisesRegex(DatasetFormatError, expected):
                    DatasetReader().inspect_archive(archive_path)

    def test_kotlin_archives_cover_standard_and_full_profiles(self) -> None:
        source = self._kotlin_fixture()
        fixture_dir = source.parent
        profiles = set()
        for archive_path in (path for path in fixture_dir.glob("*.nvrdata") if not path.name.startswith("reset-")):
            with zipfile.ZipFile(archive_path) as archive:
                profiles.add(json.loads(archive.read("manifest.json"))["profile"])
            self.assertTrue(DatasetReader().inspect_archive(archive_path)["valid"])
        self.assertTrue({"STANDARD", "FULL_FIDELITY"}.issubset(profiles))

    def test_simulated_pilot_reports_agree_across_kotlin_and_python(self) -> None:
        fixture_dir = os.environ.get("NEKOVR_KOTLIN_DATASET_FIXTURES")
        report_path = os.environ.get("NEKOVR_KOTLIN_DATASET_REPORT")
        if not fixture_dir or not report_path:
            self.skipTest("aggregate baseline supplies Kotlin pilot reports")
        server_reports = json.loads(Path(report_path).read_text(encoding="utf-8"))["reports"]
        reports_by_name = {
            Path(report["archive"]).name: report for report in server_reports
        }
        expected_names = {"simulated-5-udp.nvrdata", "simulated-8-mixed.nvrdata"}
        self.assertEqual(expected_names, set(reports_by_name))
        for name in sorted(expected_names):
            with self.subTest(archive=name):
                server = reports_by_name[name]
                python = DatasetReader().inspect_archive(Path(fixture_dir) / name)
                self.assertTrue(server["valid"])
                self.assertTrue(python["valid"])
                for field in (
                    "schemaMajor", "schemaMinor", "frames", "resetLabels",
                    "rosterSize", "channelIds", "quality", "transports",
                    "validResetWindows",
                ):
                    self.assertEqual(server[field], python[field], field)
        self.assertEqual(5, reports_by_name["simulated-5-udp.nvrdata"]["rosterSize"])
        self.assertEqual(8, reports_by_name["simulated-8-mixed.nvrdata"]["rosterSize"])
        self.assertEqual(
            ["UDP", "HID"],
            reports_by_name["simulated-8-mixed.nvrdata"]["transports"],
        )

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
        archives = sorted(path for path in Path(fixture_dir).glob("*.nvrdata") if not path.name.startswith("reset-"))
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
             (1, 2, 3, 4, 5, 6, 15, 16, 19, 44, 45), "CALIBRATED", "device-a"),
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
        for key, actual in zip(("confidence", "driftRate"), tracker.native_channels):
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
        self.assertEqual((correction.input_schema_sha256, correction.model_version,
                          correction.body_role_id, correction.mapping_tracker_id,
                          correction.gate_outcome, correction.epoch, correction.inference_sequence),
                         ("feature-schema-sha256", "1.2.0", 2, 7, "APPLIED", 6, 42))
        self.assertAlmostEqual(correction.confidence, 0.91, delta=tolerance)
        self.assertAlmostEqual(correction.drift_rate, 0.0125, delta=tolerance)
        self._assert_floats(frame_expected["postAdjusted"], correction.final_output_xyzw, tolerance)
        self._assert_floats(frame_expected["hmdPosition"], frame.hmd.position_xyz, tolerance)
        self.assertEqual((frame.hmd.validity, frame.hmd.sample_age_ns), (3, 2_000_000))
        self.assertEqual(frame.context_samples[0].session_tracker_id, "controller-1")
        self._assert_floats(frame_expected["controllerPosition"], frame.context_samples[0].position_xyz, tolerance)
        self.assertEqual((frame.context_samples[0].position_validity, frame.context_samples[0].position_provenance), (3, 1))
        self.assertEqual(frame.skeleton_bones[0].body_role, "LEFT_HAND")
        self.assertAlmostEqual(frame.body_context.height, 1.72, delta=tolerance)
        self.assertAlmostEqual(frame.floor_context.height, 0.015, delta=tolerance)
        self.assertEqual(list(frame.activity.__dict__.values()), frame_expected["activity"])

        event = next(event for event in reader.events(io.BytesIO(payload)) if event.reset_outcome == "APPLIED")
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

        self.assertEqual((label.reset_epoch_before, label.calibration_epoch_before), (3, 5))
        composed = self._normalize(self._multiply(label.adjusted_after_xyzw, self._inverse(label.adjusted_before_xyzw)))
        reverse = self._normalize(self._multiply(self._inverse(label.adjusted_before_xyzw), label.adjusted_after_xyzw))
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
