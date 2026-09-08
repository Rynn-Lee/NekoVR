# Personal training metadata and local storage

Personal training state is stored locally below a server-managed root using schema version 1. Each profile has an isolated namespace:

```text
profiles/<profile-id>/
  profile.json
  jobs/<job-id>.json
  checkpoints/<job-id>/<checkpoint-id>.json
  cache/<cache-id>.json
  models/<model-sha256>.json
```

The five metadata formats are `nekovr-personal-profile-v1`, `nekovr-personal-job-v1`, `nekovr-personal-checkpoint-v1`, `nekovr-personal-cache-v1`, and `nekovr-personal-model-v1`. Unknown schema versions fail closed.

Profiles use generated local pseudonymous IDs. They contain an optional local display alias, bounded body proportions, canonical body-role layout history, aggregate sensor-family names, and technical provenance. They do not define fields for accounts, email addresses, network addresses, MAC addresses, hardware IDs, or device serial numbers.

Jobs, checkpoints, caches, and models are owned by exactly one profile. Their lineage uses SHA-256 references for the base model, feature schema, selected sessions, settings, preprocessing, split manifest, checkpoint, metrics, model, sidecar, and source artifacts. Compatibility records declare canonical body-role sets, layouts, and aggregate sensor families.

Artifact references must be relative, contained paths. Metadata filenames are derived only from validated IDs or hashes; symbolic-link storage paths, traversal, non-finite body values, malformed hashes, and cross-profile lookup all fail. Writes use a private temporary file followed by atomic replacement when the filesystem supports it. POSIX storage receives owner-only permissions where supported. No upload or network operation is part of this store.
