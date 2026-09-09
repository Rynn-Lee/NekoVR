## ADDED Requirements

### Requirement: Transactional model configuration
Configuration RPC SHALL validate a complete candidate against the active model and persist it before exposing the new effective state; any failure SHALL restore the prior runtime and persisted configuration.

#### Scenario: Context outside active model bounds
- **WHEN** a client requests a globally allowed context that exceeds the active model's declared maximum
- **THEN** the entire request is rejected and mappings, enablement, safety settings, and persisted configuration remain unchanged

### Requirement: Evidence-derived catalog compatibility
Catalog and history compatibility SHALL be derived from validated artifact integrity, feature schema, model/profile kind, mapping, context, provider/package, and readiness; unavailable metadata SHALL be reported as unverified rather than compatible.

#### Scenario: Remote model has not been downloaded
- **WHEN** only catalog summary fields are available
- **THEN** the server does not claim runtime compatibility until trusted metadata or the complete artifact is validated

### Requirement: Transactional switching and rollback
Switch and rollback SHALL serialize activation, configuration, history, and optional shadow validation while retaining a restorable previous model and compatible settings until commit.

#### Scenario: History persistence fails after probe
- **WHEN** the candidate passes its provider probe but durable history/configuration commit fails
- **THEN** the previous model and settings remain active and the candidate is reported failed

### Requirement: Authoritative provider and bound discovery
The control plane SHALL expose server-probed provider availability and active-model context/slot/role bounds so renderer choices are based on current executable state.

#### Scenario: DirectML is not packaged
- **WHEN** the current package cannot execute DirectML
- **THEN** the UI disables it or presents the server's precise unavailable reason and never implies compatibility from the platform name

### Requirement: Rendered end-to-end model workflow
Control-plane verification SHALL cover generated bindings, real handler responses/progress, reconnect behavior, and a mounted accessible Correction tab for import, download, activation, configuration, mapping, switching, and rollback.

#### Scenario: Client reconnects during model download
- **WHEN** the initiating renderer disconnects and another client reconnects
- **THEN** no duplicate download starts and the new client reconstructs authoritative progress, catalog, runtime, and history state
