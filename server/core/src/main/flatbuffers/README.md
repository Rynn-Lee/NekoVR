# Dataset bindings

`nekovr_dataset_v1.fbs` is the source of truth. The checked-in JVM binding at
`dev/slimevr/dataset/generated/DatasetV1Bindings.kt` and Python binding at
`dataset/python/nekovr_dataset/dataset_v1_generated.py` expose the same field
ordinals so server builds and offline validation do not require `flatc`.

When changing the schema, regenerate both bindings with a FlatBuffers compiler
compatible with 22.10.26, append fields only, and run the JVM/Python conformance
test before committing them.
