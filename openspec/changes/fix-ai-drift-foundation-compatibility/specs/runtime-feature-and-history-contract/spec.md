## ADDED Requirements

### Requirement: Runtime-owned feature schema
The server SHALL own a versioned feature registry and extractor defining feature order, units, sources, validity, and missingness, and SHALL activate only models whose feature-schema hash matches that executable contract.

#### Scenario: Same width but different features
- **WHEN** a model declares the expected feature count but a different order or meaning
- **THEN** activation fails with a feature-schema mismatch rather than feeding it positional values

### Requirement: Synchronized inference snapshot
The server SHALL submit at most one immutable multi-tracker snapshot per authoritative sampling boundary using the actual monotonic delta; reading a tracker's correction SHALL NOT enqueue inference work.

#### Scenario: Eight trackers update in one tick
- **WHEN** eight mapped trackers are processed during one server tick
- **THEN** the worker receives one snapshot with one timing boundary and eight consistently versioned samples rather than eight mixed-age partial snapshots

### Requirement: Effective configurable history
The configured context length SHALL determine retained/emitted causal history within active-model bounds and any context, mapping, model, schema, or reset discontinuity SHALL invalidate incompatible history.

#### Scenario: Context changes from sixty to twenty
- **WHEN** the server accepts a twenty-frame context supported by the active model
- **THEN** old history is cleared and inference resumes only after the required twenty-frame causal window is rebuilt

### Requirement: Race-safe runtime lifecycle
Load, reload, unload, personal activation, mapping, and history reset operations SHALL have serialized ownership so stale operations cannot replace a newer model or close resources it owns.

#### Scenario: Concurrent global and personal activation
- **WHEN** two activation requests overlap and complete out of order
- **THEN** only the committed request becomes active, the prior valid session remains available until commit, and all losing resources are closed

### Requirement: Production-path runtime verification
Runtime integration tests SHALL exercise the actual server sampler, feature extractor, real ONNX session, correction stage, reset epochs, and provider probe without relying only on directly injected `InferenceSnapshot` fixtures.

#### Scenario: Test-only worker injection passes
- **WHEN** isolated worker tests pass but the tracker/server path does not produce compatible model inputs
- **THEN** runtime readiness remains failed
