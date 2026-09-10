# Re-audit of `complete-ai-drift-data-pipeline` tasks 1.1–2.9

Re-audited on 2026-09-09 against executable checks in this corrective change. The
existing checked state is retained for every item below; no item is inferred from
an umbrella task result alone.

| Task | Direct evidence used in the re-audit |
| --- | --- |
| 1.1 | `TrackerResetsHandler` injects `DriftCorrectionSource` with `IDENTITY` as its default/fallback; the forced `:server:core:test` run exercises reset and AI-correction safety tests without a `VRServer.instance` dependency in that handler. |
| 1.2 | `./gradlew :server:core:test --rerun-tasks` directly runs the reset, mounting, reference-adjustment, skeleton-reset, and tracking-pause suites. |
| 1.3 | `pnpm -C gui test` runs `electron-security.test.ts`, which compares the shared/main/preload IPC channel sets and exercises canonical path and deny-by-default URL/navigation policies. |
| 1.4 | Independent `pnpm -C gui lint` and `pnpm -C gui build` checks compile and build the affected GUI/Electron surface. |
| 1.5 | `.github/workflows/build.yml` has independent required jobs for GUI tests, GUI lint, production GUI build, forced server-core tests, and cross-language conformance. |
| 1.6 | `ai-model-control.test.ts`, `dataset-recorder.test.ts`, `DatasetReadyGateTests`, and `InferenceReadyGateTests` exercise the diagnostic/production readiness boundaries. |
| 2.1 | `DatasetConformanceFixtureTests`, `ResetLabelBindingsTests`, and the generated-binding readers exercise the versioned header, roster, frame, event, reset-label, quality, and footer records. |
| 2.2 | `test_kotlin_conformance_fixture_preserves_every_expected_field` checks the canonical coordinate, quaternion, unit, validity, and provenance fixture described by `docs/datasets/canonical-contract-v1.md`. |
| 2.3 | The aggregate baseline generates archives through the Kotlin bindings and decodes them through the public Python reader. |
| 2.4 | `FP16BinaryPackerTests` and `DatasetNumericalTests` directly cover all binary16 bit patterns, boundaries, non-finite rejection, range checks, and shared tolerances. |
| 2.5 | `DatasetConformanceFixtureTests` plus the Python conformance and full-report agreement tests validate Kotlin-writer/Python-reader round trips. |
| 2.6 | `DatasetArchiveValidatorTests` and `DatasetTelemetryChecksumTests` directly exercise manifest/schema compatibility, canonical ZIP layout, counters, checksums, and validator findings. |
| 2.7 | Kotlin `canonical container rejects extra compressed and prototype layouts` and Python `test_both_historical_prototypes_remain_structured_failures` cover both prototype formats. |
| 2.8 | `DatasetArchiveValidatorTests` exercises canonical registry semantics, descriptor collisions, required channels, and forward-compatible optional descriptors; conformance tests verify validity/provenance values. |
| 2.9 | `minimum standard and full profiles validate without fabricated optional samples`, the legacy-context conformance assertions, and generated standard/full pilot archives exercise profile compatibility and unavailable optional channels. |

Aggregate command used for the audit:

```text
node scripts/foundation-baseline.mjs
```
