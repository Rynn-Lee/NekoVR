# NekoVR Trainer worker contract

Personal training is isolated from the tracking server. The server verifies an
installed `nekovr-trainer-package-v1` manifest and launches the declared worker
as a child process without a shell. The signed manifest contains the platform,
worker/protocol version, license, relative executable path, SHA-256, trusted
RSA key ID, and a PKCS#1 v1.5 SHA-256 signature over the canonical manifest.
Paths containing traversal or symbolic links are rejected.

The worker exchanges one versioned JSON object per line. Every message has
`kind`, `request_id`, `payload`, and `protocol_version`; unknown fields and
versions fail closed. Supported worker operations are `hello`, provider
`probe`, and `train`. Errors are typed responses and a worker exit cannot alter
the server's active inference model.

## Training artifact boundary

A personalization-ready base bundle declares immutable SHA-256 values for its
training graph, evaluation graph, optimizer, and nominal checkpoint. The graph
must enable gradients exactly for the declared adapter parameters; the
optimizer must contain the same trainable set and list the complete frozen
backbone. Parameter count/fraction and correction limits are enforced before
the first epoch.

Optional native ONNX Runtime Training bundles additionally map
`training_model`, `evaluation_model`, `optimizer_model`, `checkpoint`, and
`probe_batch`, plus ordered input/output tensor names. CPU/CUDA support is
reported only after the packaged Training API creates these objects, executes
the probe batch, produces finite output, and completes an optimizer step.
DirectML inference availability is displayed separately and never implies a
DirectML training backend.

The universal CPU baseline uses the same signed adapter declaration and never
updates the shared backbone. Native and portable paths both validate finite
epoch metrics, maximum correction, clean-motion false correction and temporal
jitter; they compare the frozen fingerprint after every epoch. Improvement is
subject to a minimum delta and patience. Only the best valid checkpoint is
written, using a durable temporary ZIP followed by atomic replacement. Resume
extracts only contained regular entries and rejects archive traversal.
