## ADDED Requirements

### Requirement: Authoritative readiness status
Dataset and model control surfaces SHALL derive promotion availability from server-authoritative readiness state, including the gate result, blocking reason, and model hash where applicable.

#### Scenario: Inference report missing
- **WHEN** no valid inference-ready report exists for the active model hash
- **THEN** the GUI clearly labels active correction unavailable, displays the server reason, and disables the production Enable action

#### Scenario: Dataset gate pending
- **WHEN** dataset-ready evidence is incomplete
- **THEN** the GUI permits only explicitly labelled diagnostic recording allowed by server policy and keeps production profiles disabled

### Requirement: Defense in depth
Server-side authorization SHALL remain the final authority for production recording and correction even when a renderer sends a forged request.

#### Scenario: Forged enable request
- **WHEN** a client requests active correction while inference readiness is false or belongs to another model hash
- **THEN** the server rejects application of correction, returns an actionable typed reason, and tracker output remains fail-open identity

### Requirement: Truthful action acknowledgement
The GUI SHALL not present a gated configuration acknowledgement as evidence that production correction or collection is active.

#### Scenario: Configuration persisted but promotion denied
- **WHEN** a configuration preference is saved while the readiness gate denies runtime activation
- **THEN** the UI distinguishes persisted intent from effective runtime state and continues to show the feature as gated
