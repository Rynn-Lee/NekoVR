# ONNX Runtime packaging and provider verification

The server core pins ONNX Runtime `1.29.0` and selects one mutually exclusive
native package with the Gradle property `onnxRuntimeFlavor`:

| Flavor | Runtime payload | Providers eligible for probing |
| --- | --- | --- |
| `cpu` (default) | `com.microsoft.onnxruntime:onnxruntime` | CPU |
| `nvidia` | `com.microsoft.onnxruntime:onnxruntime_gpu` | TensorRT, CUDA, CPU |
| `directml` | Java API plus a distribution-supplied DML-enabled native runtime | DirectML, CPU |

The DirectML distribution must set `onnxruntime.native.path` before any ONNX
Runtime class is initialized. The directory must contain matching `onnxruntime`
and `onnxruntime4j_jni` libraries plus DirectML dependencies. A plain CPU JAR is
never reported as DirectML-capable. CUDA/TensorRT dependencies must likewise be
installed beside the GPU runtime according to its compatibility matrix.

Provider availability is not inferred from driver names, WebGL, or a successful
SessionOptions call. The engine checks the packaged flavor and native provider
registry, creates a session, validates its tensor contract, and runs the embedded
deterministic probe twice. Only then is that provider exposed as active.

The verification process uses the project's Java 17 runtime; running the native
probe with a different Gradle daemon JVM is not accepted as package evidence.

Verify the current package with:

```text
gradlew :server:core:onnxRuntimeProbe -PonnxRuntimeFlavor=cpu -PonnxProbeProvider=CPU
gradlew :server:core:onnxRuntimeProbe -PonnxRuntimeFlavor=nvidia -PonnxProbeProvider=CUDA
gradlew :server:core:onnxRuntimeProbe -PonnxRuntimeFlavor=nvidia -PonnxProbeProvider=TENSORRT
gradlew :server:core:onnxRuntimeProbe -PonnxRuntimeFlavor=directml -PonnxProbeProvider=DIRECTML
```

A forced provider failure is terminal for that activation request. `AUTO` tries
TensorRT, CUDA, DirectML, and CPU in order, records every failed attempt, and
activates only the first provider which successfully executes the probe.
