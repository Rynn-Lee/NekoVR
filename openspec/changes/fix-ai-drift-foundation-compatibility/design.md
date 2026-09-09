## Context

The original AI Drift change established a canonical `.nvrdata` format and readiness gates, but its foundation and recorder/reset audits exposed gaps between the declared contract and executable behavior. Java emits a streaming Zstandard frame without a declared content size, while the Python reader uses a one-shot decompression API that requires one. Dataset readers bypass checked FP16 decoding, no exhaustive binary16 or full-field cross-language fixture is present, and the validators compare only a subset of the archive contract. The full-fidelity start check currently requires every tracker to advertise global/context channels and is therefore unattainable; UDP capability mapping is incomplete and maps calibration quality to the temperature channel; HID/nRF negotiation is not wired into production readers. Context tracker positions are discarded and their IDs can be created after the immutable roster has already been written. Several declared native/correction channels are absent or use the wrong provenance. Recorder overflow can consume pending gap/reset events and labels before a failed queue offer. Reset targets are computed from pre-AI orientations although the contract declares adjusted orientations, which are not serialized with channel validity. Separately, the UI does not expose authoritative inference readiness, generic Electron paths are checked lexically, and the current GUI lint baseline is red. The inference benchmark submits zero-filled snapshots directly to the worker, calls submission time "server tick blocking", permits unlimited replacement by default, and samples GPU memory only after the model session and warm-up already allocated it. Package checks inspect the ShadowJar rather than each installed target, macOS Electron assembly has no server JAR entry, and smoke tests use the CI host Java executable. The final readiness loader then trusts the copied report's booleans and hashes without checking the running build, age, or source artifacts.

This corrective change spans Kotlin, Python, TypeScript, Electron, schema validation, ML orchestration, and CI. It must preserve the existing v1 append-only field numbering and must not manufacture real-pilot or model-promotion evidence.

## Goals / Non-Goals

**Goals:**

- Make every canonical archive written by Kotlin consumable through the supported Python training reader.
- Convert numerical, schema, registry, footer, profile, and compression claims into automated positive and negative tests.
- Make recorder profiles, capability advertisement, roster/context capture, native/correction telemetry, backpressure behavior, and reset supervision agree with their declared contracts.
- Ensure readiness and Electron trust boundaries are enforced consistently by server, preload, and renderer layers.
- Make recorder cancellation, finalization, inventory, and rendered RPC/UI behavior deterministic without blocking the protocol or tracking threads.
- Make readiness reports exact-build attestations assembled from direct checks rather than mutable summaries accepted on trust.
- Turn the existing ML helpers into one auditable preparation, training, evaluation, promotion, and personalization workflow.
- Make personal training one server-owned, restart-safe lifecycle whose eligibility, worker execution, validation, export, and activation are derived from verified artifacts rather than caller assertions.
- Make exported ONNX bundles, provider probes, runtime features/histories, and control-plane compatibility use the same enforceable model contract.
- Make local baseline commands and CI report the same result and leave the tree with all foundation checks green.

**Non-Goals:**

- Capturing physical-tracker pilots, reference-hardware benchmarks, or dogfood evidence.
- Changing the `.nvrdata` major version or renumbering existing FlatBuffer fields.
- Enabling production AI correction without inference-ready evidence.
- Treating developer-host benchmarks, staged JAR smoke, or hand-authored dogfood/readiness JSON as release evidence.
- Converting incomplete prototype archives into canonical training data.

## Decisions

### Stream Zstandard in Python

The Python loader will use a streaming decompressor/read loop that accepts frames with or without a content-size header and applies explicit decompressed-size and record-size limits. Zstandard-library exceptions will be translated to `DatasetFormatError`, allowing `validate_ready.py` to persist a failed report instead of crashing. Requiring Java to buffer the entire stream merely to publish content size was rejected because it conflicts with bounded-memory recording.

### Keep one schema and verify both binding surfaces

`nekovr_dataset_v1.fbs` remains the source of truth and existing ordinals stay append-only. A committed deterministic fixture will be produced by the Kotlin writer and inspected by Python. The fixture will include every v1 table, representative optional fields, every provenance/validity state used by the contract, non-trivial FP16 values, events, reset transforms, quality counters, and a footer. Tests will compare reconstructed values and composition results rather than only aggregate counts.

### Apply checked decoding at reader boundaries

Kotlin and Python readers will reject non-finite, out-of-range, or invalid normalized feature values before returning domain objects. Raw IEEE-754 conversion helpers may preserve all bit patterns for exhaustive codec tests, but dataset-facing readers must use channel-specific checked functions. This separates codec correctness from archive acceptance policy.

### Validate semantic descriptors and footer integrity

Validators will parse the full header registry and footer. Known channel IDs must match canonical name, unit, coordinate frame, cadence, precision, minimum profile, and allowed provenance. Unknown optional channels may be skipped; collisions or incompatible required semantics are fatal. Validators will also verify canonical ZIP members, record ordering, one terminal complete footer, footer counters against decoded data and manifest, and a precisely defined checksum scope. The checksum scope will avoid self-reference by naming and documenting the bytes covered by the footer checksum; the manifest continues to hash the final compressed telemetry entry.

### Make readiness server-authoritative and visible

Model runtime/status RPC will expose inference-ready state and an actionable reason tied to the active model hash. Dataset status continues to expose dataset readiness. Renderer controls will derive enabled/locked states only from these responses. Diagnostic operations that are valid before promotion remain available only when explicitly labelled diagnostic; production recording and active correction controls remain disabled until their gates pass. Server enforcement remains mandatory even if a renderer is compromised.

### Canonicalize Electron targets before authorization

Filesystem authorization will resolve the allowed root and existing target through `realpath`, reject symlinks/junction escapes and unsupported target types, and use session IDs rather than renderer-provided paths wherever possible. External navigation and new-window creation will be denied by default and routed through the same explicit URL policy as `OPEN_URL`. Main, preload, and shared code will use the typed channel constants exclusively.

### Run one reproducible foundation baseline

The documented local baseline will use the platform-correct executable invocation and include server core tests, GUI tests, lint, production build, and the Kotlin/Python conformance suite. CI will expose these as required jobs without relying on an unrelated packaging job to provide core-test coverage. Dataset-ready evidence will reference the same commands and will assign evidence categories only to tests that actually exercise that category.

### Separate global profile requirements from per-tracker capabilities

Collection profiles will distinguish canonical/global context requirements from channels that each physical tracker must provide. A tracker will advertise only channels actually delivered by its production transport or derivable under the declared provenance. Capability negotiation will use one canonical channel-ID mapping for UDP and HID/nRF, including every supported present-mask field. Legacy devices remain recordable under compatible profiles through explicit unavailable state; the full-fidelity profile must be achievable by a supported topology rather than requiring every tracker to own controller, skeleton, floor, and model-global channels.

### Freeze a complete roster and preserve positional context

All identities that can appear in frame records, including HMD/controllers and other context sources, will be allocated before the roster record is written. Later topology changes use events and never create an unrostered sample reference. Append-only schema fields will preserve context positions, skeleton/body/floor state, and per-channel validity/provenance. Activity classification will either produce every advertised cohort with confidence or explicitly return `UNKNOWN`; channel-registry presence alone is not evidence that a signal is recorded.

### Keep loss accounting and reset supervision loss-safe

Sample backpressure may drop canonical frames but must not discard pending reset lifecycle events, completed reset labels, or the gap accounting for earlier failures. Pending control data is acknowledged only after a successful enqueue/write, with bounded storage and deterministic coalescing where needed. Reset labels will serialize raw, calibrated pre-AI, and adjusted before/after states plus validity, and compute `normalize(q_post_adjusted * inverse(q_pre_adjusted))`. Quality thresholds become recorder configuration, and validators verify that event/label references and context ranges resolve to retained frames.

### Serialize recorder commands and separate control from bulk work

Recorder state mutations will enter through one server-owned command boundary. Finalization may use a worker, but cancellation must have an independent interrupt/control signal that can be observed while finalization is running; it must not wait behind the operation it is intended to cancel. Archive discovery, ZIP parsing, validation, and SHA-256 calculation will run off the RPC callback thread with bounded concurrency and cache invalidation keyed by stable file metadata. Unknown profile values fail with a typed argument error. Tests will exercise generated request/response bindings, real handler scheduling, broadcasts, reconnects, and a mounted renderer component rather than only reducer functions.

### Treat dataset readiness as build-bound evidence

The report generator will execute a distinct check or consume a distinct machine-readable artifact for each readiness category. The report will record the exact source/build identity, dirty-tree policy, generation time, verifier version, command outcome, and hashes of evidence and pilot archives. Runtime acceptance will reject duplicate evidence IDs, unknown or dirty build identities outside an explicit development policy, stale reports, build mismatches, missing artifacts, and changed hashes. A report's own `ready` boolean remains a summary and is never sufficient evidence by itself.

### Make the ML command path own the declared pipeline

One versioned preparation/training command path will construct canonical examples from licensed AMASS plus validated `.nvrdata`, assign immutable groups before windowing, fit normalization on training groups only, and build the configured dense/sparse/temporal/self-supervised/domain/axis/quality losses actually consumed by optimization. Global base-model training will update the declared trainable encoder/head parameters; frozen-backbone behavior is reserved for the separate personalization workflow. Contextual features enter a candidate only through recorded normalization, missingness, ablation, and unseen-cohort decisions.

Evaluation will load a checkpoint and replay held-out canonical inputs to produce predictions, compare named baselines, and derive cohort and reset-policy metrics from those executions. Promotion evidence will be generated from hashed evaluation/parity artifacts rather than caller-provided booleans. Run manifests will trace source archives/motions, groups, windows, normalization, losses, checkpoints, metrics, and exported bytes. A personalization-ready descriptor will hash the actual base model bytes or canonical parameter values; its training/evaluation/optimizer artifacts must be executable by the supported worker and produced for every eligible catalog entry.

### Make personal training one attested lifecycle

The server will derive selected-session eligibility by opening canonical inventory entries through the supported bounded reader, validating archive and profile ownership, and hashing the actual bytes. Jobs may be admitted only from a ready result bound to the selected base model, feature schema, immutable whole-session split, and streamed window plan. Summary manifests may be cached indexes, but are never an authority for integrity, duration, labels, coverage, or hashes.

The production coordinator will launch the installed, signature-verified trainer executable over versioned typed IPC and bind it to worker-executable artifacts generated from the selected base model. Pause, cancellation, throttling, progress, provider probes, resource readings, and checkpoint acknowledgement are protocol operations with request identity and bounded output. A job is not `PAUSED`, resumable, evaluated, or complete merely because metadata says so: referenced checkpoint bytes, optimizer/sampler state, hashes, and terminal worker results must be present and verified.

Evaluation will load the base and candidate checkpoints and replay the persisted held-out split to derive reset and clean-motion metrics for every sufficiently represented activity/layout cohort. Export will publish the ONNX model, sidecar, validation report, parity evidence, and full provenance as one hash-addressed atomic bundle. Activation will be serialized with normal model control, derive shadow success from retained inference evidence, persist previous state, reset history, react to topology incompatibility, and fail synchronously back to a known compatible model. The renderer displays only authoritative RPC state and cannot bypass eligibility or lifecycle transitions.

### Bind ONNX bundles to generated evidence

The sidecar validator will enforce the canonical tensor names, dtypes, ranks, symbolic dimensions, feature width, output semantics, bounds, provenance hashes, and supported opset rather than only checking that a model and its self-described sidecar agree. Export will stage the model, sidecar, parity report, and promotion evidence as one atomic bundle. Publication will recompute or verify hashes for parity, safety, cohort, and quality artifacts; caller-supplied booleans are not evidence. Provider/package probes will verify the committed probe manifest and compare every output tensor against expected values and tolerances.

### Build inference inputs once from the server feature contract

A versioned server feature registry will map each model feature ID to its authoritative source, units, normalization, validity, and fallback semantics. Activation must match its feature-schema hash to that registry. Once per server sampling tick, the server will create one immutable snapshot containing all mapped trackers at the same monotonic boundary and actual delta; tracker output access will only consume completed results and must not enqueue partial snapshots. The worker will use the configured context length within metadata bounds and clear histories atomically whenever context, mappings, feature schema, reset epoch, or active model changes.

### Apply model-control changes transactionally

RPC configuration will first build and validate a complete candidate against the active model, available provider state, mapping uniqueness/roles/slots, safety limits, and persisted schema. Only after validation will runtime and persisted state swap together; save failure restores the prior state. Remote and local catalog entries remain `unverified` or incompatible until the server validates their model/sidecar identity and current feature/mapping/context compatibility. Model switches and rollback serialize activation ownership and restore the previous model plus compatible configuration/history state if any stage fails.

### Attest the production inference release path

Performance measurement will enter through the authoritative server sampling boundary and exercise the feature extractor, history, real ONNX session, result consumption, safety gate, and identity/active correction stage used by the packaged server. Host identity will be derived from recorded OS, architecture, CPU, memory, GPU, driver, runtime/provider, package, model, and build facts and checked against an immutable policy; a caller-provided host label is only a selector. GPU incremental memory starts before native runtime/session/model allocation. Policy requires the declared warm-up/sample counts and cadence, bounds server-tick overhead and latest-value loss, and rejects missing resource measurements required by that provider.

Distribution evidence will be generated independently for every supported OS/architecture/provider flavor from the final Electron or jpackage artifact. The test installs or extracts into a clean location, uses the bundled launcher and runtime, verifies GUI/server/protocol/model/probe/license/native contents and startup resolution, enforces network isolation, and executes expected-output CPU plus applicable GPU-provider probes. Unsupported architectures remain explicitly non-inference targets and cannot be relabelled as covered artifacts.

Inference readiness will parse each evidence category with its own schema instead of accepting a generic top-level `passed`. The report records verifier/build/package/model identities, generation and expiry, policy version, source paths and hashes, and required physical-dogfood capture identities. Runtime acceptance rechecks the running build/package/model and retained evidence hashes (or a release signature covering them). Dogfood summaries must be derived from hashed archives/runtime captures for the required real sessions and cannot become `REAL_PLAYER` evidence by editing an enum. Active correction authorization is reevaluated against this attestation on every activation/state transition; rollback synchronously disables and durably records identity mode while invalidating pending correction state.

## Risks / Trade-offs

- [Stricter readers reject archives previously accepted by Kotlin] → Preserve major-version compatibility but treat semantically invalid archives as diagnostic-only and emit precise findings.
- [Streaming decompression permits decompression bombs] → Enforce compressed-entry, decompressed-stream, per-record, record-count, and frame-count limits.
- [A fixture can drift from the schema] → Generate it deterministically in tests and verify its committed hash and full decoded content in both languages.
- [Windows symlink tests require privileges] → Test the canonical authorization helper with injectable filesystem resolution and run real symlink tests where supported; a skipped platform test cannot be the only coverage.
- [Readiness UX can hide useful diagnostics] → Separate diagnostic model management from production enablement and explain the exact blocking reason.
- [Footer checksum semantics require clarification] → Document the checksum byte scope and add mutation tests before accepting new archives.
- [Adding complete context and correction telemetry increases record size] → Keep optional append-only fields, profile-aware capture, change/native cadence, and explicit per-record size budgets.
- [Preserving control events during sample overflow can create a second unbounded queue] → Use a bounded priority/control path with coalesced gap data and fail the recording explicitly if lossless control metadata cannot be retained.
- [Large archive inventories can stall RPC handling] → Hash and validate asynchronously with bounded workers and cache entries keyed by canonical path, size, and modification identity.
- [A copied or edited readiness JSON can unlock another build] → Bind acceptance to exact build identity, evidence hashes, pilot hashes, freshness, and an explicit dirty-development policy.
- [Unit-tested ML helpers can drift from the production command path] → Require one small end-to-end fixture to traverse preparation, split, training, evaluation, export, promotion, and personalization on every baseline run.
- [Licensed AMASS/SMPL assets cannot be committed] → Keep a legally distributable structural fixture for coordinate/kinematic tests and run opt-in licensed-asset integration with recorded hashes before promotion.
- [Strict feature-schema matching rejects old experimental models] → Keep them importable for diagnostics but never activate them until explicitly migrated to a known extractor contract.
- [One synchronized inference snapshot needs coordination across trackers] → Build it from the already server-owned immutable sampling boundary and keep inference submission non-blocking with latest-value replacement.
- [Provider probes can pass while computing the wrong graph] → Verify bundle hashes and expected numerical outputs, not only session creation and finiteness.
- [Transactional configuration requires rollback on persistence failure] → Validate a complete candidate first and retain an immutable prior runtime/configuration snapshot until durable save succeeds.
- [Reference hardware and physical dogfood cannot run on ordinary CI] → Keep those tasks explicitly pending, retain immutable external artifacts, and let CI verify their schemas, hashes, build identity, and policy without manufacturing a pass.
- [Installed-package tests multiply the release matrix] → Declare the supported inference matrix centrally, build each artifact for its real architecture, and fail release assembly when any claimed row lacks launcher-level smoke evidence.

## Migration Plan

1. Fix Python streaming decompression and error normalization, then add a regression test for current Kotlin output.
2. Complete checked readers and numerical tests without changing schema ordinals.
3. Repair profile/capability mapping, freeze the complete roster, and add the missing context/native/correction fields through append-only schema changes.
4. Correct adjusted-orientation reset targets and make gap/reset control data durable across queue overflow.
5. Add full-field conformance fixtures and strengthen validators; revalidate existing simulated archives and classify newly rejected archives.
6. Extend readiness RPC/UI and harden Electron policies with unit tests.
7. Restore formatting and wire the unified baseline into CI and dataset-ready evidence.
8. Re-run simulated pilots through both validators. Physical pilot collection remains a later explicit step.
9. Repair recorder RPC scheduling/inventory and add generated-binding plus rendered-component lifecycle tests.
10. Version and bind dataset-ready evidence to the running build, then regenerate simulated evidence; real pilot evidence remains explicit external work.
11. Connect the ML preparation/training/evaluation/promotion stages, replace declarative placeholder personalization graphs with worker-executable artifacts, and pass a deterministic end-to-end fixture.
12. Harden ONNX sidecar/export/promotion/probe evidence and validate it through both Python and packaged Java runtimes.
13. Replace per-tracker ad hoc inference submission with the canonical synchronized feature extractor and effective context configuration.
14. Make model configuration, catalog compatibility, switching, and rollback transactional, then add rendered RPC/UI lifecycle coverage.
15. Replace the synthetic benchmark boundary, correct resource baselines and policy enforcement, then capture the still-pending reference-host results.
16. Repair platform package assembly and run launcher-level offline smokes for every claimed target/provider.
17. Harden inference-ready and dogfood attestations, revalidate them at runtime, and only then re-audit tasks 11.1–11.8.
18. Connect archive eligibility, packaged trainer execution, checkpoint validation/export, personal activation, and installed-package evidence, then re-audit tasks 12.1–12.15.

Rollback consists of disabling the stricter promotion gates while retaining fail-closed runtime behavior; schema fields and ordinal changes must not be rolled back independently.

## Open Questions

- Whether the footer checksum will cover uncompressed length-prefixed records before the footer or another explicitly reproducible prefix representation.
- Whether the inference-ready fields belong directly in the existing runtime status response or in a shared readiness response used by model and personal-training surfaces.
- Which build identity source is authoritative for development runs versus packaged releases, and what maximum report age is allowed for each.
