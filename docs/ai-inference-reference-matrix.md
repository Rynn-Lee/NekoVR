# AI inference reference matrix

The small-tier release gate uses an immutable host identifier and the 10-tracker, 60-frame scenario. Results from faster developer machines are useful diagnostics but cannot satisfy a reference-host gate.

| Host ID | Role | Operating system | CPU / memory | GPU / driver and runtime |
| --- | --- | --- | --- | --- |
| `weak-win-cpu-i3-8100-v1` | Required fallback CPU | Windows 10 22H2 or Windows 11 x64 | Intel Core i3-8100, 4 cores/4 threads at 3.6 GHz, 16 GiB DDR4-2400 | CPU EP |
| `weak-linux-cpu-i3-8100-v1` | Required fallback CPU | Ubuntu 22.04 x64 | Intel Core i3-8100, 4 cores/4 threads at 3.6 GHz, 16 GiB DDR4-2400 | CPU EP |
| `entry-win-cuda-gtx1650-v1` | Required entry GPU | Windows 11 x64 | Intel Core i3-8100, 16 GiB DDR4-2400 | GeForce GTX 1650 4 GiB, NVIDIA R580+, CUDA 13, cuDNN 9, CUDA EP |
| `entry-linux-cuda-gtx1650-v1` | Required entry GPU | Ubuntu 22.04 x64 | Intel Core i3-8100, 16 GiB DDR4-2400 | GeForce GTX 1650 4 GiB, NVIDIA R580+, CUDA 13, cuDNN 9, CUDA EP |
| `compat-win-directml-uhd630-v1` | Compatibility-only GPU | Windows 10 22H2 x64 | Intel Core i3-8100, 16 GiB DDR4-2400 | Intel UHD 630, current OEM DCH driver, DirectML EP |

The i3-8100 is intentionally old, lacks Turbo Boost, and exposes AVX2. The GTX 1650 is the entry discrete target and has 4 GiB memory. ONNX Runtime 1.29 GPU packages use CUDA 13.0 and cuDNN 9; CUDA 13 requires an R580-or-newer NVIDIA driver. DirectML requires a Direct3D 12 device on Windows 10 or newer and is a compatibility target until its own performance policy is approved.

Each required row must run the committed `nekovr-small-benchmark-fixture` with 100 warm-up inferences, at least 1,000 measured submissions at 100 Hz, and `-PaiBenchmarkTrackers=10 -PaiBenchmarkContexts=60`. Evidence must retain the generated JSON and set the exact `aiBenchmarkHostId`. CPU and GPU evidence are evaluated separately; a result from another host ID is not interchangeable.

The repository currently contains `docs/evidence/ai-inference/development-windows-cpu.json`, recorded on a Ryzen 7 5700X development host. It validates the pipeline but is explicitly not weak-hardware release evidence.

Sources for the matrix and runtime compatibility:

- Intel i3-8100 specifications: <https://www.intel.com/content/www/us/en/products/sku/126688/intel-core-i38100-processor-6m-cache-3-60-ghz/specifications.html>
- NVIDIA GTX 1650 specifications: <https://www.nvidia.com/en-us/geforce/graphics-cards/compare/>
- ONNX Runtime CUDA requirements: <https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html>
- CUDA 13 driver compatibility: <https://docs.nvidia.com/cuda/archive/13.0.0/cuda-toolkit-release-notes/index.html>
- DirectML requirements: <https://learn.microsoft.com/en-us/windows/win32/api/directml/nf-directml-dmlcreatedevice>
