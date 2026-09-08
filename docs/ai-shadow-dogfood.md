# AI shadow-mode dogfood

Shadow mode executes model inference, epoch/reset filtering, freshness/confidence checks, correction bounds, smoothing, stale decay, queue accounting, and watchdog handling without changing tracker output. Enable it in `vrconfig.yml`, then restart the server:

```yaml
aiDrift:
  schemaVersion: 1
  enabled: true
  shadowMode: true
```

While shadow mode is active, accepted candidates are exposed with `rejectionReason = SHADOW_MODE`, `correctionApplied = false`, and an identity tracking correction. Legacy drift compensation remains in compose mode. Rejected candidates retain their actual safety reason; runtime or watchdog failure also remains identity/fail-open.

Task 11.6 requires real tracker sessions rather than generated archives. Record at least one seated/low-motion and one standing/high-motion session for every supported 5/6/8/10-tracker layout. Each session must include normal resets plus a forced inference failure. Preserve the runtime-status capture and frame/reset archive together, and record:

- layout and activity cohort, duration, model hash, provider, and server build;
- candidate acceptance/rejection counts and every outlier/stale/watchdog reason;
- reset epoch boundaries and proof that pre-reset results were not reused;
- server tick/inference/queue percentiles before and during shadow mode;
- observed tracking regressions, resets per hour, and operator notes.

Pass requires zero applied AI corrections, zero non-identity tracking output attributable to AI, correct post-reset history invalidation, identity output throughout forced failure, no sustained queue growth, and no visible tracking/performance regression against the AI-disabled control session. Store signed-off reports under `docs/evidence/ai-shadow-dogfood/`; unit tests are supporting evidence but do not substitute for these sessions.

Encode the signed-off sessions as `nekovr-shadow-dogfood-v1` using the fields in `ShadowDogfoodReport`, then run:

```powershell
.\gradlew.bat :server:core:shadowDogfoodGate `
  -PshadowDogfoodEvidence=docs/evidence/ai-shadow-dogfood/report.json
```

The gate rejects `AUTOMATED_REPLAY` evidence as a replacement for `REAL_PLAYER`, missing layouts or activities, any applied/non-identity shadow correction, missing reset/late-epoch or forced-failure checks, a non-drained queue, tick p95 regression above 500 microseconds, or a reported visible regression.
