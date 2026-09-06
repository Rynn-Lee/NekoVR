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
nekovr-ml export-onnx --config configs/default.json
nekovr-ml validate-onnx --config configs/default.json
```

`train` accepts a versioned `nekovr-training-input-v1` JSON document, trains
only the bounded adapter heads over the frozen shared causal encoder, and
writes a framework checkpoint, personalization bundle, and complete `run.json`
provenance manifest. `evaluate` accepts `nekovr-evaluation-input-v1` and emits
overall plus layout/domain/person/chipset/transport/activity/drift cohort
metrics and matching provenance. ONNX export/validation remain explicit
unavailable commands until OpenSpec section 8 is implemented.
