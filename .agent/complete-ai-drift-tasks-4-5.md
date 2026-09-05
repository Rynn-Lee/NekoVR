You are implementing OpenSpec change `complete-ai-drift-data-pipeline` in repository S:\Projects\NekoVR.

Use model-quality engineering judgment and work directly in the shared workspace. Read ALL change context first:
- openspec/changes/complete-ai-drift-data-pipeline/proposal.md
- openspec/changes/complete-ai-drift-data-pipeline/design.md
- every spec under openspec/changes/complete-ai-drift-data-pipeline/specs/
- openspec/changes/complete-ai-drift-data-pipeline/tasks.md

Implement ONLY tasks 4.1 through 4.8 and 5.1 through 5.7, end-to-end. Do not implement or mark any 6.* or later task. Preserve all existing dirty-worktree changes from tasks 1-3 and unrelated user work. Do not use destructive git commands, reset, checkout, or commit.

Non-negotiable requirements for 4.*:
- Inject a typed reset event publisher; no VRServer singleton dependency in reset logic.
- Emit request plus cancelled/failed/applied outcomes with request/application monotonic times, source, reset kind and affected body parts.
- Capture each affected physical IMU independently: raw orientation, calibrated/pre-AI orientation, complete adjustment chain, HMD reference and validity, before and after yaw/full/mounting reset.
- Compute normalized `q_target = q_post_adjusted * inverse(q_pre_adjusted)` with q/-q equivalence, diagnostic yaw, and correct axis masks.
- Link configurable pre/post frame-index windows and mark truncation at session boundaries.
- Compute machine-readable quality flags for invalid/stale HMD, excess motion, packet gaps, reconnect/reassignment, invalid quaternions, overlapping resets and insufficient context.
- Keep yaw/full/mounting domains separate and provide deterministic include/downweight/exclude policy.
- Increment per-tracker reset/calibration epochs and synchronously clear/rebase legacy drift, AI history/pending result/applied correction so old results cannot return.
- Add meaningful fixtures/tests for wraparound, sign equivalence, known transform, partial/multi-tracker, delayed, invalid-reference and mounting reset cases.

Non-negotiable requirements for 5.*:
- Extend the real SolarXR FlatBuffers RPC schema with recorder start/stop-finalize/cancel/status/list/validate/recover/delete/export/reveal, authoritative events, readiness findings, inventory, progress/counters and typed errors. Preserve compatibility and regenerate both Java and TypeScript bindings using the repository's normal generator/build flow; do not hand-maintain generated protocol code unless that is the established repository process.
- Add server handlers with request/session IDs, ordered authoritative state visible to all clients, reconnect/multi-client behavior, and operations contained to canonical resolved managed dataset roots.
- Replace DatasetRecorderWidget browser interval sampling, dataset `localStorage`, `zipBuilder`, and empty/reconstructed downloads with RPC hooks/server state. Other unrelated localStorage usage may remain.
- UI must show readiness, elapsed/write/queue/drop/reset/disk/finalization metrics, roster, validation errors, and actual server archive inventory.
- Reveal/export/delete/recover must use typed Electron IPC and canonical/resolved containment. Renderer-provided arbitrary paths and unsafe URLs must be rejected. Keep existing URL/path allowlists intact.
- Add English and Russian Fluent resources for every new visible state/error. Use accessible existing design-system controls and supported variants.
- Add robust RPC state machine/reconnect/multi-client tests plus GUI end-to-end/component tests covering start/stop/finalize/reveal. Tests must exercise real control flow rather than tautological mocks.

Implementation constraints:
- Keep code cohesive and production-grade, not placeholders or TODO-only scaffolding.
- Do not fabricate readiness, archive inventory or metrics.
- Do not silently weaken any validation/security boundary.
- Reuse current DatasetRecordingService and canonical dataset models implemented in tasks 2-3.
- If protocol is a git submodule or generated artifact has an unusual workflow, inspect repository scripts and make the smallest correct changes available in this workspace.
- Mark each 4.* / 5.* checkbox in tasks.md only after its implementation and relevant tests are complete.

Validation required before finishing:
- relevant focused server tests
- full `:server:core:test`
- `pnpm -C gui lint`
- production GUI build
- any protocol generation/check task and new GUI tests

If an existing environmental limitation blocks a validation command, still finish all correct code, record the exact command/output, and do not claim that validation passed. At the end report: files changed, task-by-task mapping, exact commands/results, and any remaining concern. Stay on the task until implementation and validation are complete.
