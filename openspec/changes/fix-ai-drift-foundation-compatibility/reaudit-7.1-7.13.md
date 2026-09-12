# Re-audit of `complete-ai-drift-data-pipeline` tasks 7.1–7.13

Re-audited against the connected command path and retained executable evidence on
2026-09-11. The original checkboxes remain complete only for the evidence below.

| Task | Status | Direct evidence |
| --- | --- | --- |
| 7.1 | complete | `ml/requirements.lock`, `verify_lock.py`, `test_dependency_lock.py`, `test_config.py`, CLI help, and `ml/README.md` cover the supported deterministic workspace and licensed setup. |
| 7.2 | complete | `test_amass.py` covers structural SMPL mapping, canonical root/HMD reference, units, resampling, coordinate transforms, and the opt-in licensed integration. |
| 7.3 | complete | `test_layouts.py` and the E2E fixture execute masked preset/additional layouts through preparation and training. |
| 7.4 | complete | `test_simulation.py` covers seeded mounting/noise/bias/random-walk/temperature/latency/loss/stale/reset simulation; the E2E fixture consumes simulation output. |
| 7.5 | complete | `test_sessions.py`, `test_preparation.py`, and the E2E real-session fixture cover validated archive loading, reset windows, provenance/quality masks, and source hashes. |
| 7.6 | complete | `test_workflow.py` and `test_pipeline_e2e.py` prove group assignment precedes window extraction, forbidden overlap/empty holdouts fail, and normalization membership is training-only. |
| 7.7 | complete | `test_losses.py`, `test_training.py`, and the connected train run prove dense/sparse/temporal/self-supervised/domain/axis/provenance/quality masks enter the optimized global loss. |
| 7.8 | complete | `test_model.py`, `test_training.py`, and ONNX parity cover the shared causal encoder, global context, bounded per-slot heads, masks, and variable layouts. |
| 7.9 | complete | `test_evaluation.py` and the E2E `evaluate` command load a checkpoint, replay holdouts, and derive the declared safety/reset/cohort metrics plus named baselines. |
| 7.10 | complete | run manifest schema v2 records source/group/window/loss/feature/checkpoint/evaluation/export lineage; `test_provenance.py` and E2E repeat comparison enforce exact identities and the documented metric tolerance. |
| 7.11 | complete | `test_features.py`, `test_workflow.py`, and retained `feature-qualification.json` cover training-only contextual normalization, missingness, ablation, and unseen device/session decisions. |
| 7.12 | complete | `test_promotion.py` covers all eight named activities and per-cohort regression failure; E2E promotion consumes checkpoint evaluation, parity, feature, layout/domain/hardware coverage, and non-regression evidence. |
| 7.13 | complete | the only repository base advertised as personalization-ready, `nekovr-small-v1`, has hash-bound training/evaluation/optimizer/nominal/base-checkpoint/worker-request artifacts; `test_personalization.py` and E2E execute one portable worker step and verify the frozen backbone. |

The deterministic connected evidence is `ml/fixtures/pipeline-e2e-v1.json` plus
`ml/tests/test_pipeline_e2e.py`. It does not manufacture licensed AMASS bytes or
physical-session evidence; those remain external opt-in inputs as designed.
