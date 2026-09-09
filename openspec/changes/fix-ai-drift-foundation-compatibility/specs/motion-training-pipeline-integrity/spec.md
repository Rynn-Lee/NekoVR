## ADDED Requirements

### Requirement: Connected canonical preparation and splitting
The supported ML command path SHALL convert licensed motion and validated real archives into one versioned canonical example contract, assign leakage groups before windowing, fit normalization from training groups only, and persist source hashes plus split audits.

#### Scenario: Related windows enter different requested splits
- **WHEN** windows share an AMASS subject/source or real user/session/device grouping
- **THEN** the pipeline keeps them in one compatible split or fails before training rather than trusting caller-provided split labels

### Requirement: Declared objectives drive optimization
Global training SHALL actually consume dense synthetic, sparse reset, temporal, self-supervised, domain, axis, provenance, and quality masks over temporal examples, and SHALL train the declared global model parameters.

#### Scenario: Real yaw-only reset
- **WHEN** a real yaw reset window is included in global training
- **THEN** its yaw sparse loss contributes according to quality policy while roll/pitch dense targets do not, and the contribution appears in run statistics

### Requirement: Context features require executable qualification
Contextual temperature, network, power, and hardware features SHALL enter a promoted model only through training-only normalization, missingness augmentation, ablation, and unseen-device/session evaluation executed by the supported workflow.

#### Scenario: Helper exists but workflow bypasses it
- **WHEN** training input names a contextual feature without a recorded qualification result from the current run
- **THEN** training or promotion rejects the feature rather than treating the helper implementation as evidence

### Requirement: Checkpoint-derived evaluation and promotion
Evaluation SHALL load the candidate checkpoint, generate predictions by replaying held-out inputs, compare named baselines, and derive hash-bound cohort, safety, reset-policy, and parity evidence consumed by promotion.

#### Scenario: Caller asserts successful promotion
- **WHEN** a caller supplies pass booleans without matching hashed evaluation and parity artifacts
- **THEN** the catalog is not modified

### Requirement: Actual model identity for personalization
A personalization-ready base identity SHALL include the actual model bytes or canonical parameter values, and every advertised ready catalog model SHALL have integrity-bound artifacts executable by the supported training worker.

#### Scenario: Same architecture with different weights
- **WHEN** two base models share configuration and parameter shapes but contain different weights
- **THEN** they have different base hashes and their personalization bundles cannot be interchanged

### Requirement: End-to-end reproducible ML evidence
The baseline SHALL include a deterministic small fixture that traverses preparation, splitting, normalization, global training, checkpoint evaluation, export/parity, promotion, and personalization artifact generation without injecting intermediate claims.

#### Scenario: Isolated helpers pass
- **WHEN** unit tests for split, loss, feature, evaluation, and promotion helpers pass but no connected run consumes their outputs
- **THEN** the end-to-end ML evidence remains failed
