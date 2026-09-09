## ADDED Requirements

### Requirement: Personal eligibility SHALL be derived from canonical session bytes

The system SHALL compute personal-training integrity, profile ownership, base/feature compatibility, usable duration/windows, reset and clean-motion coverage, stable body assignment, sensor families, layouts, and activity cohorts from validated canonical `.nvrdata` records using bounded reads. Any summary or cache SHALL be bound to the canonical archive hash and SHALL NOT supply authoritative values independently.

#### Scenario: Forged summary cannot admit a job

- **WHEN** a session summary claims valid hours, labels, coverage, or a digest that is not demonstrated by its canonical archive
- **THEN** eligibility is not ready and the server rejects personal-job creation with a typed finding

#### Scenario: Eligible sessions produce an immutable plan

- **WHEN** validated sessions satisfy the selected profile, base model, feature schema, and coverage policy
- **THEN** the server persists a hash-bound whole-session split and deterministic streamed-window plan with suitable recent validation and test holdouts

### Requirement: Personal training SHALL execute through the verified installed worker

Production personal jobs SHALL use the installed signature-verified trainer process and worker-executable artifacts derived from the exact base model. Typed IPC SHALL correlate requests and progress, distinguish CPU, CUDA-training, and DirectML-inference capabilities, bound process output and resources, and report crashes without affecting active correction.

#### Scenario: Missing or tampered worker fails closed

- **WHEN** the worker package, signature, executable artifact, base identity, or provider probe is missing or invalid
- **THEN** the job does not start and the authoritative status identifies the failed verification

#### Scenario: Active VR pause is checkpointed

- **WHEN** measured resource policy requires a pause during active tracking
- **THEN** the worker first commits and acknowledges an atomic checkpoint containing verified model, optimizer, sampler, and progress state before the server reports the job paused

### Requirement: Personal RPC and UI SHALL expose authoritative lifecycle state

Install, probe, profile, eligibility, job creation/control/status, evaluation, export, and activation SHALL use generated typed RPC bindings with strict argument validation, asynchronous progress, reconnect recovery, and multi-client broadcasts. The rendered Personal Training tab SHALL localize and gate controls from server state and SHALL show actionable authoritative coverage, progress, resource, metric, and error details.

#### Scenario: Ineligible selection remains blocked

- **WHEN** eligibility has a blocking finding or becomes stale relative to the selected profile, base, feature schema, sessions, or policy
- **THEN** both server and renderer reject job creation and show the authoritative reason

### Requirement: Resume SHALL verify durable checkpoint evidence

Job restoration SHALL verify referenced checkpoint bytes and hash plus compatible base, artifacts, optimizer, sampler, and progress state before offering resume. A worker crash or application restart SHALL not interrupt or mutate the independently running correction engine.

#### Scenario: Metadata-only checkpoint is rejected

- **WHEN** resumable metadata references a missing, changed, truncated, or incompatible checkpoint
- **THEN** restoration marks the job failed or non-resumable and never starts training from that reference

### Requirement: Validation and export SHALL be checkpoint-derived and atomic

Personal validation SHALL execute both the selected base and candidate checkpoints over the persisted held-out reset and clean-motion windows and derive the declared overall and sufficiently represented activity/layout metrics. Export SHALL atomically publish the ONNX model, sidecar, validation report, three-runtime parity evidence, and all base/profile/session/split/checkpoint/metric hashes as one verified bundle.

#### Scenario: Caller-created records cannot validate a model

- **WHEN** a caller supplies passing metrics or prediction records that are not produced by replaying the hash-bound checkpoints and holdout plan
- **THEN** evaluation/export rejects them as unbound evidence

#### Scenario: Publication failure leaves no activatable partial bundle

- **WHEN** any model, sidecar, evidence, parity, hash, or final commit step fails
- **THEN** no partial personal model becomes visible to catalog or activation

### Requirement: Personal activation SHALL be transactional and evidence-driven

Personal activation SHALL share the serialized model-control boundary, optionally run a retained shadow evaluation, reset inference history, retain and persist the prior compatible model/configuration, fall back on topology incompatibility, and provide one-action rollback. Shadow promotion SHALL be derived from server evidence rather than a caller-supplied boolean.

#### Scenario: Activation or rollback load fails

- **WHEN** candidate activation, persistence, history reset, topology validation, or previous-model restoration fails
- **THEN** the server synchronously restores a known compatible state or disables correction and reports the truthful failure without claiming the failed model active

### Requirement: Installed-package evidence SHALL exercise the complete personal lifecycle

Release verification SHALL launch the final bundled application and signed worker and traverse nine bounded multi-hour canonical session fixtures through eligibility, split/window streaming, training, checkpoint resume, validation, atomic export, shadow activation, topology fallback, and rollback through the production RPC/UI path without loading all telemetry into memory.

#### Scenario: In-process helper test is insufficient

- **WHEN** a test directly calls worker, validation, export, or activation helpers with synthetic summaries or prediction records
- **THEN** it may provide unit coverage but SHALL NOT satisfy installed-package end-to-end evidence
