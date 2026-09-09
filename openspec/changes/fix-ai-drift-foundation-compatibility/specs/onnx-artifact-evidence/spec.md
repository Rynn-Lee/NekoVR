## ADDED Requirements

### Requirement: Canonical ONNX contract validation
Model validation SHALL enforce the server-supported tensor and feature contract independently of the model's own sidecar and SHALL reject unsupported opsets, shapes, semantics, bounds, or provenance.

#### Scenario: Model and sidecar agree on the wrong contract
- **WHEN** a model and sidecar consistently declare noncanonical tensor semantics or an unknown feature schema
- **THEN** import or activation fails before provider session activation

### Requirement: Atomic evidence-bound model bundle
Export and publication SHALL treat model bytes, sidecar, parity, safety, quality, cohort, and provenance reports as one hash-addressed bundle and SHALL not accept caller-asserted gate results.

#### Scenario: Promotion boolean without report
- **WHEN** a caller reports that activity and quality gates passed but supplies no matching hashed reports
- **THEN** catalog publication fails and no partial entry is written

### Requirement: Representative ONNX parity
Parity validation SHALL cover the declared minimum and maximum context and slot bounds, layouts, permutations, masks, discontinuities, invalid channels, output bounds, and deterministic repeat execution.

#### Scenario: Maximum declared context is unsupported
- **WHEN** the exported graph passes short fixtures but fails at its sidecar's maximum context
- **THEN** the artifact fails validation and cannot be published

### Requirement: Expected-output provider probe
Every packaged provider probe SHALL verify the probe bundle manifest and compare all output tensors to committed expected values within declared tolerances.

#### Scenario: Provider returns finite wrong values
- **WHEN** a provider successfully executes but returns finite outputs outside probe tolerance
- **THEN** that provider is reported unavailable for the package and is not eligible for activation
