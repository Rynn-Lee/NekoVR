# Re-audit of `complete-ai-drift-data-pipeline` tasks 8.1–8.5

Re-audited against executable artifact publication and packaged-provider evidence on
2026-09-11. The original checkboxes remain complete only for the direct evidence below.

| Task | Status | Direct evidence |
| --- | --- | --- |
| 8.1 | complete | `onnx_export.py`, `model_metadata.py`, and `onnx_validation.py` emit and independently verify the versioned sidecar, hashes, tensor/normalization/role/bound/output contracts, provenance, metrics, opset, and size. Tampered and mutually consistent malicious model/sidecar tests prove the sidecar is not the authority for graph semantics. |
| 8.2 | complete | `test_onnx_export.py` loads the emitted small causal graph, enforces the canonical operator/opset contract, and exercises bounded dynamic batch, time, and slot dimensions plus invalid shapes and role IDs. |
| 8.3 | complete | parity evidence v2 covers minimum/maximum context and batch/slot bounds, every declared output, masks, invalid channels, layouts and permutations, and deterministic cross-process repeats; atomic publication hash-checks that generated report. |
| 8.4 | complete | `promotion.py` derives its decision from hash-bound parity, validation, evaluation, feature, coverage, safety, and quality reports inside the atomic bundle. `test_promotion.py` rejects changed evidence and partial staging before catalog publication. |
| 8.5 | complete | `onnx-probe-v1` contains the deterministic model, sidecar, expected-output fixture, and manifest. Python `verify_probe_bundle` and Kotlin `OnnxProbeBundle` authenticate every file and compare every output value within the committed tolerance; `onnxRuntimeProbe` executes that path for each requested packaged provider, and desktop distribution verification requires all four resources. |

The retained cross-language negative evidence covers tampered sidecars, rehashed malicious
model/sidecar pairs, changed evidence, finite-but-wrong expected outputs, unsupported
opsets, and incomplete bundles. A provider is reported available only after its real
session reproduces every committed output tensor.
