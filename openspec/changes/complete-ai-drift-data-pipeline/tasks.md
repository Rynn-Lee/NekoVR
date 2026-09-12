## 1. Restore a Green Baseline

- [x] 1.1 Remove the `VRServer.instance` dependency from `TrackerResetsHandler` and make disabled/unavailable AI correction return identity through an injected interface.
- [x] 1.2 Run and fix all `server/core` reset, mounting, reference adjustment, skeleton reset, and tracking pause tests.
- [x] 1.3 Reconcile the current Electron IPC edits with `shared.ts`/preload contracts and restore URL/path allowlists.
- [x] 1.4 Fix current AI Drift, dataset widget, hardware detection, ZIP utility, Typography, and Button TypeScript errors.
- [x] 1.5 Make `pnpm -C gui lint`, production GUI build, and `:server:core:test` required baseline checks.
- [x] 1.6 Hide or clearly mark the prototype recorder/model controls unavailable until the corresponding readiness gate passes.

## 2. Define the Canonical Dataset Contract

- [x] 2.1 Add a versioned FlatBuffers dataset schema for file headers, tracker roster, frame batches, events, reset labels, quality counters, and footer.
- [x] 2.2 Document coordinate frames, quaternion order/composition, handedness, units, gravity handling, timestamp units, validity/provenance flags, and numeric ranges.
- [x] 2.3 Generate and wire Kotlin and Python bindings/readers for the dataset schema.
- [x] 2.4 Implement finite/range-checked FP16 encoding and decoding with exhaustive normal, subnormal, infinity, NaN, and error-tolerance tests.
- [x] 2.5 Add cross-language Kotlin-writer/Python-reader conformance fixtures and transform round-trip tests.
- [x] 2.6 Add canonical `.nvrdata` manifest models, schema compatibility checks, telemetry checksums, and archive validator output.
- [x] 2.7 Add explicit detection and unsupported/missing-field reporting for both prototype archive formats.
- [x] 2.8 Define the extensible telemetry channel registry with units, frames, cadence, precision, validity and measured/firmware/server/model/user/unavailable provenance.
- [x] 2.9 Define minimum, standard and full-fidelity collection profiles and compatibility rules for legacy trackers with missing optional channels.

## 3. Implement Streaming Session Recording

- [x] 3.1 Replace the current `DatasetRecorder` and in-memory compressor with a lifecycle-managed server recording service and typed state machine.
- [x] 3.2 Implement the 50 Hz monotonic server-thread snapshot sampler with actual deltas, sequence/sample-age data, and immutable frame objects.
- [x] 3.3 Capture HMD validity/pose and per-tracker raw, calibrated pre-AI, final orientation, acceleration, angular velocity, correction state, status, and masks.
- [x] 3.4 Implement measured-versus-derived angular velocity and drift/correction provenance without labelling estimates as ground truth.
- [x] 3.5 Implement stable per-session tracker IDs, topology/assignment/calibration events, and immutable roster snapshots.
- [x] 3.6 Map every tracker to real `IMUType`, `DeviceOrigin`/transport, board/MCU, firmware, manufacturer, channel capability, body role, and calibration metadata.
- [x] 3.7 Omit or per-session hash MAC/IP/hardware identifiers and add consent/subject pseudonym/session privacy metadata.
- [x] 3.8 Implement the bounded reusable direct-buffer queue, FlatBuffer batching, zstd level 3 writer thread, and non-blocking drop/gap accounting.
- [x] 3.9 Implement partial files, periodic durable progress/checksums, disk/free-space limits, atomic finalization, startup recovery, and quarantine.
- [x] 3.10 Add short, long-soak, queue-overflow, disk-full, forced-crash, recovery, and mixed Wi-Fi/HID/nRF-origin recorder tests.
- [x] 3.11 Record temperature, magnetic/calibration/fusion state, packet sequence/loss/gap/reorder/duplicate/corrupt counters, RSSI, ping/jitter/sample age, battery/charging/power/sleep, uptime/reset reason and firmware features at native cadence.
- [x] 3.12 Extend firmware and Wi-Fi/HID/nRF protocol capability negotiation for raw gyro, device timestamps/sequences, packet counters, calibration quality and power/environment channels supported by hardware.
- [x] 3.13 Record HMD/controller/skeleton/body/floor context and derive confidence-bearing standing/seated/lying/crouching/transition/dance/locomotion/stationary/unknown activity intervals.
- [x] 3.14 Record raw/pre-AI/model prediction/gating/applied correction/final output/model hash/provider/slot/history/latency channels separately during corrected sessions.
- [x] 3.15 Add offline raw-stream replay and counterfactual candidate evaluation fixtures for sessions recorded with AI enabled.

## 4. Capture Reset-Derived Supervision

- [x] 4.1 Introduce an injected typed reset event publisher for request, cancellation/failure, and actual application timing/source/body parts.
- [x] 4.2 Snapshot each affected IMU's raw orientation and complete adjustment state immediately before and after yaw/full/mounting reset.
- [x] 4.3 Derive normalized per-tracker correction quaternions and diagnostic yaw using the canonical composition convention and axis masks.
- [x] 4.4 Link configurable pre/post frame windows and mark truncated windows at session boundaries.
- [x] 4.5 Compute label-quality flags for HMD validity, motion, packet gaps, reconnects, reassignment, invalid values, overlap, and insufficient context.
- [x] 4.6 Keep yaw, full, and mounting targets separate and expose deterministic training include/downweight/exclude policy.
- [x] 4.7 Increment reset/calibration epochs and clear or rebase legacy drift, AI history, pending results, and applied correction for affected trackers.
- [x] 4.8 Add wraparound, quaternion-sign, known-transform, partial, multi-tracker, delayed, invalid-reference, and mounting reset label fixtures.
## 5. Add Recorder RPC and UI

- [x] 5.1 Extend SolarXR FlatBuffers RPC with recorder commands, authoritative status/events, readiness findings, inventory, validation, recovery, export/reveal, and typed errors.
- [x] 5.2 Regenerate Java/TypeScript protocol bindings and implement server RPC handlers with request/session IDs and access-safe managed roots.
- [x] 5.3 Replace browser interval sampling, `localStorage` inventory, `zipBuilder`, and reconstructed empty downloads with server RPC hooks/state.
- [x] 5.4 Implement recorder readiness, live queue/write/drop/reset/disk metrics, roster, finalization progress, validation errors, and actual archive inventory in the AI Drift page.
- [x] 5.5 Implement safe reveal/export/delete/recover operations through typed Electron IPC and resolved path containment.
- [x] 5.6 Add English/Russian Fluent strings and accessible existing-design-system controls for all recorder states and errors.
- [x] 5.7 Add RPC state-machine, reconnect, multi-client, and GUI end-to-end start/stop/finalize/reveal tests.

## 6. Establish the Dataset-Ready Gate

- [x] 6.1 Add a single validation command/report aggregating schema, numerical, metadata, reset-label, crash-recovery, memory-soak, RPC/UI, and baseline results.
- [ ] 6.2 Record simulated and real short pilot sessions with multiple tracker layouts/transports and validate zero fatal findings.
- [x] 6.3 Load pilot archives through the Python reader, enumerate valid reset windows, and verify roster/channel/quality statistics against the server report.
- [x] 6.4 Persist and expose the build's dataset-ready result; keep production-profile recording disabled until all required evidence passes.
- [x] 6.5 Document the canonical collection protocol, consent/privacy rules, reset behavior, session acceptance criteria, and unsupported prototype files.

## 7. Build the AMASS and Real-Session ML Pipeline

- [x] 7.1 Create a pinned `ml/` Python project with CLI entry points, deterministic configuration, tests, and documented licensed AMASS/SMPL asset setup.
- [x] 7.2 Implement AMASS/SMPL conversion to canonical timed body-segment transforms and validate known-pose coordinate fixtures.
- [x] 7.3 Implement virtual sensor placements and configurable 5/6/8/10/additional body-role layout generation with masks.
- [x] 7.4 Implement reproducible mounting, noise, bias/random-walk/temperature-like drift, latency, packet-loss, stale-channel, and reset simulation.
- [x] 7.5 Implement real `.nvrdata` validation/loading, reset-window extraction, provenance masks, quality reports, and source hashes.
- [x] 7.6 Implement leakage-safe grouped train/validation/test splitting and training-only normalization with split audits.
- [x] 7.7 Implement dense synthetic, sparse real reset, temporal/self-supervised, domain, axis, and quality-weighted loss masks.
- [x] 7.8 Implement the compact shared causal temporal encoder, masked global context, per-slot bounded correction/confidence heads, and variable-layout batching.
- [x] 7.9 Add per-layout/domain/person/chipset/activity/drift evaluation for before/after error, confidence calibration, clean-motion false correction, jitter, magnitude/rate, discontinuity, resets/hour, time-to-first-reset and longest valid no-reset interval.
- [x] 7.10 Save run seed/config/environment/commit, split and dataset hashes, normalization, metrics, and artifact provenance for every run.
- [x] 7.11 Add temperature/network/power/hardware feature normalization, missingness augmentation, per-feature ablations, and unseen-device/session shortcut checks.
- [x] 7.12 Add explicit standing/seated/lying/crouching/transition/locomotion/dance/stationary cohort coverage and non-regression promotion gates.
- [x] 7.13 Generate frozen-backbone adapter training/eval/optimizer/nominal-checkpoint artifacts for every personalization-ready base model.

Re-audited from the connected CLI and deterministic end-to-end evidence in
`../fix-ai-drift-foundation-compatibility/reaudit-7.1-7.13.md`.

## 8. Export and Validate ONNX Models

- [x] 8.1 Define and version model sidecar metadata for feature-schema hash, tensors, normalization, roles/slot/context bounds, output convention, provenance, metrics, opset, size, and SHA-256.
- [x] 8.2 Export the small causal model with supported ONNX operators and dynamic bounded time/slot masks.
- [x] 8.3 Add framework-versus-ONNX Runtime parity tests across 5/6/8/10 layouts, slot permutations, masks, discontinuities, and invalid channels.
- [x] 8.4 Enforce the small-model 15 MiB size and safety/quality promotion gates before catalog publication.
- [x] 8.5 Produce a deterministic tiny probe model/fixture for provider, packaging, and server integration tests.

Re-audited from hash-bound atomic publication evidence and authenticated packaged-provider
probes in `../fix-ai-drift-foundation-compatibility/reaudit-8.1-8.5.md`.

## 9. Implement Real ONNX Runtime Inference

- [x] 9.1 Replace heuristic-only `AIDriftEngine` behavior with model/metadata hash validation, tensor contract inspection, session creation, warm-up/probe, and atomic activation.
- [x] 9.2 Package platform-specific CPU/CUDA/TensorRT/DirectML native runtimes as supported and verify providers by successful session execution.
- [x] 9.3 Implement AUTO and forced provider behavior with truthful diagnostics and no silent forced-provider fallback.
- [x] 9.4 Implement the bounded latest-value inference worker, stable slot/body-role mappings, normalized causal histories, masks, epochs, and resource lifecycle.
- [x] 9.5 Implement per-tracker finite/fresh/confidence gating, magnitude/rate/acceleration bounds, smoothing, stale decay, watchdog, and identity fail-open behavior.
- [x] 9.6 Insert one explicit AI correction stage after calibration transforms and implement tested replace/compose policy for legacy drift compensation.
- [x] 9.7 Expose model/provider/latency/queue/drop/error/outlier/confidence/mapping runtime metrics and typed health state.
- [x] 9.8 Add unit/integration tests for load failure rollback, reload/unload, provider failure, stale/late epochs, mappings, mixed confidence, outliers, watchdog, and normal tracking fallback.

## 10. Add Model Control Plane and UI

- [x] 10.1 Extend FlatBuffers RPC with model import/catalog/download/load/unload, provider/config/mapping commands, runtime status, progress, and typed errors.
- [x] 10.2 Implement managed local model storage with temporary copies, path containment, metadata/size/SHA-256 verification, and atomic imports.
- [x] 10.3 Replace hard-coded model entries with a versioned HTTPS allowlisted JSON catalog parser and verified downloads reflecting actual local state.
- [x] 10.4 Persist versioned enabled/provider/context/confidence/safety/legacy-mode/mapping settings with AI disabled on unsafe prototype migration.
- [x] 10.5 Replace local React-only AI/model state with RPC hooks and server acknowledgements for actual active model/provider/configuration.
- [x] 10.6 Implement AUTO/provider, context, confidence, correction limits, per-tracker slot/body-role mapping, enable/disable, unload, catalog, progress, and error controls.
- [x] 10.7 Remove WebGL GPU detection as execution-provider evidence and display only server-probed compatibility/latency/runtime health.
- [x] 10.8 Add English/Russian Fluent strings, accessibility coverage, and control-plane UI integration tests.
- [x] 10.9 Implement server-owned hash-based recent/pinned global and personal model history with compatibility state and transactional quick switching.
- [x] 10.10 Refactor AI Drift into compact top-level Correction, Datasets and Personal Training tabs with shared authoritative hooks and no nested decorative cards.
- [x] 10.11 Keep correction and recording jobs running across tab navigation and application/client reconnects.
- [x] 10.12 Add one-action fallback/rollback to the previous compatible model after switch or shadow-validation failure.

## 11. Performance, Packaging, and Inference-Ready Gate

- [x] 11.1 Add benchmark harnesses for server tick blocking, p50/p95/p99 inference, queue stability, CPU/GPU utilization, memory, tracker count, and context size.
- [ ] 11.2 Select and document the reference weak CPU/GPU/driver matrix and record the 10-slot/60-frame small-model baseline.
- [x] 11.3 Enforce small-tier targets of at most 128 MiB incremental GPU memory, 1 ms p95 on reference entry GPU, and 4 ms p95 on reference CPU.
- [x] 11.4 Integrate generated protocol, React assets, server, ONNX native providers/licenses, model metadata, and startup paths into Gradle/Electron/jpackage distributions.
- [x] 11.5 Add installed-package offline CPU and available GPU-provider smoke tests for every supported target.
- [ ] 11.6 Run shadow-mode dogfood and verify correction safety, reset interaction, layout cohorts, no visible performance loss, and fail-open behavior.
- [x] 11.7 Add a single inference-ready report aggregating parity, provider/package, layout, watchdog, safety, quality, baseline regression, and performance evidence.
- [x] 11.8 Enable bounded active correction only behind an opt-in feature flag after inference-ready passes and retain instant rollback to identity correction.

## 12. Implement Local Personal-Model Training

- [x] 12.1 Define versioned personal profiles, model/job/checkpoint/cache metadata, compatible roles/layouts/sensor families and local privacy/provenance storage.
- [x] 12.2 Implement selected-session eligibility analysis for integrity, feature/base compatibility, valid hours/windows, resets, clean intervals, body assignment, sensor/layout and activity coverage.
- [x] 12.3 Implement leakage-safe whole-session/time personal train/validation/test splits with recent suitable holdout and deterministic balanced streaming windows.
- [x] 12.4 Build a signed out-of-process `NekoVR Trainer` worker over typed local IPC using ONNX Runtime Training artifacts and atomic resumable checkpoints.
- [x] 12.5 Implement CPU adapter-training baseline and optional CUDA training only after packaged provider probe; report DirectML inference and training support separately.
- [x] 12.6 Enforce frozen-backbone/adapter-only gradients, bounded trainable parameter count, early stopping and correction safety constraints.
- [x] 12.7 Implement Quick, Balanced and Thorough presets derived from usable data, hardware/resource probe and validated epoch/window/early-stop policies.
- [x] 12.8 Implement CPU thread, GPU memory/utilization, RAM, disk and I/O budgets plus automatic checkpoint/pause/throttle policy during active VR tracking.
- [x] 12.9 Implement trainer install/probe/profile/eligibility/job/pause/resume/cancel/status/evaluate/export/activate FlatBuffers RPC and regenerated bindings.
- [x] 12.10 Implement Personal Training tab base/profile/session selection, coverage findings, preset/resource controls and authoritative staged progress with ETA, metrics, resources and errors.
- [x] 12.11 Restore unfinished jobs and checkpoints after application restart and test worker crash isolation without interrupting active correction.
- [x] 12.12 Evaluate each personal model against its base on held-out reset replay, resets/hour, time-to-first-reset, clean false correction, jitter, correction bounds and every sufficiently represented activity/layout cohort.
- [x] 12.13 Export validated personal ONNX plus base/profile/session/split/checkpoint/metric hashes and run framework/training-runtime/inference-runtime parity checks.
- [x] 12.14 Add transactional optional shadow activation, inference-history reset, previous-model retention, topology compatibility fallback and one-action rollback.
- [x] 12.15 Add installed-package end-to-end tests from nine multi-hour session manifests through resumed training, validation, ONNX export, activation and rollback without loading all telemetry into memory.
