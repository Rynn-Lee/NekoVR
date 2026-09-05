## ADDED Requirements

### Requirement: Global base model selection
Personal training SHALL start from a validated personalization-ready NekoVR base model that already contains the human-motion prior learned from AMASS and global real sessions; raw AMASS data SHALL not be presented as an executable model choice.

#### Scenario: Recommended base
- **WHEN** a user opens Personal Training with compatible assigned trackers
- **THEN** the system preselects the latest recommended compatible base model and shows its validation and personalization support state

### Requirement: Personal profile ownership
Personal models and jobs SHALL belong to a local pseudonymous personal profile containing body proportions, compatible body-role layout history, sensor-family summary and provenance without requiring an online account.

#### Scenario: Multiple players share a computer
- **WHEN** two personal profiles train from separate session sets
- **THEN** their checkpoints, normalization, models and recent-model history remain independently identifiable and are never mixed automatically

### Requirement: Dataset eligibility and coverage
Before training, the system SHALL validate selected sessions for schema/base-feature compatibility, integrity, personal profile, stable body assignments, valid duration, reset/no-reset evidence, channel quality, tracker layouts and activity coverage; raw hours alone SHALL not imply readiness.

#### Scenario: Nine long sessions
- **WHEN** nine 5-8 hour sessions are selected
- **THEN** eligibility reports usable hours/windows, excluded corrupt or low-quality ranges, reset labels, clean intervals, layouts, sensor families and posture/activity coverage before training starts

#### Scenario: Insufficient held-out data
- **WHEN** all selected data belongs to one inseparable session with no safe validation/test holdout
- **THEN** the recommended training flow refuses production-grade personalization or marks the result experimental and non-activatable

### Requirement: Adapter-only personalization by default
The default personal trainer SHALL freeze the global human-motion backbone and update only a declared compact adapter or conditioning head, with bounded trainable parameter count and output correction limits.

#### Scenario: Personal training graph inspection
- **WHEN** a base model's training artifacts are loaded
- **THEN** only metadata-declared adapter parameters require gradients and all backbone parameters remain frozen

### Requirement: Local isolated trainer worker
Training SHALL run in a signed, hash-verified, out-of-process trainer worker over typed local IPC so a trainer failure, cancellation or resource exhaustion cannot stop tracking or corrupt the active inference session.

#### Scenario: Worker crash
- **WHEN** the trainer process terminates unexpectedly
- **THEN** normal server tracking and the active model continue, the job becomes recoverable/failed with diagnostics, and the last valid checkpoint remains intact

### Requirement: ONNX Runtime Training artifacts
The production personal trainer SHALL consume pre-generated training/eval/optimizer/checkpoint artifacts, save resumable checkpoints, and export an inference-ready ONNX model that passes the same schema/runtime validation as global models.

#### Scenario: Successful export
- **WHEN** training and held-out evaluation pass
- **THEN** the worker exports a personal ONNX model and sidecar metadata with base/profile/dataset/checkpoint hashes and runtime parity results

### Requirement: Portable baseline and truthful acceleration
CPU adapter training SHALL be supported on target desktop platforms; CUDA acceleration MAY be offered only after a successful training probe. DirectML inference availability SHALL not be reported as DirectML training support without an independently packaged and verified training backend.

#### Scenario: AMD system with DirectML inference
- **WHEN** DirectML inference works but no compatible training provider is installed
- **THEN** Personal Training offers bounded CPU training and accurately reports that DirectML training is unavailable

### Requirement: Resource-aware coexistence
The trainer SHALL enforce configurable CPU threads, GPU memory/utilization, RAM, disk and I/O budgets and SHALL default to pausing or throttling while an active VR session is detected.

#### Scenario: Player starts VR during training
- **WHEN** active tracking load crosses the configured threshold
- **THEN** the training job checkpoints and pauses or throttles according to policy without affecting correction latency

### Requirement: Deterministic balanced sampling and splits
The personal trainer SHALL stream/cache balanced windows instead of loading all sessions into memory and SHALL split by whole session/time so validation/test windows do not leak into training; activity, layout and correction/no-correction cohorts SHALL be represented where data permits.

#### Scenario: Resume same job
- **WHEN** a paused job resumes from a checkpoint with unchanged inputs
- **THEN** split membership, sampler seed, preprocessing cache and metric lineage remain consistent

### Requirement: Personal validation and fallback
A personal model SHALL be activatable only when held-out metrics improve or remain within policy relative to its base model for correction error, reset replay, estimated/observed resets per hour, time to first reset, clean-motion false correction, jitter and every sufficiently represented activity/layout cohort.

#### Scenario: Dance improves but sitting regresses
- **WHEN** the personal model improves dance metrics but exceeds the sitting regression threshold
- **THEN** validation fails and the global base remains the recommended active model

#### Scenario: Unsupported tracker topology
- **WHEN** the player later connects a layout outside the personal model's declared compatibility
- **THEN** the server falls back to a compatible base model or disables unsupported slots instead of extrapolating silently

### Requirement: Staged visual progress and job control
The Personal Training tab SHALL show authoritative stages for validation, indexing, preprocessing/cache, split, training epochs, evaluation, ONNX export, parity and final validation, with percent, ETA, current/best validation metric, activity/layout coverage, resource use, checkpoint state and actionable errors; jobs SHALL support pause, resume and cancel.

#### Scenario: Application restart
- **WHEN** the application restarts with a checkpointed unfinished job
- **THEN** the tab restores job inputs, stage, checkpoint and compatible resume/cancel actions from server state

### Requirement: Minimal guided presets
The default UI SHALL offer `Quick`, `Balanced` and `Thorough` presets that define validated epochs/window budgets and early-stopping policy, while advanced hyperparameters remain hidden unless developer/advanced mode is enabled.

#### Scenario: Balanced default
- **WHEN** an eligible profile starts training without advanced changes
- **THEN** the recommended Balanced preset chooses safe resource and early-stopping defaults based on data volume and hardware probe

### Requirement: Safe personal-model activation
After successful validation, activation SHALL be transactional, optionally run a short shadow replay/live phase, reset inference history, retain the previous active model for one-action rollback, and never automatically overwrite a global base artifact.

#### Scenario: Shadow validation fails
- **WHEN** the newly trained model produces invalid, unstable or over-budget outputs in shadow mode
- **THEN** activation is blocked or rolled back and the prior model remains active

### Requirement: Personal training privacy and provenance
Sessions, caches, checkpoints and personal models SHALL remain local by default, and every output SHALL record profile pseudonym, base hash, selected session hashes, split manifest, settings, metrics and worker/runtime versions without raw personal identifiers.

#### Scenario: Export personal model
- **WHEN** a user explicitly exports a personal model
- **THEN** the package contains sufficient technical provenance for reproducibility but excludes raw session telemetry and identifying hardware/network strings by default
