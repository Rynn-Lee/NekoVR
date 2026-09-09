## ADDED Requirements

### Requirement: Exhaustive binary16 verification
The binary16 codec SHALL implement IEEE-754 round-to-nearest-even conversion and SHALL be exhaustively tested across all 65,536 encoded bit patterns plus representative Float32 boundary and tie cases.

#### Scenario: All binary16 patterns
- **WHEN** the exhaustive codec test decodes every binary16 bit pattern and re-encodes finite canonical values
- **THEN** signed zero, subnormal, normal, infinity, and NaN classes are preserved according to the documented canonicalization policy

#### Scenario: Range and tolerance boundaries
- **WHEN** values at, inside, and outside each declared channel range and quantization tolerance are encoded
- **THEN** valid values round within tolerance while non-finite, overflowing, or out-of-range values are rejected

### Requirement: Checked dataset decoding
Dataset-facing Kotlin and Python readers SHALL reject non-finite or out-of-range FP16 features and invalid quaternion norms before returning valid samples.

#### Scenario: Encoded infinity or NaN
- **WHEN** a canonical feature field contains a binary16 infinity or NaN bit pattern
- **THEN** both validators report a fatal numerical finding and do not expose the field as a valid measurement

#### Scenario: Invalid quaternion
- **WHEN** decoded quaternion components are finite but violate the documented normalization tolerance
- **THEN** the sample is rejected or marked invalid consistently in Kotlin and Python

### Requirement: Complete archive validation
Both validators SHALL verify canonical ZIP membership, compatible schema semantics, record ordering, manifest/header agreement, channel descriptors, terminal footer completeness, counters, checksum scopes, and collection-profile rules.

#### Scenario: Required channel semantic collision
- **WHEN** a known channel ID is declared with a different unit, frame, cadence, precision, profile, validity, or allowed provenance
- **THEN** both validators reject the archive as semantically incompatible

#### Scenario: Unknown optional channel
- **WHEN** an archive adds a unique optional channel whose descriptor is complete and does not alter required semantics
- **THEN** an older compatible reader skips its values without misaligning known fields

#### Scenario: Footer mismatch
- **WHEN** footer completeness, quality counters, checksum, or manifest values disagree with decoded telemetry
- **THEN** both validators produce a fatal finding identifying the mismatched contract field

#### Scenario: Extra or duplicate ZIP member
- **WHEN** a `.nvrdata` container has an unexpected, duplicate, absolute, or traversal member name
- **THEN** validation fails before extracting or trusting the archive

### Requirement: Explicit prototype rejection
The validators SHALL continue to recognize both historical prototype layouts and SHALL return structured unsupported-format findings with the irrecoverable missing semantics.

#### Scenario: Historical prototype input
- **WHEN** an archive contains `telemetry.bin` or the unversioned `telemetry.zst` layout
- **THEN** Kotlin and Python classify the matching prototype explicitly and never treat it as canonical or training-ready

### Requirement: Legacy profile semantics
Minimum and standard profiles SHALL permit unavailable optional legacy hardware channels only when validity and provenance explicitly represent missingness; full-fidelity SHALL reject missing required capabilities.

#### Scenario: Legacy tracker in standard profile
- **WHEN** a legacy tracker lacks an optional native sensor channel in a standard recording
- **THEN** the archive remains compatible only if that channel is marked unavailable and no fabricated zero measurement is emitted
