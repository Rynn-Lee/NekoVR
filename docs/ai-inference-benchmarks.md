# AI inference benchmark harness

The `:server:core:aiInferenceBenchmark` task runs the real packaged ONNX Runtime session through the same bounded latest-value worker used by the server. It writes a versioned JSON report without applying release thresholds; reference hardware and pass/fail policy are defined by later inference-ready tasks.

With no model arguments, the command uses the packaged probe and exercises its minimum and maximum tracker/context bounds:

```powershell
.\gradlew.bat :server:core:aiInferenceBenchmark
```

To benchmark a candidate model and an explicit matrix:

```powershell
.\gradlew.bat :server:core:aiInferenceBenchmark `
  -PonnxRuntimeFlavor=nvidia `
  -PaiBenchmarkProvider=CUDA `
  -PaiBenchmarkModel=C:\models\candidate.onnx `
  -PaiBenchmarkSidecar=C:\models\candidate.onnx.json `
  -PaiBenchmarkTrackers=5,6,8,10 `
  -PaiBenchmarkContexts=30,60 `
  -PaiBenchmarkIterations=1000 `
  -PaiBenchmarkWarmup=100 `
  -PaiBenchmarkSubmissionHz=100 `
  -PaiBenchmarkHostId=entry-win-cuda-gtx1650-v1
```

`aiBenchmarkOutput` overrides the default `server/core/build/reports/ai-inference/benchmark.json` destination. Every tracker-count/context-size scenario reports:

- server-thread `submit` p50/p95/p99/max duration;
- actual ONNX inference p50/p95/p99/max duration;
- queue wait percentiles, drops, maximum/final depth, and whether the queue drained;
- JVM process and system CPU utilization;
- heap, non-heap, and committed virtual memory;
- device GPU utilization and Java-process GPU memory for CUDA/TensorRT when `nvidia-smi` is available.

Unavailable GPU counters are represented by `gpuUnavailableReason`; they are never replaced with browser GPU or WebGL capability. DirectML benchmarks currently require an external platform sampler for GPU counters, while inference latency and process resource measurements remain available.

The release gate requires the small-tier 10-tracker/60-frame scenario, a drained bounded queue, CPU p95 at or below 4 ms, or GPU p95 at or below 1 ms with no more than 128 MiB incremental process GPU memory. Latest-value replacements remain reported because they are expected when producers briefly outrun inference; a queue which does not drain still fails. Pin the evidence to the expected physical host with both properties:

```powershell
.\gradlew.bat :server:core:aiInferenceBenchmarkGate `
  -PaiBenchmarkHostId=weak-win-cpu-i3-8100-v1 `
  -PaiBenchmarkReferenceHostId=weak-win-cpu-i3-8100-v1 `
  -PaiBenchmarkIterations=1000 `
  -PaiBenchmarkWarmup=100
```

The gate fails closed when the model is not `small`, the host ID differs, the exact scenario is absent, GPU memory evidence is unavailable, or any latency/memory/queue limit is exceeded.
