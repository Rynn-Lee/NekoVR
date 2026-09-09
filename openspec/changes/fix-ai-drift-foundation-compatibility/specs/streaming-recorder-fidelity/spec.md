## ADDED Requirements

### Requirement: Truthful attainable collection profiles
The recorder SHALL distinguish global/context requirements from per-tracker capabilities, SHALL advertise only channels that production sources can provide with their declared semantics, and SHALL permit each collection profile to start for a supported topology while representing compatible legacy omissions as unavailable.

#### Scenario: Full-fidelity topology
- **WHEN** a supported full-fidelity tracker topology advertises all required device and global inputs
- **THEN** recording starts without requiring every physical tracker to advertise controller, skeleton, floor, or model-global channels

#### Scenario: Legacy tracker
- **WHEN** an older tracker lacks an optional native channel allowed by the selected profile
- **THEN** the archive represents that channel as unavailable and never records a fabricated valid zero

### Requirement: Canonical production capability negotiation
UDP and HID/nRF production readers SHALL translate firmware capability/presence bits through one canonical channel registry and SHALL update tracker telemetry only for values actually present in a packet.

#### Scenario: UDP calibration capability
- **WHEN** packet 28 advertises packet-29 calibration quality
- **THEN** the tracker advertises channel 22 and does not incorrectly advertise temperature channel 8

#### Scenario: HID/nRF native sample
- **WHEN** supported dongle firmware reports sequence, device timestamp, gyro, counters, charging, or uptime
- **THEN** the production HID/nRF reader negotiates and publishes those values with firmware provenance

### Requirement: Complete immutable sample roster
Every identity referenced by a frame SHALL be allocated in the initial roster, including HMD, controllers, physical trackers, and recorded context sources; reconnects and later topology changes SHALL use stable events without creating an unrostered sample ID.

#### Scenario: Controller context
- **WHEN** controller pose is included in a recorded frame
- **THEN** its session tracker ID resolves to initial roster metadata and includes both orientation and position validity

### Requirement: Complete context and correction telemetry
The archive SHALL preserve the declared positional, skeleton, body, floor, activity, native-device, network, power, legacy-correction, and model-correction fields with units, cadence, validity, and provenance. Registry declarations SHALL NOT be treated as proof that a value was captured.

#### Scenario: Corrected frame
- **WHEN** AI correction is evaluated for a tracker
- **THEN** input schema/model identity, slot mapping, prediction/confidence/drift rate, gate outcome, applied correction, epoch/history, latency, legacy state, and final output remain independently reconstructable

#### Scenario: Activity cohort
- **WHEN** available HMD/skeleton/contact context supports a declared posture or motion cohort
- **THEN** the frame interval records that cohort and confidence, otherwise it records `UNKNOWN` rather than forcing an unsupported class

### Requirement: Loss-safe bounded streaming
Sample queue overflow SHALL remain non-blocking and bounded but SHALL preserve prior gap accounting, reset lifecycle events, and completed reset labels until they are durably accepted or the recording fails with an explicit integrity error.

#### Scenario: Reset during overflow
- **WHEN** the sample queue is full while a reset request and application are published
- **THEN** frames may be counted as dropped, but the next valid archive contains correlated reset events, labels, and complete gap counts exactly once

### Requirement: Recorder fidelity evidence
Automated tests SHALL cover attainable profiles, production transport parsers, complete context/correction fields, overflow with resets, disk failure, durable recovery, and a simulated multi-hour run with bounded heap/direct memory and advancing on-disk output.

#### Scenario: Simulated multi-hour soak
- **WHEN** the recorder processes the configured multi-hour synthetic duration
- **THEN** memory remains within the declared budget, durable telemetry advances, and counters reconcile after finalization
