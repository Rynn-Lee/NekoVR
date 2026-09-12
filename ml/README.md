# NekoVR ML workspace

This project prepares licensed AMASS/SMPL motion and canonical `.nvrdata`
sessions for model training. It does **not** contain or redistribute AMASS,
SMPL, SMPL-H, or SMPL-X assets.

## Reproducible setup

The supported interpreter range is CPython 3.11–3.14. Install only the fully
resolved, SHA-256-locked closure:

```text
python -m venv .venv
.venv/Scripts/python -m pip install --require-hashes --requirement requirements.lock
.venv/Scripts/python -m pip install --no-deps --editable .
.venv/Scripts/python -m pytest
```

On POSIX systems replace `.venv/Scripts/python` with `.venv/bin/python`.
Configuration is JSON, versioned, validated, and defaults to
`configs/default.json`. Commands record its canonical SHA-256 and seed.

`requirements.in` contains direct inputs; `requirements.lock` contains every
resolved build/runtime/test dependency and every accepted distribution hash.
Regenerate it with `pip-compile --generate-hashes --allow-unsafe --strip-extras
--resolver=backtracking --output-file=requirements.lock requirements.in` from
the `ml/` directory. Verify two clean installs from the same staged wheelhouse
with `python verify_lock.py`. Its only online phase downloads hash-checked
wheels; both fresh environments install with `--no-index`, compare every
installed version to the lock, and must produce the same closure fingerprint.

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
The committed `fixtures/smpl-structural-v1.json` is a CC0 synthetic numeric
fixture, not an SMPL/AMASS asset. It verifies the 24-joint tree, pelvis root,
head/HMD reference, segment mapping, metre units, 25→50 Hz resampling, and
non-commuting transforms. Set `NEKOVR_AMASS_FIXTURE` and `NEKOVR_SMPL_MODEL`
to opt into the corresponding licensed-asset integration test.

## Commands

```text
nekovr-ml prepare-amass ...
nekovr-ml prepare-sessions session.nvrdata --output session.json
nekovr-ml prepare --amass sequence.npz --body-model SMPL_NEUTRAL.npz \
  --archive session.nvrdata --layout 5 --layout 10 --output training-input.json
nekovr-ml train --input training-input.json --output-dir run \
  --config configs/default.json
nekovr-ml evaluate --input training-input.json \
  --checkpoint run/model-checkpoint.json --output-dir evaluation \
  --config configs/default.json
nekovr-ml export-onnx --checkpoint run/model-checkpoint.json \
  --metadata export-metadata.json --output run/model.onnx \
  --config configs/default.json
nekovr-ml validate-onnx --model run/model.onnx \
  --config configs/default.json
nekovr-ml parity --input training-input.json \
  --checkpoint run/model-checkpoint.json --model run/model.onnx \
  --sidecar run/model.onnx.json --output run/onnx-parity.json
nekovr-ml promote --model run/model.onnx --sidecar run/model.onnx.json \
  --evaluation evaluation/metrics.json --parity run/onnx-parity.json \
  --feature-qualification run/feature-qualification.json \
  --output run/promotion-decision.json --bundle-root run/model-bundles
nekovr-ml publish-catalog \
  --bundle run/model-bundles/<bundle-sha256> --catalog models/catalog.json
nekovr-ml compare-runs run-a/run.json run-b/run.json \
  --metric-absolute-tolerance 1e-9 --output repeat-comparison.json
nekovr-ml generate-probe --output-dir artifacts/onnx-probe-v1 \
  --config configs/default.json
nekovr-trainer-worker --ipc-stdio
```

`train` accepts only the canonical preparation contract. It assigns immutable
source/subject/session/device groups before extracting temporal windows,
rejects leakage and empty holdouts, fits normalization from training groups,
and persists the exact split audit and normalization membership. The global
optimizer consumes dense synthetic, sparse real-reset, temporal,
self-supervised, domain, axis, provenance, and quality masks while updating the
shared encoder and all global heads. Frozen-backbone training remains confined
to `nekovr-trainer-worker` personalization.

Contextual features are normalized from training groups, augmented for
missingness, ablated one at a time, and checked on unseen device/session
cohorts. `feature-qualification.json` and `run.json` retain every inclusion or
exclusion reason. `evaluate` loads the checkpoint and replays only rebuilt
validation/test windows; caller-created prediction records are not accepted.
It records candidate predictions, identity and legacy-yaw baselines, safety and
cohort metrics, executed reset rate, time to first reset, and no-reset
intervals, bound to checkpoint/input/split/normalization hashes. `parity`
derives ONNX evidence from the same checkpoint and holdouts. `promote` derives
its decision from the retained evaluation, parity, feature, activity, layout,
domain, hardware, and non-regression evidence files and writes their hashes;
it has no pass/fail evidence flags on the command line.

Run-manifest schema v2 retains every source motion/archive, immutable group and
window assignment, loss summary, feature decision, checkpoint, evaluation, and
exported-artifact identity. `compare-runs` requires exact configuration,
environment, source, split, normalization, lineage, and artifact hashes;
numeric metrics alone use the documented absolute tolerance (default `1e-9`).
The deterministic pipeline fixture executes the complete chain twice and
applies this same repeatability policy.

`prepare` is the sole canonical example-preparation boundary. It can ingest
licensed AMASS/SMPL motion, validated real `.nvrdata` archives, or both. AMASS
passes through canonical kinematics, selected layouts, and deterministic sensor
simulation; real archives pass through the canonical validator and reset-window
decoder. Both domains emit the same named 16-feature contract with source/body
hashes, slot/channel/domain masks, reset ranges, target masks, and explicit
include/downweight/exclude quality decisions. Split remains `unassigned` until
the grouped splitter assigns whole source groups.

## ONNX artifact contract

`export-onnx` writes the model and an adjacent `model.onnx.json` sidecar. The
versioned sidecar records the feature-schema hash, exact tensor names/types and
symbolic shapes, training-only normalization, supported role IDs, slot/context
bounds, output transform convention, run/dataset provenance, validation
metrics, opset, performance tier, byte size, and model SHA-256. `validate-onnx`
derives the canonical input/output contract from trusted code rather than the
sidecar, requires default-domain opset 18, checks feature/output widths,
symbolic dimensions, semantics, embedded output/role bounds and provenance
hashes, and executes every declared context/slot boundary on CPU.

The metadata input is JSON with `model_id`, `model_version`, `feature_schema`,
`normalization`, `supported_roles`, `slot_bounds`, `context_bounds`,
`provenance`, optional `validation_metrics`, and optional `performance_tier`.
Provenance must include `seed`, `config_sha256`, `source_commit`, and
`dataset_hashes`. Histories are left-padded, the rightmost frame is valid, and
time/slot/channel masks are authoritative; masked slots produce zero outputs.

Parity covers minimum/maximum context, slots and supported batch sizes, all
outputs, masks, invalid shapes/role IDs, and exact same/cross-process repeats.
Promotion derives separate hash-bound safety, quality, and cohort reports. A
passing promotion atomically renames one content-addressed directory containing
model, sidecar, parity, validation metrics, promotion, safety, quality, cohort,
and manifest bytes. `publish-catalog` reloads and hashes that complete bundle,
recomputes retained validation metrics, and never accepts caller-created gate
booleans. Failed or incomplete candidates cannot modify the catalog.

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

The personalization base identity is a canonical hash of the model
configuration and every parameter value, not merely tensor names or shapes.
Each eligible catalog bundle contains the full base checkpoint, worker request,
probe input, training/evaluation graphs, optimizer state, and nominal
checkpoint. Their hashes are bound by the manifest and exercised by the same
portable worker backend used for local training.

Every epoch rechecks the frozen-backbone fingerprint and finite correction,
clean-motion false-correction, and temporal-jitter limits. Best checkpoints are
saved atomically and bounded early stopping terminates non-improving runs.
