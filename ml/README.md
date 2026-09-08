# NekoVR ML workspace

This project prepares licensed AMASS/SMPL motion and canonical `.nvrdata`
sessions for model training. It does **not** contain or redistribute AMASS,
SMPL, SMPL-H, or SMPL-X assets.

## Reproducible setup

Use Python 3.11 or newer in a fresh virtual environment:

```text
python -m venv .venv
.venv/Scripts/python -m pip install --requirement requirements.lock
.venv/Scripts/python -m pip install --no-deps --editable .
.venv/Scripts/python -m pytest
```

On POSIX systems replace `.venv/Scripts/python` with `.venv/bin/python`.
Configuration is JSON, versioned, validated, and defaults to
`configs/default.json`. Commands record its canonical SHA-256 and seed.

## Licensed AMASS and SMPL assets

1. Register for AMASS and the matching SMPL-family body model from their
   official providers and accept their licenses.
2. Download AMASS motion `.npz` files and a compatible neutral SMPL model
   `.npz`. Do not commit either asset to this repository.
3. Pass both paths explicitly. Missing or incompatible assets fail before an
   output file is created:

```text
nekovr-prepare-amass --amass /licensed/AMASS/sequence.npz \
  --body-model /licensed/SMPL/SMPL_NEUTRAL.npz --output prepared.json
```

The output records source paths only as file names, SHA-256 hashes, declared
license identifiers, sample rate, coordinate contract, and conversion
version. Restricted source arrays are never copied into repository fixtures.

## Commands

```text
nekovr-ml prepare-amass ...
nekovr-ml prepare-sessions session.nvrdata --output session.json
nekovr-ml train --config configs/default.json
nekovr-ml evaluate --config configs/default.json
nekovr-ml export-onnx --checkpoint run/model-checkpoint.json \
  --metadata export-metadata.json --output run/model.onnx \
  --config configs/default.json
nekovr-ml validate-onnx --model run/model.onnx \
  --config configs/default.json
nekovr-ml generate-probe --output-dir artifacts/onnx-probe-v1 \
  --config configs/default.json
nekovr-trainer-worker --ipc-stdio
```

`train` accepts a versioned `nekovr-training-input-v1` JSON document, trains
only the bounded adapter heads over the frozen shared causal encoder, and
writes a framework checkpoint, personalization bundle, and complete `run.json`
provenance manifest. `evaluate` accepts `nekovr-evaluation-input-v1` and emits
overall plus layout/domain/person/chipset/transport/activity/drift cohort
metrics and matching provenance.

## ONNX artifact contract

`export-onnx` writes the model and an adjacent `model.onnx.json` sidecar. The
versioned sidecar records the feature-schema hash, exact tensor names/types and
symbolic shapes, training-only normalization, supported role IDs, slot/context
bounds, output transform convention, run/dataset provenance, validation
metrics, opset, performance tier, byte size, and model SHA-256. `validate-onnx`
checks the sidecar against the bytes, limits the graph to the supported operator
set, verifies the tensor contract, and creates a CPU ONNX Runtime session.

The metadata input is JSON with `model_id`, `model_version`, `feature_schema`,
`normalization`, `supported_roles`, `slot_bounds`, `context_bounds`,
`provenance`, optional `validation_metrics`, and optional `performance_tier`.
Provenance must include `seed`, `config_sha256`, `source_commit`, and
`dataset_hashes`. Histories are left-padded, the rightmost frame is valid, and
time/slot/channel masks are authoritative; masked slots produce zero outputs.

Catalog publication is atomic and guarded by model integrity, the 15 MiB small
tier limit, maximum and p99 framework/runtime parity tolerances, finite and
bounded outputs, zero masked-slot outputs, required activity coverage, and
quality non-regression. Failed candidates never modify the catalog.

`generate-probe` reproducibly writes the tiny non-production ONNX model,
sidecar, fixed inputs/expected outputs, and per-file SHA-256 manifest used by
server and distribution provider probes.

## Local personal training

`personal_data.py` performs profile/base/schema/integrity/body-assignment and
quality eligibility analysis before splitting. Its report includes usable
duration and windows, excluded ranges, reset and clean evidence, body layouts,
sensor families, and all required activity cohorts. Production splits use the
two newest suitable whole sessions as test and validation holdouts. Training
windows are represented by small descriptors and streamed in deterministic
activity/layout/reset-versus-clean rounds; `seed`, epoch, and offset reproduce
the exact order without loading session telemetry into memory.

`nekovr-trainer-worker` is a JSON-lines, protocol-versioned local IPC process.
The controller verifies its executable SHA-256 and RSA signature before
launching it with `shell=false`. Checkpoints are ZIP archives written through a
temporary file and atomic replacement, and can restore the exact adapter state.
The worker validates every base training artifact hash and graph/optimizer
gradient contract. The portable baseline trains only the declared adapter on
CPU. Optional CUDA is exposed only when a packaged `onnxruntime.training` API
loads the signed native training/evaluation/optimizer/checkpoint artifacts and
successfully completes the bundle's probe optimizer step. DirectML inference
and DirectML training availability are always reported independently.

Every epoch rechecks the frozen-backbone fingerprint and finite correction,
clean-motion false-correction, and temporal-jitter limits. Best checkpoints are
saved atomically and bounded early stopping terminates non-improving runs.
