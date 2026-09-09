## ADDED Requirements

### Requirement: Production-path performance evidence
Inference benchmarks SHALL exercise the packaged server's authoritative sampling, feature extraction, causal history, ONNX execution, result consumption, safety gating, and correction stage, and SHALL measure server-thread overhead and resources from a pre-runtime baseline.

#### Scenario: Direct worker benchmark passes
- **WHEN** a synthetic harness submits zero-filled `InferenceSnapshot` values directly to the worker without executing the server feature and correction path
- **THEN** its result remains diagnostic and cannot satisfy inference-ready performance evidence

#### Scenario: GPU session allocation exceeds the budget
- **WHEN** native runtime, session, model, and warm-up allocation exceeds 128 MiB before measured submissions begin
- **THEN** the incremental GPU-memory gate fails rather than resetting its baseline after allocation

### Requirement: Reference policy and host identity
The small-tier gate SHALL validate report schema, model/package/build identity, observed reference hardware and driver facts, 10-slot/60-frame shape, required warm-up/sample count and cadence, p95 latency, server-tick overhead, bounded queue loss/drain, and mandatory provider resource measurements.

#### Scenario: Host label is copied
- **WHEN** a developer machine report is edited to carry a reference host ID but its observed hardware or package identity differs
- **THEN** reference performance evidence is rejected

#### Scenario: Queue eventually drains after sustained replacement
- **WHEN** the latest-value queue drains at the end but exceeds the policy's replacement/drop or server-tick overhead limit during measurement
- **THEN** the benchmark fails instead of passing on final depth alone

### Requirement: Final-distribution inference verification
Every claimed OS, architecture, and provider flavor SHALL be verified from the final Electron or jpackage artifact after clean installation/extraction using its bundled launcher/runtime, with GUI, server, generated protocol, model/probe metadata, licenses, native libraries, and startup paths present.

#### Scenario: macOS package omits the server
- **WHEN** the final application artifact has GUI assets but no resolvable server JAR or launcher path
- **THEN** distribution verification fails even if the standalone ShadowJar passed

#### Scenario: Architecture is relabelled
- **WHEN** an x64 package is produced on an arm64 matrix row or renamed as arm64 without matching native contents
- **THEN** that row is rejected and cannot count as supported inference coverage

### Requirement: Installed offline provider smoke
Installed smoke SHALL run with network access denied through the final artifact's launcher and bundled runtime, start the local server, import the managed model, verify expected probe tensors on CPU and every provider claimed for that target, and confirm local recording remains available.

#### Scenario: Staged JAR uses host Java
- **WHEN** CI invokes a JAR found in package staging with the runner's separately installed JDK
- **THEN** the result does not attest the installed launcher, bundled runtime, paths, or native resolution

### Requirement: Direct exact-build inference attestation
The inference-ready report SHALL validate each category through its specific schema and SHALL bind verifier/policy version, generation/expiry, clean build, final package, model, source evidence, and artifact hashes. Runtime SHALL revalidate these identities and retained bytes or a trusted signature covering them.

#### Scenario: Generic passing JSON
- **WHEN** a hash-listed source contains only a top-level `passed: true` without the required category measurements and identities
- **THEN** aggregation rejects it and active correction remains unavailable

#### Scenario: Report copied or edited
- **WHEN** a valid summary is copied to another build/package or its evidence bytes change after generation
- **THEN** runtime rejects it even when all embedded booleans remain true

### Requirement: Physical shadow-dogfood provenance
Shadow-dogfood evidence SHALL be derived from hashed real-session archives and runtime captures for required layouts, activities, resets, fail-open injections, queue/performance observations, minimum duration, and auditable operator sign-off; editable evidence-kind labels SHALL NOT establish physical provenance.

#### Scenario: Hand-authored real-player counters
- **WHEN** a report marks synthetic or absent sessions as `REAL_PLAYER` and supplies plausible passing counters without source captures
- **THEN** the dogfood gate rejects it and task 11.6 remains incomplete

### Requirement: Fail-closed active correction and rollback
Active correction SHALL require explicit opt-in plus a currently valid attestation for the exact running build, package, provider, and active model. Rollback SHALL synchronously return identity, invalidate pending correction state, and durably record disabled effective state without modifying calibration/reset state.

#### Scenario: Evidence becomes invalid after startup
- **WHEN** the active report expires, changes, or no longer matches the effective provider/package/model
- **THEN** authorization fails closed at the next effective-state evaluation and correction returns identity

#### Scenario: Rollback with inference in flight
- **WHEN** rollback occurs while a pre-rollback inference is queued or running
- **THEN** it cannot reapply correction, identity is immediate, and restart does not silently re-enable the prior effective state
