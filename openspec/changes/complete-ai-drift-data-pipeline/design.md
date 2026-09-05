## Context

The repository currently instantiates `AIDriftEngine` and `DatasetRecorder` in `VRServer`, samples some adjusted tracker values on the server loop, and adds a React page that separately records rotations from the data feed. The server recorder accumulates the complete compressed stream in a `ByteArrayOutputStream`; neither recorder receives reset events; neither format contains angular velocity, drift state, validity masks, coordinate/units metadata, or an immutable tracker roster. The UI never invokes the server recorder. `AIDriftEngine` initializes an ONNX environment but never creates or runs a model session, while `TrackerResetsHandler` applies a heuristic and introduces a dependency on the global `VRServer.instance`. This breaks reset unit tests when no server singleton exists.

AMASS provides articulated human motion represented through SMPL-family body models. It is useful for learning which combinations of body-segment motion are anatomically plausible, but it contains neither SlimeVR sensor noise nor real tracker drift, mounting offsets, resets, connection jitter, or player behavior. It therefore supplies a synthetic kinematic prior, not real-device drift ground truth. Real play sessions provide domain data; reset operations provide sparse supervision only when the exact per-tracker correction transform and surrounding samples are captured.

The system must work with variable tracker layouts, tolerate unavailable raw channels on older firmware, preserve current tracking when AI fails, and avoid visible VR performance loss. Dataset collection must be trustworthy before large-scale recording begins because an ambiguous schema or missing reset labels cannot be repaired after collection.

## Goals / Non-Goals

**Goals:**

- Establish a single versioned dataset contract and a conformance gate before accepting real sessions.
- Capture synchronized, bounded-memory, crash-recoverable 50 Hz data from the authoritative server state.
- Preserve all available training-relevant sensor, environment, network, power, calibration, reference and correction-pipeline signals at declared native cadence with explicit missingness/provenance.
- Produce auditable sparse labels from yaw/full/mounting resets without treating HMD pose as full-body ground truth.
- Combine AMASS-derived synthetic motion with real reset-labelled sessions in a reproducible training/evaluation pipeline.
- Support 5, 6, 8, 10, and other tracker layouts through explicit body-role IDs, slot mappings, masks, and model metadata.
- Run a lightweight causal ONNX model through a verified execution provider with bounded corrections, confidence gating, and fail-open behavior.
- Expose the real recorder and inference engine through typed SolarXR RPC and a truthful React UI.
- Let a non-expert safely fine-tune, evaluate, export and activate a compact personal adapter from multiple local sessions inside the AI Drift workflow.
- Define functional, numerical, safety, and performance gates for dataset-ready and inference-ready milestones.

**Non-Goals:**

- Shipping AMASS or SMPL assets, whose licenses require users to obtain them separately.
- Claiming that HMD-only sessions provide dense ground-truth orientation for every body segment.
- Replacing mounting calibration, body proportions, skeleton solving, or all existing tracker filtering.
- Training a production model inside the Kotlin server or silently uploading user sessions.
- Re-training the complete global human-motion backbone in the default personal-training workflow.
- Guaranteeing CUDA, TensorRT, or DirectML on hardware/driver combinations where the corresponding native provider cannot load.
- Applying unconstrained roll/pitch corrections from sparse yaw-only labels.

## Decisions

### 1. One server-owned canonical session format

The server is the only recorder. The GUI sends commands and displays status; it does not sample WebSocket state or construct archives. Each session is written to a unique working directory as `manifest.partial.json` plus `telemetry.fbs.zst.partial`, then finalized into a ZIP-compatible `.nvrdata` archive containing `manifest.json` and `telemetry.fbs.zst`. A FlatBuffers dataset schema defines file header, roster, frame batches, event batches, and footer. Zstandard level 3 compresses the telemetry stream.

This is chosen over the current ad hoc FP16 byte layout because generated readers can verify schema evolution, optional channels, and bounds. It is chosen over browser recording because the server has raw tracker state, reset transforms, stable timing, and device metadata. ZIP remains only the outer container and does not recompress the already-zstd payload.

### 2. Monotonic snapshots, asynchronous disk I/O, and explicit loss accounting

A 50 Hz monotonic sampler takes immutable snapshots on the server thread. It uses elapsed monotonic nanoseconds rather than wall-clock deltas; UTC appears only in session metadata. Snapshots enter a bounded single-producer/single-consumer queue backed by a reusable direct `ByteBuffer` pool. A dedicated writer owns FlatBuffer encoding, zstd, and `FileChannel` I/O. Queue overflow increments a dropped-frame counter and writes a gap event instead of blocking tracking. Periodic frame batches, zstd flushes, checksums, and an atomic final rename permit recovery or explicit quarantine of interrupted sessions.

This satisfies the intended NIO/ring-buffer architecture while keeping all mutable tracker access on the server thread. The fixed `ByteBuffer(1024)`, `ByteArrayOutputStream`, and whole-session byte copy are removed.

### 3. Raw, adjusted, derived, and validity data remain distinguishable

Each frame contains HMD pose and tracking validity plus a fixed session roster of tracker samples. Tracker samples include raw IMU quaternion, calibrated/reference-adjusted quaternion before AI correction, final output quaternion, linear acceleration, angular velocity, current AI/legacy correction state, status, sample age, sequence number when available, and per-channel validity/provenance bits. Angular velocity is recorded from the device when available; otherwise it is quaternion-derived with `DERIVED` provenance and bounded-delta validation. Estimated drift is never represented as measured truth; it carries `LEGACY_ESTIMATE`, `RESET_DERIVED`, `MODEL_ESTIMATE`, or `UNAVAILABLE` provenance.

FP16 is used only for bounded vector/quaternion feature fields after finite/range checks. Timestamps, identifiers, flags, counters, and reset transforms use integer or FP32 fields where label precision matters. Coordinate handedness, quaternion order, units, gravity handling, and transform direction are declared in the schema and manifest.

### 3a. Extensible full-fidelity telemetry dictionary

"All data" is defined as every signal that is available or can be added without compromising tracking, not as a brittle fixed list. The schema has a versioned channel registry with unit, frame, cadence, source, precision, validity, and provenance for each field. Unknown optional channels can be skipped by older readers. Required features are stored per 50 Hz canonical frame; high-rate raw samples can be stored in timestamped sub-frame batches; slow values are stored on change or at their native cadence and joined by time during preprocessing.

The initial channel registry covers:

- Sensor timing and identity: firmware sensor timestamp/tick, server receive monotonic timestamp, canonical frame timestamp, packet/sample sequence, device uptime/boot epoch, sensor ID, stable session ID, IMU/board/MCU/firmware, body role, sensor-to-body mounting transform, coordinate convention, configured and observed sample rates.
- Kinematics: raw and fused/calibrated quaternion, pre-filter and post-filter orientation where available, raw accelerometer including gravity, linear/world acceleration, raw gyroscope in rad/s, quaternion-derived angular velocity, raw magnetometer vector, optional position/velocity, saturation/clipping flags, fusion mode/status, and data age.
- Environment and calibration: IMU/die temperature, magnetometer enabled/status/accuracy, per-axis or vendor calibration quality when available, rest/mounting/full/yaw calibration state, gravity estimate, temperature reference/baseline, and calibration or sensor error codes.
- Link quality and delivery: transport/origin, packet sent/received/lost counters, instantaneous and windowed loss, sequence gaps, duplicates, out-of-order/corrupt packets, reconnects, RSSI/link quality, ping/RTT, inter-arrival delta, jitter, estimated one-way sample age when clocks permit it, and receiver/server queue drops.
- Power and device state: battery voltage/percentage/runtime estimate, charging/external-power state when available, sleep/low-power mode, reset reason, firmware feature flags, sensor status, disconnect reason, and thermal/power throttling flags.
- Reference and body context: HMD and controller raw/final pose plus validity/age, tracking origin/recenter events, floor estimate, skeleton bone orientations/positions, body proportions, locomotion state, and automatically derived posture/activity tags with confidence for standing, seated, lying, crouching, transitions, dance/high-dynamics, locomotion, and unknown.
- Correction pipeline: raw tracker input, mounting/full/yaw transform state, legacy drift estimate/correction, normalized model input/schema, model ID/hash/version/provider, slot mapping/history validity, predicted correction/confidence/drift rate, gated/rejected reason, actually applied correction, final output, and inference sequence/latency.
- Events and operator context: tracker assignment/topology/calibration/reset, AI enable/disable/model switch, recording pause/resume, game/session marker, optional user activity tags, manual override, and validator/writer gaps.

No unavailable value is replaced with zero as though measured. Every optional channel carries `MEASURED`, `FIRMWARE_REPORTED`, `SERVER_DERIVED`, `MODEL_DERIVED`, `USER_ANNOTATED`, or `UNAVAILABLE` provenance plus validity. Firmware/protocol changes add raw gyro, device timestamp/sequence, packet counters, temperature, calibration accuracy, charging/power mode, and other missing signals where hardware supports them. Dataset profiles declare a minimum required subset and a preferred full-fidelity subset so legacy trackers can still contribute without contaminating feature semantics.

### 4. Reset transforms are captured as sparse labels

Reset handling emits a typed event from the reset processor rather than calling the recorder through `VRServer.instance`. For every affected physical IMU tracker it records the reset type/source, requested and applied monotonic timestamps, body part, raw quaternion, adjustment chain immediately before and after reset, reference HMD quaternion and validity, and the canonical correction quaternion that maps the pre-reset adjusted orientation into the post-reset adjusted orientation. A short configurable pre/post frame window is linked by frame index, and label flags describe partial resets, moving pose, stale HMD, tracker disconnects, or other quality issues.

The target convention is explicit: `q_target = normalize(q_post_adjusted * inverse(q_pre_adjusted))`, represented in the same world-frame composition order used by inference. Yaw reset labels supervise yaw only; full reset labels may supervise the axes actually changed; mounting resets form a separate task/domain and are not silently mixed with drift correction.

### 5. Immutable roster metadata and privacy boundary

The roster is snapshotted at session start and changes are represented by events. Each tracker entry contains a session-random stable ID, device-local tracker number, body role, concrete `IMUType`, `DeviceOrigin`/transport, board/MCU type where known, firmware, manufacturer, calibration capabilities, and channel availability. MAC/IP/hardware identifiers are hashed with a per-session salt or omitted by default. User consent, optional subject pseudonym, application version/commit, platform, schema version, and collection settings are recorded. Raw personally identifying device strings are never required for training.

### 6. Training uses two domains with leakage-safe evaluation

A separate `ml/` Python project provides commands for `prepare-amass`, `prepare-sessions`, `train`, `evaluate`, `export-onnx`, and `validate-onnx`. AMASS is converted through an explicit SMPL model path into canonical joint/segment transforms. Virtual tracker placements and sensor frames generate layouts; configurable biases, random walks, temperature-like drift, packet loss, latency, reset timing, mounting offsets, and sensor noise create synthetic observations. Real sessions contribute self-supervised temporal consistency data and reset-derived sparse labels. Loss masks ensure labels supervise only valid axes/times.

Train/validation/test splits are grouped by AMASS subject/source and by real subject/session/device cohort. Normalization statistics are computed only on the training split. Every run stores config, seed, Git commit, input dataset hashes, metrics, and artifact hashes. Dataset adapters reject unknown major schema versions and report quality statistics before training.

### 7. A causal set-aware compact model contract

The default architecture is a compact shared per-tracker causal temporal encoder (depthwise-separable TCN or similarly exportable causal block) plus masked global context pooling and a small shared per-tracker correction head. Inputs have shape `[batch, time, slots, features]` with explicit slot mask, body-role ID, channel-validity mask, and time deltas. Outputs are per-slot bounded rotation-vector/yaw correction, confidence, and optional drift-rate estimate. Model metadata declares feature schema/hash, normalization, maximum slots, supported roles, context range, output convention, training dataset hashes, opset, quantization, and performance tier.

Shared weights and masks support variable layouts without hard-coded 5/6/8/10 presets. Separate small/balanced artifacts are allowed. The initial production target is yaw correction; additional axes require independently validated labels. Dynamic time length is allowed within declared bounds; slot count is padded to the model maximum.

### 8. ONNX sessions are validated before activation

Model selection is a two-phase load: copy/download to a managed directory using a temporary file, verify size and SHA-256, parse sidecar metadata, create an ONNX Runtime session with the requested provider, inspect tensor names/types/shapes, run deterministic warm-up/probe inputs, and atomically swap the active session only after success. A failed load leaves the previous model active.

Provider availability is determined by successfully creating and running a model session, not by whether `addCUDA`, `addTensorrt`, or `addDirectML` accepted an option. AUTO tries TensorRT, CUDA, DirectML on supported Windows installations, then CPU according to packaged native binaries. Explicit provider selection fails visibly instead of silently lying about the active provider. Native artifacts are packaged per platform and verified in distribution smoke tests.

### 9. Inference is off the high-frequency tracking path and fail-open

At 50 Hz, immutable feature snapshots enter a latest-value inference worker. It maintains history by stable tracker/body-role mapping, resets history on roster/calibration discontinuities, runs the session, validates finite outputs, gates by confidence and channel validity, clamps angular magnitude/rate, and smooths correction state. The server thread consumes only the latest valid correction; stale, slow, invalid, or failed inference returns identity and preserves normal tracking.

AI correction is inserted as an explicit drift-correction stage after mounting/full/yaw calibration transforms and before final output. Legacy drift compensation is either disabled for an AI-controlled tracker or composed according to a configured, tested mode; it is never accidentally applied twice. Lifecycle ownership is dependency-injected so tracker/reset unit tests do not require a global server singleton.

Performance gates are measured on reference weak hardware and CPU fallback. The small model target is at most 15 MiB on disk, 128 MiB incremental GPU memory, p95 inference at most 1 ms on the reference entry GPU and 4 ms on reference CPU for 10 slots and a 60-frame context, with no blocking operation on the server tick and no sustained queue growth. Gates are configurable only by changing benchmark policy, not model metadata claims.

### 10. Typed control plane and truthful UI

New FlatBuffers RPC messages cover start/stop/cancel/recover/list/delete/reveal session operations; recorder status and validation errors; local model import and catalog download; model load/unload; provider/history/confidence/slot settings; runtime status and metrics. The GUI subscribes to server state through existing protocol hooks. Electron file dialogs expose only user-selected paths or enumerated application folders, and downloads use a host allowlist plus hashes from the catalog.

UI controls never infer the active provider from WebGL renderer strings and never show a selected browser `File` as loaded. A model is loaded only after server acknowledgement. All strings use Fluent localization. The recorder refuses to start when required readiness checks fail and shows actionable causes.

### 11. Two explicit readiness gates

`dataset-ready` requires schema/reader round-trip tests, FP16 numerical tests, reset-label transform tests, a forced crash/recovery test, bounded-memory soak, mixed Wi-Fi/HID roster metadata tests, RPC/UI end-to-end start/stop/export, and a validator report with zero fatal errors. A short pilot dataset must successfully pass the Python loader and produce reset windows before broad collection begins.

`inference-ready` additionally requires ONNX parity against the training framework, provider/package smoke tests, failure/watchdog tests, layout-mask tests, correction safety tests, and latency/memory benchmarks. Passing dataset-ready does not imply that a production correction model is trained.

### 12. Closed-loop collection avoids model self-training

Players may record while correction is enabled, which is essential for learning long-term behavior with fewer resets. The recorder therefore stores immutable raw and pre-AI observations, the model prediction, gating decision, applied correction, and final output separately. Training targets never use final corrected output as sensor truth. Model hash, normalization/schema, configuration, tracker mapping, confidence, and correction epoch make every action reproducible.

Real sessions provide four kinds of evidence: sparse correction labels from rare yaw/full resets; clean no-reset segments that penalize unnecessary correction; cross-tracker/HMD/skeleton consistency objectives; and global-model teacher/pseudo-labels that are explicitly marked and never mixed with human reset ground truth. Manual resets after AI correction are high-value override labels. Evaluation can replay the raw stream offline with AI disabled or with a candidate model, enabling counterfactual comparison without requiring the player to repeat the session.

Activity/posture labels are primarily derived from HMD height/orientation, skeleton pose, velocity, contact/locomotion features, and motion intensity, with confidence and `UNKNOWN` fallback. Optional user markers improve evaluation but are not required. Promotion requires coverage and non-regression for standing, seated, lying, crouching, transitions, dance/high-dynamics, and long stationary periods; aggregate metrics alone cannot hide a failed posture cohort.

### 13. Personalization fine-tunes an adapter, not human anatomy from scratch

The user chooses a validated pre-trained NekoVR global base model. AMASS is never shown as a runnable model: it was one source used offline to teach the global backbone human kinematics. The base artifact includes frozen-backbone training/eval/optimizer/checkpoint artifacts for a small personal adapter or conditioning head. Personal training updates only that bounded parameter set by default, preserving the global human-motion prior, reducing compute/memory, and limiting overfit. Advanced full-model fine-tuning remains an unsupported research workflow until separately validated.

Personal inputs are selected `.nvrdata` sessions plus an optional existing personal checkpoint. Eligibility checks require compatible schema/model features, stable body-role assignments, adequate valid hours and reset/no-reset evidence, and activity/layout coverage. Hours alone are not sufficient. Sessions are split by whole session and time into train/validation/test, with the most recent suitable session held out where possible. The trainer samples balanced windows rather than loading 45-72 hours into memory.

A signed optional `NekoVR Trainer` worker runs out of process and communicates through typed local IPC. The preferred production implementation uses pre-generated ONNX Runtime Training artifacts and the native Training API, then exports an inference-ready ONNX model. CPU adapter training is the universal baseline; CUDA acceleration is optional when a verified training provider is packaged. DirectML inference availability does not imply DirectML training support. The worker is downloaded/installed only with consent, hash verification, and licenses, keeping the base application small.

The server remains usable while preprocessing/training, but training defaults paused during active VR tracking or runs under explicit CPU/GPU/memory/I/O budgets. Jobs are deterministic, checkpointed, resumable after restart, cancellable, and isolated so failure cannot stop tracking. Training output includes the base model hash, personal profile ID, compatible roles/layouts/sensor families, dataset hashes, metrics, and adapter/full artifact hashes.

### 14. Three-tab AI Drift workflow and recent-model access

The AI Drift page uses compact top-level tabs rather than nested cards:

1. `Correction`: model selector with pinned/recent compatible models, enable toggle, provider/health, tracker-role mapping, per-tracker confidence/correction status, and advanced safety settings.
2. `Datasets`: readiness, recording controls, live quality/telemetry coverage, session inventory, validation, activity/layout coverage, export/reveal, and recovery.
3. `Personal Training`: base model, personal profile, eligible multi-select session table, coverage/readiness summary, training preset, resource budget, staged progress, pause/resume/cancel, validation comparison, and export/activate result.

The default personalization path is: select the recommended compatible base; select a personal profile; accept recommended eligible sessions; choose `Quick`, `Balanced`, or `Thorough`; start; review a concise held-out comparison; activate only if gates pass. Progress is driven by worker events with stages for validation, indexing, preprocessing/cache, split, training epochs, evaluation, ONNX export, runtime parity, and final validation. It shows percent, ETA, current/best validation loss, activity/layout coverage, checkpoint state, device/resource use, and actionable errors without exposing raw ML controls by default.

Recent model history is server-owned and hash-based, not a browser filename list. It stores last-used time, pin state, base/personal profile, compatibility and validation status. Switching models is transactional, validates the current tracker/body mapping, resets inference history, and can immediately roll back to the previous active model. A newly trained model first runs a short shadow validation; automatic activation is optional and rollback remains one action.

## Risks / Trade-offs

- [Reset actions are sparse and user-triggered, so labels are biased toward noticeable drift] -> retain synthetic AMASS corruption, collect hard negatives and no-reset sessions, record label quality/context, and evaluate by session/subject cohorts.
- [HMD is not full-body ground truth] -> treat it as heading/world reference and a model feature, never as dense per-tracker target; optionally support lighthouse/mocap reference trackers later through the same validity schema.
- [Derived angular velocity differs from raw gyroscope data] -> store provenance and masks, train with matching corruption/ablation, and add firmware channels later without changing semantic meaning.
- [FP16 can hide small drift signals] -> keep reset corrections and time deltas at higher precision, measure quantization error, and reject feature ranges outside declared bounds.
- [Variable tracker layouts complicate batching and model quality] -> use stable body-role IDs, masks, layout augmentation, per-layout metrics, and a declared maximum with explicit unsupported-layout errors.
- [Native provider packaging increases distribution size and platform complexity] -> use platform-specific packages, provider probes, signed catalogs/hashes, and CPU fallback; do not bundle unused providers into every target.
- [Asynchronous inference can apply stale corrections] -> attach sequence/timestamp, enforce maximum age, consume latest-only output, and return identity on discontinuity.
- [Correction can fight legacy compensation or user resets] -> define one correction stage, clear/rebase AI history at reset, and test each legacy composition mode.
- [Interrupted archives can consume disk or be mistaken for valid data] -> use partial suffixes, periodic durable metadata, startup recovery/quarantine, free-space thresholds, and checksums.
- [Current unrelated Electron changes do not compile and weaken URL/file IPC constraints] -> restore typed IPC contracts and allowlists before wiring model/session file operations; include the full GUI lint/build gate.
- [More telemetry can increase firmware bandwidth, disk use, and model shortcut learning] -> use native-rate/change streams, compression, collection profiles, per-channel ablations, provenance, and feature promotion tests rather than feeding every recorded field blindly into the model.
- [Temperature and link quality correlate with specific hardware/users and can become spurious shortcuts] -> normalize per device/session, retain hardware-stratified holdouts, run ablations, and include a feature only when it improves unseen-device cohorts.
- [Training on outputs produced by the active model can amplify its mistakes] -> preserve raw/pre-AI streams, label model-derived values, replay candidates offline, prioritize human/external labels, and require clean-segment false-correction gates.
- [Personal training may overfit one posture, session, tracker set, or reset habit] -> freeze the global backbone, train a small adapter, use whole-session holdouts and activity/layout coverage, and fall back to the base model on incompatible topology or failed validation.
- [Local training can interfere with VR or make the base distribution very large] -> use an optional signed worker, bounded adapter training, checkpoint/pause, explicit resource budgets, and default pause during active tracking.

## Migration Plan

1. Restore the existing server reset tests and GUI typecheck/lint baseline; remove the global singleton access from tracker correction and mark prototype AI/recorder UI unavailable.
2. Land the canonical FlatBuffers dataset schema, generated bindings, writer/reader/validator, roster metadata mapping, and conformance tests behind a developer feature flag.
3. Integrate reset events and authoritative 50 Hz sampling; run bounded-memory/crash tests and generate pilot archives from simulated and real mixed-transport trackers.
4. Add recorder RPC/status and replace the browser recorder. Keep prototype archives read-only and label them unsupported; do not silently convert files whose missing fields cannot be reconstructed.
5. Build the Python dataset/AMASS pipeline, validate pilot archives, establish grouped splits and baseline metrics, then declare dataset-ready and begin broader collection.
6. Implement the model contract and baseline training/export/parity tests. Store models in a managed directory with metadata and hashes.
7. Implement provider packaging, validated session activation, inference worker, correction safety logic, RPC settings, and UI model management behind an opt-in feature flag.
8. Pass inference-ready benchmarks and staged dogfood with correction disabled, shadow mode, then bounded active mode. Rollback disables the feature flag and returns identity corrections without altering existing reset/calibration data.
9. Generate frozen-backbone personalization artifacts for promoted base models, package the optional trainer worker, and validate CPU plus supported CUDA training paths.
10. Add the Personal Training tab and recent/pinned model workflow; pilot personal adapters on multi-session profiles, require held-out posture/layout gates, then enable safe activation and rollback.

## Open Questions

- Which exact weak CPU/GPU models define the release benchmark matrix, and which platforms must ship DirectML in the first release?
- Will future firmware expose raw gyroscope samples and sensor timestamps for all transports, or must derived angular velocity remain the baseline contract?
- Which AMASS subsets and SMPL-family body model licenses are acceptable for redistribution of trained weights?
- Should real-session collection support optional lighthouse/mocap reference trackers in the first schema version or reserve their typed reference channel for a later minor version?
- What privacy/consent text and retention policy apply if community datasets are uploaded outside the local machine?
- Which firmware families can expose raw gyro, device timestamps/sequences, charging state, reset reason, and calibration accuracy without reducing pose packet rate?
- What minimum valid hours, reset-label count, activity coverage, and held-out session count qualify a profile for Quick, Balanced, and Thorough personalization?
- Which desktop platforms receive prebuilt ONNX Runtime Training workers in the first release, and is CUDA acceleration required initially or is bounded CPU adapter training sufficient?
