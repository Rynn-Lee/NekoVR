# Canonical Dataset Collection Protocol

This protocol is the release gate for production NekoVR dataset collection. A
session is accepted only when it is a canonical `.nvrdata` archive described by
[the v1 contract](canonical-contract-v1.md) and the build has a passing
`dataset-ready-report.json`.

## Before a collection session

1. Use the exact application build named in the dataset-ready report. Do not
   reuse a report from another commit or rebuild.
2. Explain what motion and device telemetry is stored, where the local archive
   is written, the intended retention/export policy, and that recording is not
   an upload. Obtain explicit consent in the recorder UI.
3. Use a session-specific pseudonym. Do not enter a name, account identifier,
   IP address, MAC address, or device serial. Leave identifier handling at
   `OMIT`, or use per-session hashing only when longitudinal correlation is
   explicitly required and consented to.
4. Assign every physical IMU to the correct body role. Confirm a valid HMD
   reference, adequate free disk space, and truthful per-tracker IMU,
   transport, firmware, capability, and calibration metadata.
5. For pilot coverage, record at least one simulated session and one physical
   tracker session. Across the pilot set include more than one layout and all
   transports intended for production (for example Wi-Fi/UDP and HID/nRF).

## During recording

- Begin in a stable standing pose, then cover the planned standing, seated,
  lying, crouching, transition, locomotion, dance/high-dynamics, and stationary
  activities. Low-confidence activity remains `UNKNOWN`; never force a label.
- Perform yaw, full, or mounting resets only when they are actually needed or
  when the session plan explicitly calls for a reset fixture. Stand still and
  keep the HMD tracked around a reset whenever possible.
- A reset request is not itself a correction label. The recorder stores the
  request and applied/cancelled/failed outcome separately. Yaw, full, and
  mounting labels remain distinct, and only complete, valid, non-excluded
  pre/post windows are counted as training-ready reset windows.
- Do not stop the server process to end a normal session. Use Stop and wait for
  finalization and validation. A crash-recovery pilot is a separate controlled
  test and its partial directory must be recovered or quarantined explicitly.

## Session acceptance criteria

Accept a pilot or production session only when all of these conditions hold:

- both the Kotlin server validator and Python reader report zero fatal findings;
- schema major, session ID, telemetry SHA-256, record sequence, header, roster,
  footer, and manifest/frame counts agree;
- Kotlin and Python reports agree exactly on frame/reset counts, roster size,
  channel IDs, quality counters, and enumerated valid reset windows;
- consent is present and the default manifest contains no raw network address,
  MAC, account ID, or stable hardware identifier;
- every tracker has a stable session ID and explicit body role, IMU type,
  transport, firmware/capabilities, and validity/provenance semantics;
- dropped frames, gaps, invalid samples, reconnects, and reset quality warnings
  are reviewed against the session plan; zero fatal findings is mandatory and
  warnings require an explicit acceptance decision;
- the activity/layout/transport coverage required by the pilot plan is present.

Rejected archives remain local for diagnosis and must not enter training or be
presented as production-ready data.

## Dataset-ready validation command

Run the repository command with at least one generated/simulated pilot and one
short pilot captured from physical trackers:

```text
.\gradlew.bat :server:core:generateDatasetPilots
python dataset/validate_ready.py \
  --simulated-pilot server/core/build/dataset-pilots/simulated-5-udp.nvrdata \
  --simulated-pilot server/core/build/dataset-pilots/simulated-8-mixed.nvrdata \
  --real-pilot path/to/physical-trackers.nvrdata \
  --report path/to/datasets/dataset-ready-report.json
```

On Windows, use `py -3 dataset/validate_ready.py` when `python` is not on
`PATH`. The generator emits only `SIMULATED` provenance. The validator rejects
one of those archives if it is passed through `--real-pilot`.

The command runs the server suite, GUI RPC test, lint, and production build;
asks the Kotlin validator and Python reader to inspect every pilot; compares
their roster/channel/quality/reset-window statistics; and atomically writes one
versioned report. It exits non-zero and writes `ready: false` when any evidence
is missing or fails. `--skip-checks` is diagnostic only and can never create a
passing report.

At runtime, set `NEKOVR_DATASET_READY_REPORT` (or the JVM property
`nekovr.datasetReadyReport`) to the report path. Without a structurally complete
passing report containing both pilot sources, the server exposes
`DATASET_READY_GATE_PENDING` and rejects the production profile. A passing
report exposes `DATASET_READY` and unlocks it.

## Unsupported prototype files

- GUI prototype ZIPs containing `telemetry.bin` have no canonical schema,
  immutable roster, validity/provenance masks, angular velocity contract, or
  trustworthy footer/checksum.
- Server prototype ZIPs containing unversioned `telemetry.zst` have no
  FlatBuffer header/channel registry, stable roster IDs, validity/provenance,
  or canonical footer.

Both formats are read-only diagnostic inputs. Validators label them fatal and
unsupported; they must never be renamed, silently converted, counted as pilot
evidence, or used for training because their missing semantics cannot be
reconstructed.
