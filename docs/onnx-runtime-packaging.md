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

Distribution verification and installed-payload smoke tests are available with:

```text
gradlew :server:desktop:verifyInferenceDistributionContents
gradlew :server:desktop:installedInferenceSmoke
gradlew :server:desktop:jpackageImage
```

The CPU ONNX Runtime payload currently makes AI inference a supported packaged feature on Windows x64, Linux x64, Linux arm64, and macOS arm64. Windows arm64 and macOS x64 packages may still contain the rest of the application, but are not AI-inference targets until matching ONNX JNI natives are integrated; CI therefore does not claim inference smoke coverage for them. CI runs the offline managed-import/session/probe smoke both on the downloaded server payload and again from the Electron package output on every supported runner. `ThirdPartyNotices.txt`, the deterministic model and sidecar, generated RPC classes, GUI assets, server entry point, and native ONNX libraries are checked as distribution contents.

A forced provider failure is terminal for that activation request. `AUTO` tries
TensorRT, CUDA, DirectML, and CPU in order, records every failed attempt, and
activates only the first provider which successfully executes the probe.
