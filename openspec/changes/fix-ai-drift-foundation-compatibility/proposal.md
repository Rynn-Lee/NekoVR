## Why

The foundation, recorder/reset, RPC/readiness, ML-pipeline, ONNX-runtime, model-control, and inference-release tasks of `complete-ai-drift-data-pipeline` are marked complete even though the audited tree still has material contract gaps. In addition to the dataset and training issues, ONNX publication accepts caller-asserted promotion booleans, and the packaged probe verifies only finite output rather than the committed expected values. Normal model activation does not require the server's feature-schema hash. The production inference path invents a fixed seven-value quaternion/acceleration prefix, submits a separate mixed-age snapshot from each tracker callback with a hard-coded 20 ms delta, and ignores the configured context length. The benchmark bypasses that production path, measures GPU memory only after session creation, and its gate omits a server-tick limit and permits unrestricted latest-value replacement. Electron packaging omits the server payload on macOS, while its "installed" smoke launches a staged JAR with the host JDK rather than the installed application. Finally, the runtime accepts an editable inference-ready summary without validating the running build, freshness, or retained evidence bytes. These gaps must be closed before real pilot evidence or promoted active models can be trusted.

## What Changes

- Make the canonical streaming Zstandard payload readable by both the Kotlin validator and the supported Python reader, including unknown-content-size frames and normalized error reporting.
- Add checked binary16 decoding and exhaustive numerical tests for normal, subnormal, signed-zero, overflow, infinity, NaN, range, quaternion normalization, and documented error tolerances.
- Add committed Kotlin-writer/Python-reader conformance fixtures that verify all required fields and quaternion transform composition, and run them in the normal verification baseline.
- Strengthen archive validation to verify header/manifest channel semantics, footer completeness/counters/checksum semantics, exact canonical ZIP contents, schema compatibility, and required profile behavior.
- Make collection profiles attainable and truthful, fix UDP and HID/nRF capability negotiation, and record complete roster, positional/body context, native telemetry, correction-pipeline state, validity, and provenance without fabricated zero values.
- Preserve reset request/application outcomes and labels across recorder backpressure, and derive reset supervision from serialized adjusted orientations using the declared composition convention.
- Make dataset and model controls visibly and functionally follow their corresponding readiness gates while retaining explicitly labelled diagnostic operations where allowed.
- Make recorder RPC state transitions serialized, cancellation effective during finalization, archive inventory work non-blocking, and the rendered GUI/RPC lifecycle executable in tests.
- Bind dataset-ready acceptance to the exact build and directly produced evidence artifacts, rejecting duplicate, stale, unverifiable, or mismatched reports.
- Connect AMASS and real-session preparation, leakage-safe splitting, training-only normalization, domain-aware losses, feature qualification, checkpoint evaluation, promotion, and provenance into one executable ML workflow.
- Generate personalization artifacts from the exact base-model bytes/weights and require executable, integrity-bound artifacts for every catalog model advertised as personalization-ready.
- Make ONNX publication consume hash-bound parity/safety/cohort evidence and make packaged probes compare the committed expected tensors, not merely finite output.
- Define one server feature-extraction schema and build one immutable multi-tracker inference snapshot per server tick with actual timing, explicit missingness, and the configured context window.
- Make activation, configuration, catalog compatibility, and rollback transactional and model-compatible across server, RPC, and renderer state.
- Make performance evidence exercise the production sampling/feature/correction path, measure resource allocation from a pre-session baseline, and bind the declared weak-hardware identity to observed CPU/GPU/driver/runtime facts.
- Verify each actual Electron/jpackage target after installation with its bundled launcher/runtime and require source-specific, build-bound inference-ready and physical-dogfood evidence before active correction can be authorized.
- Harden Electron IPC file/URL/navigation boundaries against symlink escapes, traversal, untrusted renderer navigation, and contract drift.
- Restore a genuinely passing GUI lint/build/test and server-core test baseline and ensure CI invokes the same checks used by the dataset-ready gate.
- Connect personal training to verified canonical archives, executable signed worker artifacts, measured resource controls, checkpoint-derived validation/export evidence, and transactional server activation instead of trusting summary manifests, caller-supplied records, or in-process test doubles.

## Capabilities

### New Capabilities

- `dataset-cross-language-interoperability`: Canonical Kotlin/Python compression, binding, reader, fixture, and transform compatibility.
- `dataset-numerical-and-archive-validation`: Checked FP16 handling plus complete manifest, channel-registry, footer, checksum, profile, and prototype validation.
- `ai-readiness-control-surface`: Truthful dataset/model availability and action gating derived from authoritative readiness state.
- `electron-trust-boundary`: Typed IPC contracts and canonical URL, navigation, and filesystem allowlist enforcement.
- `foundation-verification-baseline`: Reproducible local and CI checks for GUI and server foundation work.
- `streaming-recorder-fidelity`: Truthful profile/capability negotiation, complete roster/context/correction telemetry, loss-safe backpressure, and bounded-memory evidence.
- `reset-label-integrity`: Durable reset lifecycle capture and adjusted-orientation supervision labels with resolvable windows and deterministic quality policy.
- `dataset-rpc-lifecycle`: Serialized recorder commands, interruptible finalization, bounded asynchronous inventory, and rendered end-to-end RPC/UI verification.
- `dataset-readiness-attestation`: Direct, build-bound, freshness-checked readiness evidence and pilot identity verification.
- `motion-training-pipeline-integrity`: An executable preparation-to-promotion workflow whose losses, features, evaluation, provenance, and personalization artifacts are derived from the actual data and model bytes.
- `onnx-artifact-evidence`: Strict sidecar/export validation, evidence-derived promotion, and expected-output provider probes.
- `runtime-feature-and-history-contract`: Canonical feature extraction, synchronized inference snapshots, effective context configuration, and race-safe session lifecycle.
- `model-control-transaction-integrity`: Atomic model configuration/switching and truthful catalog/UI compatibility derived from validated server artifacts.
- `inference-release-attestation`: Production-path benchmarks, installed distribution/provider verification, physical dogfood provenance, and exact-build inference-ready authorization.
- `personal-training-lifecycle-integrity`: Archive-derived eligibility, signed out-of-process training, authoritative RPC/UI state, resumable checkpoints, held-out validation, atomic export, activation, and installed-package evidence for personal models.

### Modified Capabilities

None. The main specification set does not yet contain these capabilities; this corrective change defines the missing verification contracts explicitly.

## Impact

Affected areas include the Kotlin dataset writer/readers and validators, snapshot/roster/reset instrumentation, recorder RPC scheduling and inventory, UDP and HID/nRF telemetry integration, the Python `nekovr_dataset` loader and dataset-ready command, the `ml/` preparation/training/evaluation/export/promotion and personal-training commands, personal profile/job/checkpoint stores, trainer-worker packaging and IPC, ONNX sidecars and probe bundles, Kotlin feature extraction/inference workers/provider probes and benchmark/readiness gates, managed model/catalog/history/configuration services, model and personal-training RPC handlers and AI Drift React controls, Electron/jpackage assembly and launchers, GUI formatting/tests, and the GitHub Actions build workflow. The canonical `.nvrdata` major version remains unchanged; fixes must preserve append-only schema compatibility and reject malformed or semantically incompatible archives rather than silently reinterpret them.
