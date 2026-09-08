# Inference-ready gate and active-correction opt-in

The server accepts one hash-pinned `nekovr-inference-ready-report-v1` for exactly one model SHA-256. Promotion requires all nine evidence categories:

- `parity`
- `provider-package`
- `layout`
- `watchdog`
- `safety`
- `quality`
- `baseline-regression`
- `performance`
- `shadow-dogfood`

Create a `nekovr-inference-ready-evidence-v1` manifest containing `buildCommit`, `modelSha256`, and one source entry per category. Every entry contains an ID, a path relative to the manifest, the source file SHA-256, and an optional detail. Each source JSON must expose a top-level boolean `passed`; when it exposes `modelSha256` or `model_sha256`, it must match the promoted model.

Generate the single report with:

```powershell
.\gradlew.bat :server:core:inferenceReadyReport `
  -PinferenceReadyEvidence=docs/evidence/inference-ready/evidence-manifest.json `
  -PinferenceReadyOutput=models/ai/inference-ready-report.json
```

Missing, duplicated, failed, path-escaping, hash-mismatched, malformed, or cross-model evidence fails closed. The output is written atomically. The runtime reads `models/ai/inference-ready-report.json` by default; `-Dnekovr.inferenceReadyReport=<path>` or `NEKOVR_INFERENCE_READY_REPORT` can select a different report.

Bounded active correction requires all of the following:

1. A valid inference-ready report for the active model hash.
2. Persisted user configuration with AI enabled and shadow mode disabled.
3. Explicit startup opt-in using `--enable-ai-correction` or `NEKOVR_ENABLE_AI_CORRECTION=1`.

Without any one condition, the engine returns identity, leaves legacy compensation composed, and exposes `ACTIVE_CORRECTION_OPT_IN_DISABLED`, `INFERENCE_READY_GATE_PENDING`, or `INFERENCE_READY_MODEL_MISMATCH`. Removing the runtime opt-in takes effect on the next correction read. The existing one-action disable control and `rollbackToIdentity()` synchronously disable correction, clear safety/live state, and return identity without modifying reset or calibration state.
