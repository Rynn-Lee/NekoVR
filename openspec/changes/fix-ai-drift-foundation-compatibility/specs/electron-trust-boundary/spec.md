## ADDED Requirements

### Requirement: Typed IPC channel consistency
Electron main and preload code SHALL register and invoke IPC exclusively through the shared typed channel contract.

#### Scenario: Contract drift
- **WHEN** a channel is renamed, removed, or its argument type changes
- **THEN** type checking or a contract test fails for every inconsistent main/preload registration

### Requirement: Canonical filesystem authorization
Electron filesystem actions SHALL authorize canonical targets against canonical managed roots and SHALL reject traversal, symlink, junction, non-regular archive, and unsupported target-type escapes.

#### Scenario: Symlink beneath allowed root
- **WHEN** a renderer requests a lexical path beneath an allowed root that resolves through a symlink or junction outside that root
- **THEN** Electron rejects the operation without opening, revealing, copying, or returning the external target

#### Scenario: Managed dataset action
- **WHEN** a server-authorized session ID identifies a regular `.nvrdata` file directly beneath the canonical dataset root
- **THEN** reveal or export operates on that canonical file without accepting a renderer-supplied source path

### Requirement: Deny untrusted renderer navigation
The Electron window SHALL deny external top-level navigation and new-window creation by default and SHALL route approved external URLs through one centralized scheme/host/path policy.

#### Scenario: Remote navigation attempt
- **WHEN** renderer content attempts to navigate the application window or open a new window to an unapproved URL
- **THEN** navigation is denied and the remote document never receives the preload bridge

#### Scenario: Approved external URL
- **WHEN** the renderer invokes the typed external-open action with an allowlisted URL
- **THEN** Electron opens it outside the application window and rejects URLs that only spoof an allowed prefix
