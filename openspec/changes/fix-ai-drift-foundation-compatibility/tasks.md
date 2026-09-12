## 1. Restore the Foundation Baseline

- [x] 1.1 Fix the current GUI Prettier failure and confirm `pnpm.cmd -C gui lint` propagates a non-zero exit code on an intentional formatting failure.
- [x] 1.2 Run the GUI unit suite, production build, and a forced `:server:core:test` execution; record the exact commands in the developer documentation.
- [x] 1.3 Add one platform-neutral aggregate baseline command that invokes GUI test/lint/build, server core tests, and dataset conformance without relying on shell wrappers that lose child exit codes.

## 2. Repair Python Zstandard Interoperability

- [x] 2.1 Replace one-shot Python Zstandard decompression with bounded streaming decompression that accepts frames with unknown content size.
- [x] 2.2 Translate Zstandard import, decode, truncation, and resource-limit failures into stable `DatasetFormatError` results.
- [x] 2.3 Make `validate_ready.py` catch all normalized dataset-reader failures and always write a failed machine-readable report instead of crashing.
- [x] 2.4 Add regression tests that read current Kotlin streaming output and reject truncated, corrupt, oversized, and multi-frame abuse cases.

## 3. Complete FP16 Safety and Tests

- [x] 3.1 Route Kotlin dataset feature decoding through channel-specific finite/range checks and quaternion normalization validation.
- [x] 3.2 Add equivalent checked binary16 feature decoding to the Python reader while retaining a raw codec helper for bit-pattern tests.
- [x] 3.3 Add exhaustive Kotlin tests over all 65,536 binary16 patterns, including class preservation and canonical NaN policy.
- [x] 3.4 Add Float32-to-binary16 boundary, round-to-nearest-even tie, subnormal, signed-zero, overflow, infinity, NaN, range, and documented error-tolerance tests.
- [x] 3.5 Add cross-language negative fixtures proving Kotlin and Python reject the same invalid FP16 features and quaternion norms.

## 4. Establish Full-Field Conformance

- [x] 4.1 Extend the Kotlin high-level reader to preserve native channels, correction state, activity, roster, channel descriptors, quality counters, and footer fields required by validation.
- [x] 4.2 Extend the Python high-level reader to preserve and validate the same required v1 fields instead of exposing only tracker orientations and aggregate counts.
- [x] 4.3 Add a deterministic Kotlin fixture generator covering every v1 table, representative optional fields, validity/provenance variants, non-trivial FP16 values, and reset transforms.
- [x] 4.4 Commit the conformance fixture or its reproducible source/hash and add a Python test that compares every expected decoded value within documented tolerances.
- [x] 4.5 Add a non-commuting quaternion fixture and verify the canonical `normalize(q_post * inverse(q_pre))` composition in both languages.

## 5. Strengthen Canonical Archive Validation

- [x] 5.1 Define and document the footer checksum byte scope so it is reproducible and non-self-referential, while retaining the manifest hash of the final compressed telemetry entry.
- [x] 5.2 Parse and validate the terminal footer in Kotlin and Python, including complete state, duration, counters, checksum, uniqueness, and agreement with manifest/decoded records.
- [x] 5.3 Validate exact canonical ZIP members and reject duplicate, absolute, traversal, unexpected, compressed-again, or oversized entries.
- [x] 5.4 Compare known channel descriptors by ID, name, unit, frame, cadence, precision, minimum profile, validity semantics, and allowed provenance in both validators.
- [x] 5.5 Preserve forward compatibility by accepting complete unknown optional descriptors while rejecting ID collisions and incompatible required semantics.
- [x] 5.6 Add positive and mutation-based negative tests for schema versions, record order, registry semantics, footer fields, checksums, and frame/quality counts.
- [x] 5.7 Retain structured detection tests for both historical prototype layouts and verify they cannot enter dataset-ready or training inputs.
- [x] 5.8 Add minimum, standard, full-fidelity, and legacy-missing-channel tests proving unavailable data is never serialized as a fabricated valid zero.

## 6. Make Readiness Controls Truthful

- [x] 6.1 Extend the server model runtime/status RPC with authoritative inference-ready state, active-model hash agreement, and an actionable typed blocking reason.
- [x] 6.2 Update generated SolarXR bindings and GUI state reducers/tests for the readiness fields without optimistic activation state.
- [x] 6.3 Disable and clearly label production model Enable/activate controls until inference readiness passes; distinguish persisted intent from effective runtime activation.
- [x] 6.4 Keep only explicitly labelled diagnostic model operations available before promotion and retain server-side rejection for forged active-correction requests.
- [x] 6.5 Verify dataset controls distinguish diagnostic recording from locked production profiles and display the authoritative dataset-ready reason.

## 7. Harden Electron Trust Boundaries

- [x] 7.1 Replace remaining literal IPC registrations with `IPC_CHANNELS` and add a compile-time or unit contract test covering main and preload channel signatures.
- [x] 7.2 Extract a canonical filesystem authorization helper using `realpath` and explicit target types for managed roots and existing targets.
- [x] 7.3 Add deterministic traversal, sibling-prefix, symlink, junction, missing-target, directory, and regular-file authorization tests that do not depend solely on privileged Windows symlink creation.
- [x] 7.4 Apply canonical authorization to generic open-file actions and keep dataset reveal/export source selection session-ID-based.
- [x] 7.5 Add deny-by-default `will-navigate` and `setWindowOpenHandler` policies and route approved external opens through the centralized URL allowlist.
- [x] 7.6 Add URL tests for scheme/host/path spoofing, encoded traversal, subdomain boundaries, approved links, and blocked renderer navigation.

## 8. Wire Required Verification and Re-audit

- [x] 8.1 Add explicit required CI jobs for GUI tests, GUI lint, production GUI build, forced server core tests, and Kotlin/Python dataset conformance independently of packaging jobs.
- [x] 8.2 Change dataset-ready evidence mapping so schema, numerical, metadata, reset-label, recovery, soak, RPC/UI, and baseline categories pass only from their direct checks.
- [x] 8.3 Generate the simulated 5-tracker UDP and 8-tracker mixed-transport archives and require both Kotlin and Python validators to agree on full decoded reports.
- [x] 8.4 Run the aggregate foundation baseline from a clean checkout and confirm all checks pass with no unexpected skip in security, FP16, or cross-language coverage.
- [x] 8.5 Re-audit the original `complete-ai-drift-data-pipeline` tasks 1.1–2.9 and update their completion state only where this corrective change supplies executable evidence.

## 9. Restore Streaming Recorder Fidelity

- [x] 9.1 Split collection-profile requirements into global/context and per-tracker capabilities; make minimum, standard, full-fidelity, and legacy profiles attainable and reject only genuinely missing required inputs.
- [x] 9.2 Replace ad hoc capability numbers with one canonical mapping, map UDP calibration quality to channel 22, advertise every supported packet-29 field, and wire HID/nRF capability/sample handling into the production dongle readers rather than test-only helpers.
- [x] 9.3 Freeze every sample-producing HMD, controller, physical tracker, and context identity into the initial roster; prohibit post-roster IDs and cover reconnect, replacement, assignment, calibration, and capability changes with stable topology events.
- [x] 9.4 Extend the append-only schema, bindings, snapshot factory, and readers to retain controller/HMD positions, skeleton bone orientations/positions, body and floor context, and confidence-bearing standing/seated/lying/crouching/transition/dance/locomotion/stationary/unknown intervals.
- [x] 9.5 Capture the declared native telemetry that is currently absent or incomplete, including configured/observed rate, inter-arrival timing/jitter, complete packet/error counters, calibration/fusion state, battery/power/sleep state, and reset reason, with correct units, cadence, validity, and provenance.
- [x] 9.6 Extend correction telemetry to include legacy correction/drift provenance and model input schema/version, hash/provider, slot/mapping, prediction, confidence, drift rate, gate/rejection, applied correction, history/epoch, inference sequence, latency, and final output as distinct fields.
- [x] 9.7 Make queue overflow acknowledge pending gap/reset events and completed labels only after successful enqueue/write; keep tracking non-blocking while bounding both sample and control metadata memory.
- [x] 9.8 Replace the 500-frame pseudo-soak with deterministic multi-hour simulated coverage that asserts heap/direct-buffer bounds and on-disk progress, and add actual transport-parser, overflow-with-reset, disk-failure, durable-crash/recovery, and full-fidelity profile integration tests.
- [x] 9.9 Add validator/conformance failures for unrostered sample IDs, advertised-but-never-producible required channels, invalid provenance, fabricated zero-as-valid optional data, missing context fields, and lost gap/reset sequence continuity.

## 10. Correct Reset Supervision Integrity

- [x] 10.1 Serialize raw, calibrated pre-AI, and adjusted orientations plus adjustment-chain state and per-channel validity immediately before/after every affected physical IMU reset.
- [x] 10.2 Derive and test the canonical target as `normalize(q_post_adjusted * inverse(q_pre_adjusted))`; retain pre-AI values as observations but never substitute them for the declared adjusted target.
- [x] 10.3 Preserve exactly one correlated REQUESTED terminal-outcome lifecycle and every successful per-tracker label across recorder backlog, including cancelled and failed delayed resets.
- [x] 10.4 Move HMD age and motion thresholds into explicit labeler/recorder configuration and deterministically evaluate stale reference, motion, packet-gap, reconnect/reassignment, invalid quaternion, overlap, and truncation over the resolved context window.
- [x] 10.5 Validate event-index/request-ID relationships, reset domain/axis masks, before/after epochs, tracker roster membership, and that pre/post frame ranges are ordered and resolve to available frames or carry an explicit truncation flag.
- [x] 10.6 Add non-commuting adjusted-transform, full/yaw/mounting, partial/multi-tracker, delayed cancel/failure, overflow, reconnect/reassignment, packet-gap, overlap, truncated-window, and invalid-reference integration fixtures through the real reset publisher and recorder.
- [x] 10.7 Extend reset-history tests to full, yaw, and mounting operations and prove queued/in-flight results from every previous epoch cannot restore a stale AI correction.
- [x] 10.8 Re-audit original tasks 3.1–4.8 after these fixes and change their completion state only when direct tests demonstrate the exact task and specification semantics.

## 11. Repair Dataset RPC and GUI Lifecycle

- [x] 11.1 Route recorder state mutations through one server-owned command boundary, validate profile enum values strictly, and return typed errors for invalid arguments and state races.
- [x] 11.2 Give finalization an independently observable cancellation signal so a cancel request in `FINALIZING` interrupts or deterministically rolls back the in-flight operation instead of waiting behind it on the same executor.
- [x] 11.3 Move archive discovery, ZIP parsing, validation, and SHA-256 calculation off the RPC callback thread; bound concurrency and cache results using canonical file identity with explicit invalidation.
- [x] 11.4 Add race tests for stop/cancel, repeated commands, disconnect/reconnect, and multi-client broadcasts, including cancellation while finalization is blocked.
- [x] 11.5 Add generated-binding integration tests and a mounted recorder-widget test that drives start, live status, stop/finalize, cancel, inventory, validation, and path-free reveal/export authorization through real request/response state.
- [x] 11.6 Re-audit original tasks 5.1–5.7 and update their completion state only after the protocol thread remains responsive and the full rendered lifecycle has direct evidence.

## 12. Bind Dataset Readiness to Direct Evidence

- [x] 12.1 Replace the shared `:server:core:test` result assigned to six evidence IDs with distinct machine-readable checks that directly exercise schema, numerical, metadata, reset-label, crash-recovery, and memory-soak semantics.
- [x] 12.2 Version the readiness report to record verifier identity, exact build commit, dirty-tree policy, generation time, each command/result artifact hash, and each pilot archive SHA-256.
- [x] 12.3 Reject duplicate evidence IDs, unknown or mismatched build identities, disallowed dirty builds, expired reports, missing evidence artifacts, and artifact/pilot hash changes during runtime evaluation.
- [x] 12.4 Add negative tests proving a copied, hand-authored, stale, duplicated, or post-generation-mutated report cannot unlock a production collection profile.
- [x] 12.5 Generate and retain direct simulated-pilot evidence through both readers; keep the gate closed until the required physical real-pilot artifacts are supplied and hashed rather than marking their absence as implemented evidence.
- [x] 12.6 Re-run the GUI/server readiness surfaces against accepted and rejected reports and prove server enforcement cannot be bypassed by forged renderer state.
- [x] 12.7 Re-audit original tasks 6.1–6.5 and update their completion state only from direct category checks and actual pilot artifacts.

## 13. Connect and Verify the Motion Training Pipeline

- [x] 13.1 Replace top-level-only pins with a fully resolved, hash-locked dependency closure for the supported Python version(s), and verify a fresh offline/repeatable environment uses exactly that closure.
- [x] 13.2 Add a distributable structural SMPL fixture plus an opt-in licensed-asset integration check covering the real kinematic tree, canonical root/HMD reference, joint-to-segment mapping, resampling, units, and non-commuting coordinate transforms.
- [x] 13.3 Implement one versioned preparation path that converts AMASS layouts/simulation and validated real archives into the same canonical training-example contract with source hashes, masks, reset windows, and quality decisions.
- [x] 13.4 Invoke grouped splitting before window extraction, reject forbidden overlap or unusable empty holdouts, fit normalization only from training groups, and persist the split audit and normalization membership.
- [x] 13.5 Wire dense synthetic, sparse real-reset, temporal, self-supervised, domain, axis, provenance, and quality masks into the loss actually optimized over temporal samples; train the declared global encoder/heads while keeping frozen-backbone training exclusive to personalization.
- [x] 13.6 Wire contextual feature normalization, missingness augmentation, per-feature ablation, and unseen-device/session checks into training and promotion, recording every inclusion/exclusion decision in run provenance.
- [x] 13.7 Make evaluation load a real checkpoint and replay held-out examples to generate predictions and named baseline comparisons; derive safety, cohort, reset-rate, time-to-first-reset, and no-reset-interval metrics from executed model/policy behavior rather than caller-supplied prediction records.
- [x] 13.8 Generate promotion decisions from hash-bound evaluation, ONNX parity, activity/layout/domain/hardware coverage, and non-regression artifacts; do not accept caller-asserted pass booleans as evidence.
- [x] 13.9 Extend run provenance to identify every source archive/motion, group/window assignment, loss summary, feature decision, checkpoint, evaluation, and exported artifact, and add a deterministic repeat-run comparison with documented tolerances.
- [x] 13.10 Derive personalization base identity from the actual model bytes or canonical parameter values, produce worker-executable integrity-bound training/evaluation/optimizer/checkpoint artifacts for every eligible catalog model, and prove equal shapes with different weights cannot share an identity.
- [x] 13.11 Add a deterministic end-to-end fixture that traverses AMASS/real preparation, split, normalization, global training, checkpoint evaluation, ONNX export/parity, promotion, and personalization generation without bypassing intermediate contracts.
- [x] 13.12 Re-audit original tasks 7.1–7.13 and update their completion state only when the connected CLI and end-to-end evidence demonstrate the declared semantics.

## 14. Harden ONNX Artifact and Probe Evidence

- [x] 14.1 Enforce the canonical input/output names, dtypes, ranks, symbolic dimensions, feature width, output semantics, supported opset, bounds, and well-formed provenance hashes independently of what an untrusted sidecar declares.
- [x] 14.2 Stage model, sidecar, parity report, validation metrics, and promotion evidence as one atomic hash-addressed bundle so a failed export cannot leave a publishable partial artifact.
- [x] 14.3 Extend parity coverage to minimum and maximum context, supported batch/slot bounds, invalid shapes/role IDs, all declared outputs, and deterministic cross-process repeats; reject metadata bounds the graph does not actually support.
- [x] 14.4 Complete task 13.8 by making catalog publication load and hash-check generated parity, safety, quality, and cohort reports instead of accepting an `ArtifactPromotionEvidence` object populated by the caller.
- [x] 14.5 Package the probe manifest and expected-output fixture with the model, verify every file hash, and compare every provider's outputs against the committed tensors/tolerances rather than checking only that outputs are finite.
- [x] 14.6 Add tampered sidecar, mutually consistent malicious model/sidecar, changed evidence, wrong expected output, unsupported opset, and partial-bundle negative tests in Python and Kotlin.
- [x] 14.7 Re-audit original tasks 8.1–8.5 and update their completion state only when publication and packaged probes consume the hardened evidence.

## 15. Repair the Runtime Feature and History Contract

- [x] 15.1 Define a versioned Kotlin feature registry/extractor with feature IDs, order, units, source, validity, and missingness semantics, and require every activated model's feature-schema hash to match it.
- [x] 15.2 Build exactly one immutable multi-tracker inference snapshot at the authoritative server sampling boundary with actual monotonic delta and synchronized mapping/epoch state; remove inference submission from per-tracker correction reads.
- [x] 15.3 Extract every declared feature from authoritative runtime state, mark unavailable channels invalid, and reject models whose required inputs cannot be produced instead of filling an anonymous quaternion/acceleration prefix.
- [x] 15.4 Make `contextFrames` control the worker's retained and emitted history within active-model bounds, and atomically clear/rebuild history when context, mapping, model, schema, or reset epoch changes.
- [x] 15.5 Serialize concurrent load/reload/unload operations across RPC and personal activation, and prove a losing activation cannot close or replace the winning session or leak native resources.
- [ ] 15.6 Verify each supported packaged provider with the expected-output probe and record platform/runtime/native-library identity; do not claim CUDA, TensorRT, or DirectML packaging from configuration or documentation alone.
- [ ] 15.7 Add a production-path integration test using a real ONNX session, multiple real tracker callbacks, nonuniform deltas, missing channels, remapping, resets, context changes, stale results, and watchdog failure without direct test-only snapshot injection.
- [ ] 15.8 Re-audit original tasks 9.1–9.8 and update their completion state only after the production sampling/extraction path supplies compatible inputs and direct runtime evidence.

## 16. Make Model Control and UI State Transactional

- [ ] 16.1 Build and validate a complete configuration candidate against active-model context/role/slot bounds and safety policy before mutation; commit runtime plus persisted configuration together and restore both if durable save fails.
- [ ] 16.2 Report remote entries as unverified until their metadata is downloaded and validated, and compute local/catalog compatibility from feature schema, model kind/profile, mapping, context, provider/package, integrity, and readiness rather than hard-coded `compatible=true`.
- [ ] 16.3 Expose authoritative available-provider diagnostics and model-specific allowed bounds so the UI disables unsupported choices or displays the exact server rejection before applying a configuration.
- [ ] 16.4 Serialize switch and rollback with configuration/history updates, retain the prior model and compatible settings until commit, and restore them on probe, persistence, history, or shadow-validation failure.
- [ ] 16.5 Add async request ownership and reconnect tests proving long import/download/load operations do not report success to a dead connection and all clients recover authoritative progress/status without duplicate operations.
- [ ] 16.6 Add mounted Correction-tab integration and accessibility tests that drive import, catalog download, load, provider/context/configuration, mapping, enable, unload, pin/switch, reconnect, failure, and rollback through generated RPC bindings.
- [ ] 16.7 Re-audit original tasks 10.1–10.12 and update their completion state only after configuration, compatibility, switching, and rendered UI behavior have transactional end-to-end evidence.

## 17. Attest Performance, Packaging, and Inference Readiness

- [ ] 17.1 Replace direct zero-filled worker submission with a benchmark mode that traverses the authoritative server sampler, versioned feature extractor, configured history, real ONNX session, result consumption, safety gate, and correction stage using actual monotonic deltas.
- [ ] 17.2 Measure GPU memory from before native runtime/session/model allocation, retain process-scoped utilization where available, and record immutable OS/architecture/CPU/RAM/GPU/driver/runtime/provider/package/model/build identity rather than trusting a caller-supplied host label.
- [ ] 17.3 Make the benchmark gate validate report schema and identity, required warm-up/sample count and cadence, the 10-slot/60-frame scenario, p95 limits, 128 MiB GPU allocation, server-tick overhead, bounded replacement/drop ratio, queue drain, and mandatory provider-specific resource evidence.
- [ ] 17.4 Keep reference task 11.2 pending until the declared weak CPU and entry GPU rows have generated hash-bound baselines from the exact release package; reject the existing development-host report as release evidence while retaining it as diagnostics.
- [ ] 17.5 Build and inspect each claimed Electron and jpackage OS/architecture/provider artifact, include server/GUI/generated protocol/model/probe/license/native payloads and valid startup paths, fix the missing macOS server payload, and prevent x64 artifacts from being labelled as arm64 coverage.
- [ ] 17.6 Run offline smoke from a clean installed/extracted location through the artifact's bundled launcher and runtime, covering server startup, local managed model import, expected-output CPU probe, local recording availability, and every GPU provider claimed for that target.
- [ ] 17.7 Replace generic top-level `passed` aggregation with category-specific evidence parsers and bind the inference-ready report to verifier/policy version, exact clean build, package, model and artifact hashes, generation/expiry time, unique IDs, and retained source bytes revalidated at runtime.
- [ ] 17.8 Keep shadow dogfood task 11.6 pending until required physical sessions exist; derive each session summary from hashed recorder/runtime-status captures with minimum duration, layout/activity/reset/fail-open/performance evidence and an auditable real-player sign-off rather than trusting editable enum and counters.
- [ ] 17.9 Reevaluate exact-build/package/model readiness at activation and effective-state transitions, and add negative tests for forged, copied, stale, mutated, wrong-build, wrong-package, wrong-model, synthetic-dogfood, and missing-provider reports plus a synchronous durable rollback-to-identity test with in-flight work.
- [ ] 17.10 Re-audit original tasks 11.1–11.8 and update their completion state only after production-path benchmarks, final-artifact installed smokes, direct readiness evidence, and the explicitly external reference/dogfood runs satisfy their exact contracts.

## 18. Connect and Attest the Personal Training Lifecycle

- [ ] 18.1 Make selected-session eligibility open and validate the actual canonical `.nvrdata` inventory entries with bounded memory, derive hashes/duration/windows/resets/clean intervals/profile ownership/body assignment/sensor/layout/activity coverage from their contents, and treat summary manifests only as hash-checked caches.
- [ ] 18.2 Reject job creation unless a fresh authoritative eligibility result is ready and bound to the selected profile, base-model bytes, feature schema, sessions, and policy; remove the zero feature-schema hash, validate every enum/index, and persist the immutable admission evidence.
- [ ] 18.3 Connect whole-session chronological train/validation/test assignment, recent suitable holdouts, deterministic balanced streaming windows, exclusions, and cache keys to canonical archive records; persist and hash the exact split/window plan without materializing all telemetry in memory.
- [ ] 18.4 Replace the production `PersonalTrainerWorker` stub with a versioned typed bridge to the installed signature-verified `NekoVR Trainer` process, including package/artifact verification, request correlation, bounded stdout/stderr, crash detection, and safe path/process lifetime handling.
- [ ] 18.5 Generate and package executable ONNX Runtime Training graphs, optimizer state, nominal checkpoints, and probes from the exact base-model values for every personalization-ready catalog entry; run real CPU and optional CUDA training probes while reporting DirectML inference availability separately from training support.
- [ ] 18.6 Enforce frozen-backbone/adapter-only gradients, trainable-parameter and correction bounds, early stopping, deterministic sampler state, and checkpoint hash/state validation in the actual worker backend rather than only helper contracts or fake backends.
- [ ] 18.7 Derive Quick/Balanced/Thorough plans from verified usable coverage and measured host headroom, pass the selected plan and CPU/GPU/RAM/disk/I/O limits to the worker, stream real usage, and require an acknowledged atomic checkpoint before pause/cancel or active-VR throttling changes persisted job state.
- [ ] 18.8 Complete the generated personal-training RPC lifecycle for install/probe/profile/eligibility/job/control/status/evaluate/export/activate with typed errors, asynchronous progress and multi-client/reconnect broadcasts; make every transition server-authoritative and test the real coordinator/worker boundary.
- [ ] 18.9 Replace source-regex UI tests with localized mounted Personal Training-tab tests covering profile creation, personalization-compatible base filtering, session recommendations, blocking findings, cohort coverage, preset/resources, valid action states, staged progress/ETA/metrics/errors, reconnect, and accessibility through generated RPC bindings.
- [ ] 18.10 Restore jobs only after verifying checkpoint bytes/hash, base/artifact identity, optimizer and sampler offsets, then prove application restart and a killed real subprocess resume deterministically while the real correction engine remains available and unmodified.
- [ ] 18.11 Make personal validation load the selected base and candidate checkpoints, replay the persisted held-out reset/clean windows, and derive resets/hour, time-to-first-reset, false correction, jitter, correction bounds, and every sufficiently represented activity/layout cohort directly from executed predictions rather than caller-supplied `EvaluationRecord` arrays.
- [ ] 18.12 Publish the personal ONNX, sidecar, validation report, framework/training-runtime/inference-runtime parity evidence, and base/profile/session/split/checkpoint/metric hashes as one atomic hash-addressed bundle; reject unbound reports, partial publication, and runtime outputs not produced from the exported candidate.
- [ ] 18.13 Wire personal shadow activation into the serialized server model-control/RPC path; derive promotion from retained shadow evidence instead of a caller boolean, persist the previous compatible model/configuration, reset inference history, handle topology changes, and make rollback/load failure synchronous, durable, and truthful.
- [ ] 18.14 Replace the in-process `installed` test with final-package tests that launch the bundled application and signed worker, consume nine bounded multi-hour canonical session fixtures/manifests, resume from a verified checkpoint, validate/export/activate/rollback through RPC/UI, and demonstrate bounded telemetry memory.
- [ ] 18.15 Re-audit original tasks 12.1–12.15 and update their completion state only after the installed production lifecycle and retained artifact evidence demonstrate each exact contract.
