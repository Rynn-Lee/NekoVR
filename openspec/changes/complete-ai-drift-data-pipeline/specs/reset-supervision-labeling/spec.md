## ADDED Requirements

### Requirement: Typed reset event emission
The reset pipeline SHALL emit one typed event for every requested and applied yaw, full, or mounting reset, including source, affected body parts, request time, apply time, reference validity, and success/failure state.

#### Scenario: Scheduled yaw reset
- **WHEN** a delayed yaw reset completes
- **THEN** the dataset contains both the request timing and the actual application timing with type `YAW_RESET`

#### Scenario: Failed or cancelled reset
- **WHEN** a reset is cancelled or cannot be applied
- **THEN** the event records the outcome and is not used as a successful correction label

### Requirement: Per-tracker reset snapshots
For each physical IMU affected by a reset, the system SHALL record raw orientation, calibrated pre-AI orientation, adjustment-chain state immediately before and after the reset, body role, HMD reference, and channel validity.

#### Scenario: Full reset of multiple trackers
- **WHEN** a full reset affects eight IMU trackers
- **THEN** the event contains eight independently addressable tracker label records rather than one aggregate delta

#### Scenario: Partial body reset
- **WHEN** a reset targets selected body parts
- **THEN** only trackers actually affected receive correction labels and all excluded trackers remain identifiable as unaffected

### Requirement: Canonical correction target
The system SHALL derive and store a normalized per-tracker correction quaternion using the declared transform convention `q_target = q_post_adjusted * inverse(q_pre_adjusted)`, together with axis supervision masks and a redundant yaw delta for diagnostics.

#### Scenario: Known transform fixture
- **WHEN** a test reset applies a known 30-degree yaw transform
- **THEN** the stored quaternion composes the pre-reset orientation to the post-reset orientation within numerical tolerance and its diagnostic yaw is approximately 30 degrees

#### Scenario: Yaw-only reset
- **WHEN** the event type is yaw reset
- **THEN** the label's supervision mask permits yaw loss and does not imply valid roll/pitch ground truth

### Requirement: Label context windows
Each successful reset label SHALL reference configurable pre-reset and post-reset frame ranges by stable frame indices, with sufficient context to reconstruct motion and drift immediately surrounding the reset.

#### Scenario: Complete context
- **WHEN** all required frames exist and trackers remain valid
- **THEN** the label is marked complete and both ranges resolve to ordered frame sequences

#### Scenario: Reset near session boundary
- **WHEN** recording begins or ends before a complete context window exists
- **THEN** the label remains present but is marked truncated with the available ranges

### Requirement: Label quality flags
The labeler SHALL compute machine-readable quality flags for stale/missing HMD, excessive player motion, tracker packet gaps, reconnects, reassignment, invalid quaternions, overlapping resets, and insufficient context.

#### Scenario: Player moves during reset
- **WHEN** angular/linear motion exceeds the configured label-quality threshold around reset
- **THEN** the label is retained with a motion-quality warning and training excludes or downweights it according to configuration

### Requirement: Reset semantics remain separated
Yaw, full, and mounting resets SHALL remain distinguishable training targets, and mounting changes SHALL not be silently treated as drift corrections.

#### Scenario: Mounting reset
- **WHEN** a mounting reset changes sensor-to-bone alignment
- **THEN** the event is assigned to the mounting domain/task and is excluded from yaw-drift loss unless an explicit multi-task configuration includes it

### Requirement: Correction history discontinuity
Applying any reset SHALL synchronously rebase or clear legacy drift state, AI feature history, pending inference, and applied AI correction for affected trackers according to the reset type.

#### Scenario: Reset while inference is pending
- **WHEN** an inference result computed from pre-reset history arrives after the reset
- **THEN** sequence/epoch validation rejects it and it cannot reapply the old correction

### Requirement: Reset instrumentation without global singleton dependency
Tracker reset and adjustment code SHALL receive correction/event collaborators through explicit ownership or interfaces and SHALL operate in unit tests without requiring `VRServer.instance` initialization.

#### Scenario: Existing reset unit suite
- **WHEN** reset and skeleton tests construct standalone trackers
- **THEN** all existing reset behavior tests pass with AI disabled and no global server instance

### Requirement: Reset-label conformance tests
The project SHALL include fixtures for wraparound yaw, quaternion sign equivalence, partial/full/mounting resets, multi-tracker resets, invalid references, and delayed resets.

#### Scenario: Quaternion sign equivalence
- **WHEN** pre/post quaternions differ only by the equivalent `q` versus `-q` representation
- **THEN** the derived correction is identity within tolerance and no 360-degree label is produced
