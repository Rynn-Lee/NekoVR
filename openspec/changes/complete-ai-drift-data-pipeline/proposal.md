## Why

The current AI drift and dataset code is a disconnected prototype: it does not execute an ONNX model, the UI does not control the server recorder, reset labels are not captured, required sensor channels and hardware metadata are missing, and long sessions are buffered entirely in memory. Collecting player sessions now would create data that cannot reliably supervise per-tracker correction or be reproduced by a training pipeline.

## What Changes

- Define and implement a versioned, streaming 50 Hz session format containing synchronized monotonic timestamps, HMD pose/reference validity, raw and reference-adjusted tracker orientations, linear acceleration, angular velocity, tracker validity, and reset/correction events.
- Capture every training-relevant signal available at its native cadence: raw/fused motion, temperature, magnetic field and calibration quality, packet sequence/loss/reordering/duplicates, RSSI, ping/jitter/sample age, battery/power state, firmware/sensor status, per-stage transforms, skeleton context, and explicit missingness/provenance. Extend firmware/protocols where required rather than silently fabricating unavailable channels.
- Snapshot immutable per-session tracker metadata, including stable device/tracker identity, body assignment, concrete IMU chipset, connection transport, firmware, coordinate conventions, units, and calibration state.
- Turn yaw/full/mounting resets into usable sparse labels by recording affected trackers and their pre-reset state, post-reset state, applied correction quaternion, source, timing, and label-quality window.
- Add a reproducible training toolchain that imports AMASS/SMPL motion, synthesizes configurable tracker layouts and IMU drift/noise/mounting errors, ingests real player sessions, prevents subject/session leakage, trains variable-layout causal models, evaluates safety/quality metrics, and exports validated ONNX artifacts plus model metadata.
- Replace the heuristic-only AI path with actual ONNX Runtime session loading, schema validation, causal history windows, tracker-slot masks/mappings, confidence gating, bounded correction application, telemetry, and fail-open behavior.
- Preserve raw, pre-AI, model prediction, applied correction, and final output as separate channels during corrected play so future training never mistakes the model's own output for sensor truth; collect rare user resets, clean no-reset intervals, posture/activity coverage, and model override events for counterfactual evaluation.
- Add server RPC/FlatBuffers contracts and a React control surface for dataset recording, session status/export, model selection/catalog download, provider selection, history/confidence settings, slot mapping, and runtime health.
- Add quick access to recent and pinned compatible models so a player can put on assigned trackers, select a global or personal model, enable correction, and play across dance, seated, standing, lying, crouching, and transition-heavy sessions with substantially fewer resets.
- Add a third AI Drift tab for local personal-model training. It selects a pre-trained NekoVR base model (not raw AMASS), validates multiple local sessions, fine-tunes a small personal adapter with visual staged progress, evaluates it against held-out sessions/activity cohorts, exports a lightweight ONNX model, and optionally activates it after passing safety gates.
- Package the required ONNX Runtime execution-provider binaries and verify CPU, CUDA, TensorRT, and Windows DirectML availability explicitly; never report an execution provider as active until a model session has successfully run on it.
- Add dataset conformance tests, reset-label tests, ONNX numerical/runtime tests, performance benchmarks, and end-to-end collection smoke tests as release gates before accepting production recordings.
- **BREAKING**: replace both prototype dataset layouts (`telemetry.bin` in the GUI and the unversioned server `telemetry.zst`) with one canonical versioned session schema and remove hard-coded fictional model catalog entries/preset behavior.

## Capabilities

### New Capabilities

- `motion-dataset-recording`: Canonical server-side, streaming, recoverable session capture with complete sensor channels, timing, metadata, validation, and export.
- `reset-supervision-labeling`: Per-tracker reset event capture and derivation of trustworthy sparse drift-correction labels.
- `motion-model-training`: Reproducible AMASS plus real-session preprocessing, simulation, training, evaluation, and ONNX export for variable tracker layouts.
- `onnx-drift-inference`: Validated, bounded, low-latency causal ONNX inference with provider fallback, tracker masks/mappings, confidence gating, and observability.
- `ai-drift-control-plane`: FlatBuffers/RPC and React workflows that control the real server recorder and inference engine, manage models, and expose truthful state/errors.
- `personal-model-training`: Local, resumable, resource-aware personalization of a validated global base model from selected player sessions, including progress, evaluation, ONNX export, profiles, and safe activation.

### Modified Capabilities

None. This repository has no existing OpenSpec capability specifications.

## Impact

- Server: `VRServer`, tracker sampling/reset paths, new dataset services, AI engine/configuration, lifecycle handling, RPC handlers, and persisted configuration.
- Protocol: new SolarXR FlatBuffers messages for recorder/model/runtime commands, status, metadata, and errors; regenerated Java/TypeScript bindings.
- GUI/Electron: AI Drift page and hooks, safe file/folder IPC, session list/export, model controls, localization, and packaging.
- ML tooling: a new Python training workspace with pinned dependencies, AMASS adapters, synthetic corruption generation, canonical dataset readers, training/evaluation/export commands, and reproducibility metadata.
- Personal trainer: optional signed ONNX Runtime Training artifacts/native worker, local preprocessing cache, frozen-backbone adapter checkpoints, resumable jobs, and personal-model/profile storage without mandatory cloud upload.
- Build/distribution: Gradle dependencies and native ONNX Runtime provider packaging, model metadata/catalog verification, jpackage/Electron integration, and CI performance/conformance gates.
- Compatibility: existing SlimeVR tracker layouts and Wi-Fi/HID/nRF-origin devices remain supported; models use explicit masks and metadata instead of fixed tracker-count presets.
