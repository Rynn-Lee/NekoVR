## ADDED Requirements

### Requirement: Reproducible foundation baseline
The repository SHALL provide and document a baseline that runs server core tests, GUI tests, GUI lint, the production GUI build, and Kotlin/Python dataset conformance with reliable exit-code propagation on every supported developer platform.

#### Scenario: Clean compatible tree
- **WHEN** the baseline runs on a clean compatible checkout
- **THEN** every command executes rather than being skipped or reported from stale output and the aggregate exits zero

#### Scenario: Formatting failure
- **WHEN** Prettier reports a changed or unformatted source file
- **THEN** the local baseline and CI job both exit non-zero and identify that file

#### Scenario: Cross-language regression
- **WHEN** Kotlin writes an archive that Python cannot decode or reconstruct within tolerance
- **THEN** the baseline fails before dataset-ready evidence can be marked passed

### Requirement: Required CI checks
CI SHALL run GUI lint, production GUI build, GUI RPC/state tests, explicit server core tests, and dataset conformance as required jobs independent of optional packaging success.

#### Scenario: Core tests fail while packaging succeeds
- **WHEN** a server core test fails but a desktop or Android package can still be assembled
- **THEN** the CI workflow remains failed and cannot report the foundation baseline as successful

### Requirement: Evidence-specific verification
The dataset-ready report SHALL mark each evidence category passed only from tests or commands that directly exercise that category.

#### Scenario: Numerical tests absent
- **WHEN** general server tests pass but exhaustive FP16 tests are missing, skipped, or fail
- **THEN** numerical evidence remains failed and the dataset-ready report is not ready
