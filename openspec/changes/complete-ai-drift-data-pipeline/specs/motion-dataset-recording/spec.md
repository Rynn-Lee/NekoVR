## ADDED Requirements

### Requirement: Authoritative server-side recording
The system SHALL use one server-owned dataset recorder as the authoritative source of session telemetry, and the GUI SHALL control that recorder rather than sampling data-feed frames into a second format.

#### Scenario: Start from the GUI
- **WHEN** a user starts recording from the AI Drift page and all readiness checks pass
- **THEN** the server creates exactly one recording session and the GUI displays the server-issued session identifier and status

#### Scenario: Duplicate start
- **WHEN** a start command is received while a session is already recording
- **THEN** the server rejects the command without creating or truncating another session

### Requirement: Dataset readiness validation
The recorder SHALL validate HMD/reference availability, at least one assigned physical IMU tracker, writable storage, free-space threshold, supported schema, and complete tracker metadata before recording.

#### Scenario: Missing reference
- **WHEN** the HMD reference required by the collection profile is absent or stale
- **THEN** recording does not start and status identifies the failed reference check

#### Scenario: Unknown metadata
- **WHEN** a tracker has an unknown chipset or transport
- **THEN** the configured collection policy either rejects the session or records an explicit `UNKNOWN` value and quality warning; it never substitutes a fictional chipset or manufacturer

### Requirement: Synchronized monotonic sampling
The recorder SHALL capture immutable server snapshots at a target rate of 50 Hz using monotonic timestamps, while preserving actual time deltas, sample age, and dropped-frame gaps.

#### Scenario: Normal sampling
- **WHEN** tracking data remains available for one second
- **THEN** the archive contains approximately 50 ordered frame timestamps with their actual monotonic deltas

#### Scenario: Recorder backlog
- **WHEN** the writer queue is full
- **THEN** the tracking thread remains non-blocking and the session records dropped-frame counters and a gap event

### Requirement: Complete per-frame channels
Each canonical frame SHALL contain HMD pose and validity plus, for each rostered tracker, raw/fused/calibrated/pre-AI/final orientations where available, raw and linear acceleration, measured or derived angular velocity, magnetic vector, correction/drift state, tracker status, sample age, and channel validity/provenance. High-rate sub-frame samples and slow change streams SHALL remain timestamp-aligned to the canonical frame timeline.

#### Scenario: Raw gyro unavailable
- **WHEN** a transport does not supply angular velocity
- **THEN** the recorder stores a validated quaternion-derived angular velocity marked `DERIVED`, or marks the channel unavailable without claiming measured gyro data

#### Scenario: Non-finite sensor value
- **WHEN** any vector or quaternion contains NaN, infinity, a zero-norm quaternion, or an out-of-contract magnitude
- **THEN** the sample is marked invalid and the invalid value is not serialized as a valid FP16 feature

### Requirement: Full training telemetry registry
The versioned schema SHALL support every initially identified training-relevant channel: device/server/canonical timestamps; packet/sample sequence and uptime; configured/observed sample rate; raw/fused motion; gyro, accelerometer and magnetometer values; temperature; calibration/fusion/error status; packet sent/received/lost/gap/duplicate/reorder/corrupt counters; RSSI, ping, sample age, inter-arrival jitter and reconnects; battery/charging/power/sleep state; firmware features; HMD/controller/skeleton/body/floor context; all reset/mounting/legacy/AI transforms; model identity/provider/confidence/gating/latency; and topology/operator events.

#### Scenario: Full-feature tracker
- **WHEN** firmware and transport expose temperature, raw gyro, magnetic vector, packet sequence, calibration accuracy, RSSI, battery and device timestamps
- **THEN** the archive preserves those values at their native cadence with units, precision, source and validity instead of dropping them at the recorder boundary

#### Scenario: Legacy tracker
- **WHEN** a tracker lacks one or more optional channels
- **THEN** the archive marks those channels `UNAVAILABLE` and remains usable under a compatible minimum collection profile without substituting zero or another tracker's value

### Requirement: Native cadence and change-stream encoding
The recorder SHALL sample each signal at an appropriate declared cadence: canonical motion at 50 Hz, higher-rate raw IMU in timestamped sub-frame batches when enabled, and temperature/network/power/calibration values on change or at their reported rate.

#### Scenario: Stable temperature
- **WHEN** tracker temperature remains unchanged for ten seconds
- **THEN** the archive can reconstruct the valid temperature interval without writing 500 duplicate temperature values

### Requirement: Firmware and transport observability
The project SHALL extend supported tracker firmware/protocols to expose training-critical device timestamps/sequences, raw gyro, temperature, packet counters, calibration quality and power state when hardware can provide them, while preserving pose delivery rate and compatibility negotiation.

#### Scenario: Capability negotiation
- **WHEN** a firmware version advertises a new raw-gyro and timestamp capability
- **THEN** the server records those channels with firmware provenance and older firmware continues through explicit unavailable masks

### Requirement: Corrected-session counterfactual channels
When AI correction is active, the recorder SHALL store immutable raw/pre-AI observations, model input schema/hash, prediction/confidence, gating decision, actually applied correction and final output as separate channels; final output SHALL never be treated as sensor ground truth.

#### Scenario: Corrected play without reset
- **WHEN** a player records a long session with AI enabled and performs no reset
- **THEN** offline tooling can replay the raw stream with AI disabled or another candidate model and can identify clean no-reset intervals without learning directly from the previous model's corrected output

#### Scenario: Manual reset overrides AI
- **WHEN** the player performs a reset after AI correction has been active
- **THEN** the reset label links to the active model prediction/applied correction and is identifiable as a high-value human override event

### Requirement: Posture and motion coverage
The recorder or preprocessing pipeline SHALL attach confidence-bearing derived activity labels for standing, seated, lying, crouching, locomotion, transitions, dance/high-dynamics, stationary periods and unknown, using HMD/skeleton/velocity/contact context without requiring manual annotation.

#### Scenario: Uncertain posture
- **WHEN** available reference data cannot distinguish sitting from crouching
- **THEN** the interval is marked low-confidence or `UNKNOWN` and is not forced into either cohort

### Requirement: Extensible channel provenance
Every optional channel SHALL declare unit, coordinate frame, cadence, precision, validity and one of `MEASURED`, `FIRMWARE_REPORTED`, `SERVER_DERIVED`, `MODEL_DERIVED`, `USER_ANNOTATED` or `UNAVAILABLE`; readers SHALL skip unknown optional channels while rejecting incompatible required semantics.

#### Scenario: New firmware channel
- **WHEN** a future firmware adds an optional pressure or oscillator-stability signal
- **THEN** a newer writer can record its registered semantics and older readers can skip it without misaligning existing frame data

### Requirement: Explicit numerical and coordinate contract
The dataset schema SHALL define byte order, quaternion component order, multiplication direction, coordinate handedness/axes, world and sensor frames, units, gravity treatment, timestamp units, FP16 ranges, and normalization rules.

#### Scenario: Independent reader round trip
- **WHEN** a conformance fixture is written by Kotlin and read by the Python dataset loader
- **THEN** all valid fields reconstruct within documented tolerances and transform composition matches the fixture

### Requirement: Immutable tracker roster and topology events
The session SHALL snapshot a stable tracker roster at start and SHALL represent connection, disconnection, reassignment, calibration, or channel-availability changes as timestamped events without reusing session tracker identifiers.

#### Scenario: Tracker reconnects
- **WHEN** a physical tracker disconnects and reconnects during recording
- **THEN** frames preserve the same session identity when the device mapping is provably the same and include disconnect/reconnect events and invalid intervals

#### Scenario: Body assignment changes
- **WHEN** a tracker is reassigned to another body role
- **THEN** an assignment event records the old and new roles and subsequent samples reference the new mapping

### Requirement: Detailed per-tracker metadata
The manifest SHALL map every tracker separately to its session ID, device-local number, body role, concrete IMU chipset, connection transport/origin, board and MCU type when known, firmware, manufacturer when reported, channel capabilities, and initial calibration state.

#### Scenario: Mixed tracker hardware
- **WHEN** a session contains Wi-Fi BMI160 trackers and HID/nRF BNO085 trackers
- **THEN** the manifest retains each tracker's own chipset and transport rather than applying device-wide defaults

### Requirement: Bounded-memory streaming persistence
Telemetry SHALL be FlatBuffers-encoded in batches, Zstandard-compressed at level 3, and streamed asynchronously to disk through a bounded reusable buffer pool; memory use SHALL not grow with session duration.

#### Scenario: Multi-hour soak
- **WHEN** recording runs for the configured multi-hour soak duration
- **THEN** recorder heap/direct-buffer use remains within the declared fixed budget and the archive continues to advance on disk

### Requirement: Recoverable finalization
The recorder SHALL use partial files, checksums, periodic durable progress, and atomic finalization so interrupted sessions are recovered or quarantined and never presented as valid completed archives.

#### Scenario: Process termination during recording
- **WHEN** the process stops after at least one durable batch and restarts
- **THEN** the server reports a recoverable partial session or a specific validation failure without overwriting it

#### Scenario: Successful stop
- **WHEN** the user stops a healthy recording
- **THEN** the server closes the zstd stream, writes final counts/checksums and manifest, creates the canonical `.nvrdata` archive, validates it, and atomically marks it complete

### Requirement: Canonical versioned archive
A completed session SHALL contain a supported major schema version, application commit/build, collection configuration, roster, quality counters, reset/event summary, and `telemetry.fbs.zst`; unknown major versions SHALL be rejected.

#### Scenario: Prototype archive
- **WHEN** a current `telemetry.bin` or unversioned `telemetry.zst` prototype archive is opened
- **THEN** tooling labels it unsupported or imports only provably recoverable fields with explicit missing-data flags and never treats it as training-ready

### Requirement: Local privacy by default
The recorder SHALL remain local unless the user performs an explicit export/upload action and SHALL omit or per-session hash network addresses, MACs, and stable hardware identifiers by default.

#### Scenario: Default manifest inspection
- **WHEN** a completed manifest is inspected under default settings
- **THEN** it contains no raw IP address, MAC address, account identifier, or globally stable hardware identifier

### Requirement: Dataset-ready gate
Production collection SHALL remain disabled until schema round-trip, numerical, mixed-transport metadata, reset-label, RPC/UI, crash-recovery, and bounded-memory tests pass and a pilot archive loads successfully in Python with zero fatal validator findings.

#### Scenario: Gate failure
- **WHEN** any required dataset-ready check fails in the target build
- **THEN** the UI identifies the build as not dataset-ready and prevents production-profile recording
