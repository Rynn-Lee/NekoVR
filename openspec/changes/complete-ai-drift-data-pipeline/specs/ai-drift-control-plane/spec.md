## ADDED Requirements

### Requirement: Typed recorder RPC state machine
SolarXR FlatBuffers RPC SHALL define start, stop/finalize, cancel, status, list, validate, recover, delete, export/reveal operations and recorder events with explicit states, request IDs, session IDs, progress, counters, readiness findings, and typed errors.

#### Scenario: Start and stop lifecycle
- **WHEN** an authorized client starts and later stops a recording
- **THEN** all clients observe ordered server states from readiness through recording, finalizing, validation, and completed archive

#### Scenario: Client disconnect
- **WHEN** the initiating GUI disconnects while recording
- **THEN** server policy determines whether recording continues or finalizes and reconnecting clients can recover authoritative status

### Requirement: Server-owned dataset inventory
The control plane SHALL list only server-validated completed or explicitly recoverable sessions and SHALL provide actual path, size, duration, frame/drop/reset counts, schema version, validation state, and roster summary.

#### Scenario: Reveal completed session
- **WHEN** the user requests to reveal a completed local session
- **THEN** Electron opens the containing datasets folder or selects the actual server-reported file rather than opening the logs folder or reconstructing an empty archive

#### Scenario: Delete session
- **WHEN** the user confirms deletion of a completed session
- **THEN** the server deletes only the resolved file inside the managed datasets root and broadcasts the updated inventory

### Requirement: Typed model management RPC
FlatBuffers RPC SHALL define local model import, catalog refresh, catalog download, load, unload, status, and error operations, including model hash, metadata summary, download progress, validation result, and active state.

#### Scenario: Local model selection
- **WHEN** the user selects an ONNX file
- **THEN** the server imports and validates it through a managed path and the UI reports loaded only after activation acknowledgement

### Requirement: Verifiable remote catalog
Remote catalogs SHALL be parsed from JSON, enforce HTTPS and an allowed host policy, include model/metadata URLs, byte sizes and SHA-256 hashes, and represent downloaded state from actual files rather than hard-coded booleans.

#### Scenario: Hash mismatch
- **WHEN** a downloaded model does not match its catalog hash
- **THEN** the temporary file is rejected/quarantined, never activated, and a typed integrity error is shown

#### Scenario: Catalog parse failure
- **WHEN** a server returns invalid JSON or unsupported schema
- **THEN** existing verified local models remain usable and the failure is reported without fabricated default downloads

### Requirement: Configurable inference contract
The control plane SHALL expose persisted enabled state, requested provider, context window within model bounds, confidence threshold, correction safety limits, legacy-composition mode, and per-model tracker/body-role slot mappings.

#### Scenario: Invalid context length
- **WHEN** a client requests a context outside model metadata bounds
- **THEN** the server rejects the setting with allowed bounds and does not partially apply the configuration

#### Scenario: Layout mapping change
- **WHEN** slot mapping changes while AI is active
- **THEN** the server validates uniqueness/roles, applies it atomically, and invalidates affected history epochs

### Requirement: Truthful runtime and compatibility status
The UI SHALL display server-reported active provider, model hash, validation/load state, compatibility warning, latency, mapped trackers, confidence/correction status, and last error; it SHALL not equate WebGL GPU detection with ONNX provider availability.

#### Scenario: DirectML compatibility mode
- **WHEN** the server successfully activates DirectML
- **THEN** the UI identifies DirectML as active and shows its compatibility notice based on server status

### Requirement: Recorder UI uses authoritative data
The AI Drift page SHALL show server readiness findings, live elapsed time, actual written/queued/dropped frames, disk use, reset count, active roster, finalization progress, and server dataset inventory.

#### Scenario: Dropped frame
- **WHEN** the server reports a recorder queue drop
- **THEN** the UI increments the dropped count and marks session quality without inventing a constant frame total from a browser timer

### Requirement: Feature-complete model controls
The UI SHALL provide local ONNX selection, verified catalog download, provider selection including AUTO/CPU/CUDA/TensorRT/DirectML when packaged, context setting, confidence setting, tracker mapping, enable/disable, unload, and actionable validation errors.

#### Scenario: Unsupported provider
- **WHEN** a provider is not packaged or probe-tested on the current server platform
- **THEN** the control is disabled or rejects activation with the server's reason and never displays a false active badge

### Requirement: Recent and pinned model access
The server SHALL maintain a hash-based most-recently-used and pinned model list with last-used time, global/personal type, profile, compatibility and validation state, and the Correction tab SHALL provide one-action transactional switching among compatible entries.

#### Scenario: Player starts a session
- **WHEN** the player's assigned tracker layout matches a recently used personal model
- **THEN** that model appears near the top of the selector with its profile and compatibility state and can be activated after server validation

#### Scenario: Recent model became incompatible
- **WHEN** body assignments or model schema no longer match a recent model
- **THEN** the entry remains identifiable but cannot be activated silently and the UI offers a compatible base fallback

### Requirement: Three-tab AI Drift page
The AI Drift page SHALL use top-level `Correction`, `Datasets` and `Personal Training` tabs with compact full-width work surfaces consistent with the existing application, without nested decorative cards or duplicated recorder/model state.

#### Scenario: Tab navigation during recording
- **WHEN** the user moves between tabs while recording or training
- **THEN** server-owned jobs continue and each tab restores authoritative state without restarting work

### Requirement: Typed personal-training RPC
FlatBuffers RPC SHALL define trainer installation/probe, profile, session eligibility, job create/start/pause/resume/cancel/status, cache/checkpoint, evaluation, export and activate operations with job IDs, stages, progress, ETA, resources, metrics, coverage and typed errors.

#### Scenario: Multiple client windows
- **WHEN** two clients observe one training job
- **THEN** both receive the same ordered server/worker stage and progress state and conflicting commands are resolved by job version/request ID

### Requirement: Personal-training workflow
The Personal Training tab SHALL allow selection of a validated base model, personal profile, eligible sessions, Quick/Balanced/Thorough preset and resource policy, then show staged progress, held-out comparison and export/activation actions.

#### Scenario: Recommended path
- **WHEN** compatible sessions and a personalization-ready base exist
- **THEN** the tab preselects the recommended base and eligible sessions, shows quality/activity/layout coverage, and enables start only after all blocking findings are resolved

### Requirement: Trainer package integrity
Optional trainer binaries and training artifacts SHALL be downloaded only from an allowlisted HTTPS catalog with platform, version, size, license and SHA-256 metadata and SHALL execute only after signature/hash and compatibility probes pass.

#### Scenario: Trainer package mismatch
- **WHEN** the downloaded trainer or base training artifact hash differs from catalog metadata
- **THEN** it is quarantined, never executed, and the UI reports an integrity failure

### Requirement: Safe Electron file and URL boundary
Electron IPC used by datasets/models SHALL use typed channel contracts, enumerated managed roots or user-approved dialog paths, resolved containment checks, HTTPS host allowlists, and no arbitrary renderer-supplied write or open paths.

#### Scenario: Path traversal request
- **WHEN** a renderer request resolves outside the managed datasets/models root without a user file dialog grant
- **THEN** Electron rejects it and records a security error

#### Scenario: Untrusted model URL
- **WHEN** a catalog or renderer supplies a URL outside the configured HTTPS allowlist
- **THEN** the download/open operation is rejected

### Requirement: Localized accessible control surface
All new visible strings SHALL use Fluent resources with English and Russian baseline translations, and controls SHALL use existing accessible components, labels, focus behavior, and supported button variants.

#### Scenario: Typecheck and localization validation
- **WHEN** the GUI validation pipeline runs
- **THEN** TypeScript, ESLint, Prettier, missing-message checks, and production renderer/Electron builds succeed

### Requirement: Persisted configuration compatibility
AI and recorder settings SHALL be stored in versioned server configuration with validated defaults and migration behavior, while session-specific settings are snapshotted into each manifest.

#### Scenario: Upgrade from prototype build
- **WHEN** no valid versioned AI configuration exists
- **THEN** AI correction defaults disabled, unsafe prototype model state is not activated, and normal tracking starts successfully

### Requirement: Distribution packaging gate
The final Gradle/Electron/jpackage distribution SHALL include the React client, Java server, generated protocol bindings, required ONNX native runtime/provider files for its platform, licenses, and startup paths, and SHALL pass installed-package smoke tests.

#### Scenario: Installed offline CPU mode
- **WHEN** the packaged application starts without network access on a supported CPU-only system
- **THEN** normal tracking, local dataset recording, local model import, and CPU-provider validation work without downloading runtime components

### Requirement: Baseline regression gate
Existing server reset tests and GUI build checks SHALL pass before dataset/model features can be marked ready.

#### Scenario: Standalone tracker tests
- **WHEN** the server core unit suite runs without an initialized `VRServer` singleton
- **THEN** existing reset, mounting, reference adjustment, skeleton reset, and tracking pause tests pass
