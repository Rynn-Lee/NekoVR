## ADDED Requirements

### Requirement: Serialized recorder command lifecycle
Recorder RPC state mutations SHALL be serialized through a server-owned command boundary, SHALL reject unknown enum/argument values, and SHALL produce deterministic typed outcomes under concurrent clients.

#### Scenario: Concurrent stop requests
- **WHEN** two clients request finalization for the same active session
- **THEN** exactly one transition starts finalization and the other receives the authoritative typed state without starting a second writer operation

### Requirement: Interruptible finalization
Cancellation SHALL be observable by an in-flight finalization operation without waiting behind that operation on the same executor.

#### Scenario: Cancel while finalization is blocked
- **WHEN** finalization is waiting on writer or filesystem work and a valid cancel request arrives
- **THEN** the cancellation signal reaches the active operation, the terminal state is deterministic, and no later queued cancel is falsely reported as effective

### Requirement: Non-blocking dataset inventory
Archive discovery, validation, ZIP parsing, and full-file hashing SHALL NOT run synchronously on the protocol callback or tracking thread and SHALL use bounded work and coherently invalidated cached results.

#### Scenario: Multi-gigabyte inventory refresh
- **WHEN** a client refreshes an inventory containing long-session archives
- **THEN** RPC/status handling remains responsive while validation and hashes are computed asynchronously and unchanged archives reuse verified results

### Requirement: Executable renderer-to-server lifecycle evidence
Recorder verification SHALL cover generated protocol bindings, real handler state/broadcast behavior, and mounted renderer behavior for the production lifecycle.

#### Scenario: Record and finalize from the widget
- **WHEN** a mounted widget starts a session, receives live broadcasts, finalizes it, and requests reveal or export
- **THEN** the test observes authoritative server responses and path-free desktop authorization without substituting reducer-only fixtures
