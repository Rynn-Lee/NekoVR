## ADDED Requirements

### Requirement: Direct readiness evidence
Each dataset-ready evidence category SHALL be backed by a distinct successful check or machine-readable result that directly exercises that category.

#### Scenario: General server suite passes without soak evidence
- **WHEN** the general server test task succeeds but no bounded multi-hour memory-soak result exists
- **THEN** the memory-soak category remains failed and the dataset-ready gate stays closed

### Requirement: Exact-build readiness binding
An accepted readiness report SHALL identify the exact running build, verifier, dirty-tree policy, generation time, evidence artifacts, and pilot archive hashes.

#### Scenario: Report copied to another build
- **WHEN** a ready report was generated for a different commit or disallowed dirty state
- **THEN** runtime rejects it with an actionable build-identity finding

### Requirement: Readiness report integrity and freshness
Runtime evaluation SHALL reject ambiguous, stale, or changed readiness inputs, including duplicate required evidence IDs and evidence or pilot bytes that no longer match their recorded hashes.

#### Scenario: Pilot changed after validation
- **WHEN** a pilot archive is replaced after the report was generated
- **THEN** hash verification fails and production collection profiles remain locked

### Requirement: Real pilot evidence remains physical evidence
The readiness workflow SHALL distinguish implemented validation tooling from the existence and successful validation of required real-session pilot artifacts.

#### Scenario: Only simulated pilots exist
- **WHEN** all automated checks and simulated pilots pass but no qualifying physical real pilot is supplied
- **THEN** the report remains not ready and states that real-pilot evidence is missing
