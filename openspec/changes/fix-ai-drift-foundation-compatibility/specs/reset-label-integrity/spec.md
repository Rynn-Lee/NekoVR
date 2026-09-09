## ADDED Requirements

### Requirement: Adjusted-state reset snapshots
Each successful physical-IMU reset label SHALL serialize raw, calibrated pre-AI, and adjusted orientations, the complete adjustment chain, channel validity, status, sample age, body role, HMD reference, and before/after reset and calibration epochs.

#### Scenario: Per-tracker reset snapshot
- **WHEN** a reset affects multiple physical IMU trackers
- **THEN** every affected tracker has independently addressable before/after states and no unaffected tracker receives a successful label

### Requirement: Canonical adjusted correction target
The reset target SHALL be `normalize(q_post_adjusted * inverse(q_pre_adjusted))` in the declared world-frame composition order. Calibrated pre-AI observations SHALL remain available but SHALL NOT replace adjusted orientations in target derivation.

#### Scenario: Non-commuting adjustment chain
- **WHEN** pre-AI and later adjustment stages contain non-commuting rotations
- **THEN** the stored target composes the serialized pre-reset adjusted orientation to the serialized post-reset adjusted orientation within tolerance

### Requirement: Durable correlated reset lifecycle
The recorder SHALL retain one request event and one terminal applied, cancelled, or failed outcome for each reset request ID, and SHALL link each successful per-tracker label to the applied event even during sample backpressure.

#### Scenario: Cancelled delayed reset during overflow
- **WHEN** a delayed reset is requested and cancelled while canonical samples are being dropped
- **THEN** the archive contains the correlated request and cancellation exactly once and contains no successful correction label

### Requirement: Deterministic configurable label quality
Reference-age and motion thresholds SHALL be explicit configuration, and quality flags SHALL be derived over resolvable pre/post windows for stale HMD, motion, packet gaps, reconnect/reassignment, invalid quaternions, overlaps, insufficient context, and truncation.

#### Scenario: Reassignment in context window
- **WHEN** the labeled tracker changes assignment inside a reset context window
- **THEN** the label carries the reconnect/reassignment flag and the configured training policy is reproducible from archived fields

### Requirement: Reset relation validation
Validators SHALL reject inconsistent request/event/label relationships, unrostered label trackers, incompatible domain/axis masks, regressing epochs, and unresolved context ranges unless an explicit boundary-truncation flag explains the missing frames.

#### Scenario: Missing context frame
- **WHEN** a label claims a complete window whose indexed frame range is not present
- **THEN** validation fails instead of accepting the label as complete training supervision

### Requirement: Reset discontinuity coverage
Tests SHALL exercise yaw, full, and mounting epochs through the real publisher/recorder path and SHALL prove queued or in-flight results from previous epochs cannot restore a stale correction.

#### Scenario: In-flight result after mounting reset
- **WHEN** an inference result from the prior epoch completes after mounting reset application
- **THEN** it is rejected and the archived post-reset correction state remains unapplied for that result
