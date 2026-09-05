## ADDED Requirements

### Requirement: Transactional model activation
The engine SHALL validate model bytes and metadata, inspect input/output names/types/shapes, construct a session, run warm-up/probe inference, and atomically activate the model only after all checks succeed.

#### Scenario: Invalid selected model
- **WHEN** a selected file is malformed or its schema hash is incompatible
- **THEN** loading fails with a typed error and the previous active model, if any, remains active

### Requirement: Truthful execution-provider selection
AUTO mode SHALL attempt packaged providers in the configured supported order and mark a provider active only after successful session creation and inference; forced provider mode SHALL fail visibly rather than silently use another provider.

#### Scenario: CUDA option accepted but native provider unavailable
- **WHEN** CUDA session execution cannot complete
- **THEN** CUDA is reported unavailable and AUTO continues to the next provider without claiming CUDA latency

#### Scenario: Forced DirectML failure
- **WHEN** DirectML is forced but cannot execute the model
- **THEN** activation fails with a DirectML-specific diagnostic and does not silently activate CPU

### Requirement: Managed session lifecycle
The engine SHALL own ONNX environment/session/tensor resources, close superseded resources safely, support unload/shutdown, and keep initialization failure from terminating normal tracking.

#### Scenario: Model reload during tracking
- **WHEN** a validated replacement model is activated
- **THEN** in-flight inference completes or is discarded safely, the session swap is atomic, and old native resources are closed

### Requirement: Causal per-tracker history and mappings
The runtime SHALL construct inputs using the model-declared history range, stable slot/body-role mapping, masks, channel validity, normalization, and actual time deltas; history SHALL reset on discontinuity or incompatible remapping.

#### Scenario: Tracker reconnect
- **WHEN** a mapped tracker reconnects after a stale interval
- **THEN** its history is invalidated and correction remains identity until minimum valid context is rebuilt

### Requirement: Per-tracker confidence gating
The runtime SHALL apply correction independently per mapped tracker only when that tracker's output is finite, valid, fresh, above the configured confidence threshold, and supported by the active model.

#### Scenario: Mixed confidence
- **WHEN** three of eight tracker outputs exceed the threshold
- **THEN** only those three trackers are eligible for bounded correction and the other five receive identity correction

### Requirement: Bounded and smooth corrections
Applied corrections SHALL enforce configured angular magnitude, angular-rate, acceleration, and smoothing bounds and SHALL treat quaternion sign equivalence consistently.

#### Scenario: Outlier output
- **WHEN** the model returns a finite correction larger than the safety limit
- **THEN** the runtime clamps or rejects it according to policy and increments an outlier metric

### Requirement: Fail-open asynchronous inference
Inference SHALL run outside the server tracking thread using bounded latest-value queues, and missing, slow, stale, invalid, or failed inference SHALL yield identity correction without pausing or corrupting standard tracking.

#### Scenario: Inference timeout
- **WHEN** result age exceeds the configured maximum
- **THEN** the result is discarded, correction decays/reverts safely to identity, and the tracking tick continues

#### Scenario: Repeated runtime exception
- **WHEN** failures exceed the watchdog threshold
- **THEN** the model is disabled or quarantined, identity correction is used, and runtime status exposes the reason

### Requirement: Single explicit drift-correction stage
AI correction SHALL be applied after established mounting/full/yaw calibration transforms and before final output, with tested policy for replacing or composing legacy drift compensation so the same drift is never corrected twice.

#### Scenario: AI replaces legacy drift
- **WHEN** a tracker is assigned to an active AI model in replacement mode
- **THEN** legacy drift compensation does not contribute an additional correction for that tracker

### Requirement: Reset-aware inference epochs
Each reset or calibration discontinuity SHALL increment an affected tracker's inference epoch and clear/rebase history and applied correction.

#### Scenario: Late pre-reset result
- **WHEN** a result carries an older epoch than the current tracker state
- **THEN** it is rejected and never reaches tracker output

### Requirement: Runtime observability
The engine SHALL expose active model/hash/provider, load state, per-stage latency percentiles, queue depth/drops, inference rate, stale/error/outlier counts, mapped tracker states, confidence summaries, and last typed error.

#### Scenario: UI status request
- **WHEN** a client requests runtime status
- **THEN** values originate from the active server engine and do not use browser GPU-name inference as provider evidence

### Requirement: Lightweight performance gate
For the small model at 10 slots and 60-frame context, the packaged build SHALL target at most 128 MiB incremental GPU memory, p95 inference at most 1 ms on the designated entry GPU, and p95 at most 4 ms on the designated fallback CPU, without blocking the server tick or sustained queue growth.

#### Scenario: Benchmark regression
- **WHEN** a candidate model/runtime exceeds any required latency, memory, tick-blocking, or queue-stability threshold
- **THEN** it fails inference-ready promotion for that provider/tier

### Requirement: ONNX numerical and package validation
CI/release testing SHALL verify framework-to-ONNX parity, masked-layout behavior, provider availability in packaged distributions, deterministic fixtures, and CPU fallback on supported platforms.

#### Scenario: Development classpath succeeds but package fails
- **WHEN** a native provider works in development but is missing from the packaged application
- **THEN** the distribution smoke test fails before release
