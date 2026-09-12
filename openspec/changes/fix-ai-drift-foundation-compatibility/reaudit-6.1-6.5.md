# Re-audit of `complete-ai-drift-data-pipeline` tasks 6.1–6.5

Re-audited on 2026-09-11 against direct category results, retained simulated
pilot archives, runtime gate evaluation, and mounted renderer/server tests. A
checked item is retained only where the evidence below directly demonstrates
its contract. Physical pilot evidence is not manufactured or inferred from the
simulated archives.

| Original task | Direct evidence | Decision |
| --- | --- | --- |
| 6.1 readiness aggregation | `test_validate_ready_evidence.py` proves that every required category maps to a distinct Gradle check and a distinct hash-bound machine-readable artifact. `DatasetReadyGateTests` validates the resulting report schema and required evidence IDs. | Remains complete |
| 6.2 simulated and real pilots | The deterministic 5-tracker UDP and 8-tracker mixed-transport simulated pilots are generated and consumed by both readers. `DatasetReadyGateTests.testMissingArtifactsAndPhysicalRealPilotAreRequired` proves these cannot substitute for a qualifying physical real pilot. No physical pilot artifact is present. | Remains incomplete |
| 6.3 Python pilot agreement | The dataset conformance baseline loads both simulated pilots through the public Python reader and compares roster, transport, channel, quality, frame, and valid-reset-window results with Kotlin reports and retained archive hashes. | Remains complete |
| 6.4 persisted server gate and UI | `DatasetReadyGateTests` covers accepted, copied, forged, stale, duplicate, dirty/wrong-build, missing, and mutated reports. `DatasetRPCHandlerTests.testHigherFidelityProfilesRemainLocked` rejects a direct production RPC after evidence bytes change; `testProductionProfileStartsOnlyAfterDatasetReadyGatePasses` covers acceptance. The mounted `dataset-recorder-widget.test.tsx` renders the same rejected/accepted server reasons and only enables the production selection after the authoritative accepted status. | Remains complete |
| 6.5 collection protocol | `docs/datasets/collection-protocol.md` documents consent/privacy, reset procedure, acceptance criteria, physical pilot requirements, and unsupported prototype handling; canonical field semantics remain in `docs/datasets/canonical-contract-v1.md`. | Remains complete |

Verification commands used for this re-audit:

```text
./gradlew :server:core:test --tests dev.slimevr.unit.DatasetRPCHandlerTests
gui/node_modules/.bin/vitest run --config gui/vitest.config.ts gui/src/tests/dataset-recorder-widget.test.tsx
```

Both commands passed. Original task 6.2 intentionally remains unchecked until
actual qualifying physical pilot archives are supplied, validated, and hashed.
