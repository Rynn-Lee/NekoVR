## ADDED Requirements

### Requirement: Streaming Zstandard interoperability
The canonical Python dataset reader SHALL decode Zstandard telemetry streams produced by the Kotlin writer regardless of whether the frame declares its decompressed content size, while enforcing bounded resource limits.

#### Scenario: Kotlin stream omits content size
- **WHEN** the Kotlin writer produces a canonical streaming Zstandard telemetry entry without a content-size header
- **THEN** the Python reader decodes every length-prefixed FlatBuffer record successfully within configured size limits

#### Scenario: Corrupt compressed stream
- **WHEN** a telemetry entry is truncated or contains invalid Zstandard data
- **THEN** the Python reader returns a `DatasetFormatError` and the dataset-ready command records failed Python evidence instead of terminating with an uncaught library exception

### Requirement: Full-field cross-language fixture
The repository SHALL contain a deterministic Kotlin-written conformance fixture whose Python verification covers every required v1 table and representative optional fields, numerical tolerances, and transform composition.

#### Scenario: Python reads Kotlin fixture
- **WHEN** the committed conformance test reads the Kotlin-written fixture through the public Python dataset reader
- **THEN** all header, roster, frame, native-channel, event, reset-label, quality, footer, validity, and provenance values match the expected fixture values

#### Scenario: Transform round trip
- **WHEN** the fixture contains non-commuting pre-reset and post-reset rotations
- **THEN** Python reconstructs `normalize(q_post * inverse(q_pre))` within the documented tolerance and does not substitute a yaw-only or reversed-order result

### Requirement: Reader schema coverage
The Kotlin and Python high-level readers SHALL expose or explicitly validate all required v1 fields used by validation and training; silently dropping a populated required field is forbidden.

#### Scenario: Populated native channels
- **WHEN** a tracker sample contains populated native channels and correction metadata
- **THEN** both language readers preserve channel IDs, timestamps, values, validity, provenance, and correction state
