## ADDED Requirements

### Requirement: Reproducible training workspace
The repository SHALL provide a pinned Python ML workspace with documented commands for AMASS preparation, real-session preparation, training, evaluation, ONNX export, and ONNX validation.

#### Scenario: Fresh environment
- **WHEN** a developer creates the documented supported environment and supplies licensed external assets
- **THEN** all commands resolve pinned dependencies and produce versioned outputs without manual notebook-only steps

### Requirement: Licensed AMASS and body-model ingestion
The AMASS importer SHALL accept user-provided AMASS and compatible SMPL-family model paths, record source/license identifiers and hashes, and SHALL not redistribute restricted source motion or body-model assets.

#### Scenario: Missing SMPL asset
- **WHEN** AMASS preparation is invoked without the required body-model files
- **THEN** it exits with an actionable licensing/path error and does not emit incomplete training samples

### Requirement: Canonical human-motion conversion
AMASS poses SHALL be converted into the same coordinate, quaternion, body-role, timing, and unit contract used by real session data, with configurable resampling and joint-to-segment mappings.

#### Scenario: Conversion fixture
- **WHEN** a known SMPL pose sequence is converted
- **THEN** virtual segment orientations and HMD/root reference match the canonical transform fixture within tolerance

### Requirement: Synthetic tracker layouts and corruption
The pipeline SHALL generate configurable tracker layouts including 5, 6, 8, 10, and additional supported counts, and SHALL synthesize sensor frames, mounting offsets, bias/random-walk drift, noise, latency, packet loss, stale samples, reset timing, and channel unavailability.

#### Scenario: Layout augmentation
- **WHEN** one AMASS motion is prepared for training
- **THEN** configured body-role subsets can produce multiple masked layouts without changing target semantics

#### Scenario: Drift target generation
- **WHEN** a known synthetic orientation error is applied
- **THEN** the generated correction target inverts that error under the canonical composition convention

### Requirement: Real-session ingestion and validation
The session importer SHALL validate schema/checksums, decode validity/provenance masks, extract reset windows, report quality statistics, and reject fatal corruption before training.

#### Scenario: Valid pilot archive
- **WHEN** a dataset-ready pilot archive is imported
- **THEN** the pipeline reports frame/layout/channel/reset-label counts and emits canonical samples linked to the source hash

#### Scenario: Corrupt archive
- **WHEN** telemetry checksum or frame bounds are invalid
- **THEN** the archive is rejected and no samples from it enter a split

### Requirement: Sparse and domain-aware supervision
Training SHALL distinguish synthetic dense correction targets, real reset-derived sparse targets, temporal/self-supervised objectives, yaw/full/mounting axes, and channel provenance through explicit loss masks and configurable weights.

#### Scenario: Real yaw reset
- **WHEN** a real label supervises yaw only
- **THEN** roll/pitch target losses are masked out for that sample

#### Scenario: Low-quality reset
- **WHEN** a label has configured quality warnings
- **THEN** it is excluded or downweighted deterministically and the decision is included in run statistics

### Requirement: Closed-loop training safeguards
Training SHALL distinguish measured/raw observations, human reset targets, external reference targets, global-teacher pseudo-labels, model predictions and previously applied corrections, and SHALL never use a corrected final output as equivalent to independent ground truth.

#### Scenario: Session recorded with AI enabled
- **WHEN** a training window includes corrections from a known active model
- **THEN** raw/pre-AI features remain the replay source, model-derived channels are tagged, and loss construction follows the configured off-policy/pseudo-label rules

### Requirement: Telemetry feature selection and ablation
The training pipeline SHALL ingest the full channel registry but SHALL promote temperature, network, power, hardware or other contextual signals into production model inputs only after normalization, missingness robustness, ablation and unseen-device/session cohort evaluation.

#### Scenario: Spurious temperature correlation
- **WHEN** temperature improves training loss but regresses held-out hardware or users
- **THEN** the candidate feature/model fails promotion or the signal is regularized/removed

### Requirement: Required activity coverage
Model evaluation and promotion SHALL separately cover standing, seated, lying, crouching, transitions, locomotion, dance/high-dynamics, and stationary intervals, with unknown/low-confidence intervals excluded from cohort claims.

#### Scenario: Aggregate improvement with lying regression
- **WHEN** aggregate correction error improves but the lying cohort exceeds its regression threshold
- **THEN** the model fails production promotion

### Requirement: Leakage-safe grouped splits
Train, validation, and test sets SHALL be grouped so no AMASS subject/source sequence or real user/session/device cohort appears across incompatible splits, and normalization statistics SHALL use training data only.

#### Scenario: Split audit
- **WHEN** split metadata is validated
- **THEN** the audit finds zero forbidden group overlaps and reports per-layout/per-chipset coverage

### Requirement: Variable-layout causal model contract
The trained model SHALL consume causal history shaped by time, slots, features, body-role IDs, slot masks, channel-validity masks, and time deltas, and SHALL emit bounded per-slot correction plus confidence without fixed tracker-count presets.

#### Scenario: Padded five-tracker input
- **WHEN** a five-tracker sample is padded to a ten-slot model
- **THEN** valid outputs correspond only to the five mapped slots and masked slots do not affect global context or loss

#### Scenario: Tracker ordering
- **WHEN** equivalent inputs use a different slot order with updated role/mask mappings
- **THEN** outputs permute consistently within numerical tolerance

### Requirement: Evaluation by safety and cohort
Evaluation SHALL report angular error before/after correction, reset-target error, confidence calibration, false correction on clean motion, correction rate/magnitude, temporal jitter, discontinuities, yaw/full resets per hour, time to first reset and longest valid no-reset interval, grouped by layout, activity/posture, chipset/transport provenance, drift severity, person and real versus synthetic domain.

#### Scenario: Regression threshold
- **WHEN** a candidate improves aggregate error but exceeds the configured false-correction or jitter limit on clean sessions
- **THEN** the candidate fails promotion

#### Scenario: Reset reduction target
- **WHEN** a candidate is evaluated in replay and staged multi-hour dogfood against AI-disabled and current-base baselines
- **THEN** it must meet the configured yaw-reset-rate and time-to-first-reset improvement without violating safety or activity-cohort gates

### Requirement: Reproducible model artifact
Every exported model SHALL include sidecar metadata with feature-schema hash, normalization statistics, supported roles/layout bounds, context bounds, output convention, opset, training config/seed/commit, dataset hashes, validation metrics, artifact SHA-256, and performance tier.

#### Scenario: Repeatable run
- **WHEN** training/export repeats with the same inputs, seed, environment, and deterministic mode
- **THEN** metrics and outputs satisfy the documented reproducibility tolerance and provenance identifies all inputs

### Requirement: Personalization-ready base artifacts
Each base model offered for local personalization SHALL publish compatible frozen-backbone training, evaluation, optimizer and nominal checkpoint artifacts for a bounded personal adapter, together with trainable parameter names and resource expectations.

#### Scenario: Non-personalizable model
- **WHEN** a model lacks compatible signed training artifacts
- **THEN** it remains usable for inference but is not offered as a personal-training base

### Requirement: ONNX parity and size gate
Export SHALL use supported ONNX operators and SHALL compare framework and ONNX Runtime outputs over representative masked layouts before publishing; the small production artifact SHALL be at most 15 MiB unless benchmark policy is explicitly revised.

#### Scenario: Parity failure
- **WHEN** maximum or percentile output error exceeds the declared tolerance on any required layout
- **THEN** export validation fails and the model is not added to the catalog
