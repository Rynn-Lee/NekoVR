from __future__ import annotations

from dataclasses import asdict, dataclass
import base64
import hashlib
import hmac
import importlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from typing import Any, Callable, Iterable, Mapping, Protocol, Sequence
import zipfile

from .provenance import canonical_hash, file_sha256


IPC_PROTOCOL_VERSION = 1
RSA_SHA256_DIGEST_INFO = bytes.fromhex("3031300d060960864801650304020105000420")


@dataclass(frozen=True)
class RsaPublicKey:
    key_id: str
    modulus_hex: str
    exponent: int = 65537


def verify_rsa_sha256(message: bytes, signature: bytes, key: RsaPublicKey) -> bool:
    """Verify a PKCS#1 v1.5 SHA-256 signature without adding a crypto dependency to the base app."""
    modulus = int(key.modulus_hex, 16)
    size = (modulus.bit_length() + 7) // 8
    if size < 62 or len(signature) != size or key.exponent < 3 or key.exponent % 2 == 0:
        return False
    encoded = pow(int.from_bytes(signature, "big"), key.exponent, modulus).to_bytes(size, "big")
    digest = hashlib.sha256(message).digest()
    padding_size = size - len(RSA_SHA256_DIGEST_INFO) - len(digest) - 3
    expected = b"\x00\x01" + b"\xff" * padding_size + b"\x00" + RSA_SHA256_DIGEST_INFO + digest
    return padding_size >= 8 and hmac.compare_digest(encoded, expected)


@dataclass(frozen=True)
class VerifiedWorkerPackage:
    root: Path
    executable: Path
    version: str
    sha256: str
    license_id: str


def _contained_file(root: Path, relative: str) -> Path:
    if not relative or Path(relative).is_absolute() or ".." in Path(relative).parts:
        raise ValueError("package path must be relative and contained")
    candidate = root / relative
    cursor = candidate
    while cursor != root:
        if cursor.is_symlink():
            raise ValueError("symbolic links are not allowed in trainer packages")
        cursor = cursor.parent
    target = candidate.resolve()
    if not target.is_relative_to(root.resolve()):
        raise ValueError("package path escapes package root")
    if not target.is_file():
        raise ValueError(f"trainer package file does not exist: {relative}")
    return target


def _contained_path(root: Path, relative: str) -> Path:
    if not relative or Path(relative).is_absolute() or ".." in Path(relative).parts:
        raise ValueError("package path must be relative and contained")
    candidate = root / relative
    cursor = candidate
    while cursor != root:
        if cursor.is_symlink():
            raise ValueError("symbolic links are not allowed in trainer packages")
        cursor = cursor.parent
    target = candidate.resolve()
    if not target.is_relative_to(root.resolve()):
        raise ValueError("package path escapes package root")
    if not target.exists():
        raise ValueError(f"trainer package path does not exist: {relative}")
    return target


def verify_worker_package(
    package_root: str | Path,
    manifest_path: str | Path,
    public_keys: Mapping[str, RsaPublicKey],
) -> VerifiedWorkerPackage:
    root = Path(package_root).resolve()
    raw_manifest = Path(manifest_path).absolute()
    if not raw_manifest.is_relative_to(root):
        raise ValueError("worker manifest must be a regular contained file")
    manifest_file = _contained_file(root, raw_manifest.relative_to(root).as_posix())
    payload = json.loads(manifest_file.read_text(encoding="utf-8"))
    required = {
        "format", "protocol_version", "version", "platform", "license", "executable",
        "executable_sha256", "public_key_id", "signature_base64",
    }
    if set(payload) != required or payload["format"] != "nekovr-trainer-package-v1":
        raise ValueError("unsupported or incomplete trainer package manifest")
    if payload["protocol_version"] != IPC_PROTOCOL_VERSION:
        raise ValueError("trainer IPC protocol is incompatible")
    executable = _contained_file(root, payload["executable"])
    if file_sha256(executable) != payload["executable_sha256"]:
        raise ValueError("trainer executable hash mismatch")
    key = public_keys.get(payload["public_key_id"])
    if key is None:
        raise ValueError("trainer package signing key is not trusted")
    signed = {name: value for name, value in payload.items() if name != "signature_base64"}
    message = json.dumps(signed, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()
    try:
        signature = base64.b64decode(payload["signature_base64"], validate=True)
    except (ValueError, TypeError) as error:
        raise ValueError("trainer package signature is not valid base64") from error
    if not verify_rsa_sha256(message, signature, key):
        raise ValueError("trainer package signature verification failed")
    return VerifiedWorkerPackage(root, executable, payload["version"], payload["executable_sha256"], payload["license"])


@dataclass(frozen=True)
class TrainingArtifactBundle:
    root: Path
    manifest: Mapping[str, Any]
    trainable_parameters: tuple[str, ...]
    frozen_parameters: tuple[str, ...]
    trainable_parameter_count: int
    frozen_parameter_count: int
    maximum_correction_radians: float

    @classmethod
    def load(cls, root: str | Path) -> "TrainingArtifactBundle":
        directory = Path(root).resolve()
        manifest = json.loads(_contained_file(directory, "manifest.json").read_text(encoding="utf-8"))
        if manifest.get("schema_version") != 1 or manifest.get("personalization_ready") is not True:
            raise ValueError("base model is not personalization-ready")
        hashes = manifest.get("artifact_sha256")
        if not isinstance(hashes, dict) or not hashes:
            raise ValueError("training artifact hashes are missing")
        for name, expected_hash in hashes.items():
            artifact = _contained_file(directory, str(name))
            if file_sha256(artifact) != expected_hash:
                raise ValueError(f"personalization artifact hash mismatch: {name}")
        training = json.loads(_contained_file(directory, "training_graph.json").read_text(encoding="utf-8"))
        evaluation = json.loads(_contained_file(directory, "evaluation_graph.json").read_text(encoding="utf-8"))
        optimizer = json.loads(_contained_file(directory, "optimizer.json").read_text(encoding="utf-8"))
        trainable = tuple(manifest.get("trainable_parameters", ()))
        frozen = tuple(manifest.get("frozen_parameters", ()))
        enabled = {name for name, value in training.get("requires_grad", {}).items() if value is True}
        if enabled != set(trainable) or enabled & set(frozen) or set(trainable) & set(frozen):
            raise ValueError("training graph does not enforce adapter-only gradients")
        if set(optimizer.get("trainable_parameters", ())) != set(trainable):
            raise ValueError("optimizer parameters differ from the adapter contract")
        if set(optimizer.get("frozen_parameters", ())) != set(frozen):
            raise ValueError("optimizer does not preserve the frozen backbone")
        if training.get("worker_backend") != "portable_cpu_adapter_v1" or evaluation.get("worker_backend") != "portable_cpu_adapter_v1":
            raise ValueError("portable training/evaluation graphs are not worker executable")
        request_name = manifest.get("portable_worker_request")
        request = json.loads(_contained_file(directory, str(request_name)).read_text(encoding="utf-8"))
        if request.get("format") != "nekovr-portable-adapter-worker-request-v1" or request.get("base_model_sha256") != manifest.get("base_model_sha256"):
            raise ValueError("portable worker request identity is invalid")
        base_checkpoint = json.loads(_contained_file(directory, str(request.get("checkpoint"))).read_text(encoding="utf-8"))
        if canonical_hash(base_checkpoint) != manifest.get("base_model_sha256"):
            raise ValueError("portable worker checkpoint differs from the base identity")
        probe_input = json.loads(_contained_file(directory, str(request.get("training_input"))).read_text(encoding="utf-8"))
        if probe_input.get("format") != "nekovr-training-input-v1" or not probe_input.get("examples"):
            raise ValueError("portable worker probe input is invalid")
        correction_limit = float(training.get("maximum_correction_radians", float("nan")))
        if not math.isfinite(correction_limit) or correction_limit <= 0:
            raise ValueError("training artifact correction limit is invalid")
        return cls(
            directory, manifest, trainable, frozen,
            int(manifest.get("trainable_parameter_count", -1)),
            int(manifest.get("frozen_parameter_count", -1)), correction_limit,
        )

    def require_native_ort_artifacts(self) -> Mapping[str, Path]:
        roles = self.manifest.get("ort_training_artifacts")
        required = {"training_model", "evaluation_model", "optimizer_model", "checkpoint", "probe_batch"}
        if not isinstance(roles, dict) or set(roles) != required:
            raise ValueError("signed bundle does not contain native ONNX Runtime Training artifacts")
        paths = {role: _contained_path(self.root, str(relative)) for role, relative in roles.items()}
        if not paths["checkpoint"].is_dir() or any(not paths[role].is_file() for role in required - {"checkpoint"}):
            raise ValueError("ONNX Runtime Training artifact roles have invalid path types")
        declared_hashes = set(self.manifest["artifact_sha256"])
        native_files = [paths[role] for role in required - {"checkpoint"}]
        native_files.extend(path for path in paths["checkpoint"].rglob("*") if path.is_file())
        missing_hashes = [path.relative_to(self.root).as_posix() for path in native_files if path.relative_to(self.root).as_posix() not in declared_hashes]
        if missing_hashes:
            raise ValueError(f"native ONNX Runtime Training artifacts lack hashes: {missing_hashes}")
        return paths


@dataclass(frozen=True)
class TrainingPolicy:
    maximum_trainable_parameters: int
    maximum_trainable_fraction: float
    maximum_checkpoint_bytes: int
    maximum_epochs: int
    early_stopping_patience: int
    early_stopping_min_delta: float
    maximum_correction_radians: float
    maximum_false_correction_radians: float
    maximum_jitter_radians: float

    def __post_init__(self) -> None:
        numeric = (
            self.maximum_trainable_fraction, self.early_stopping_min_delta,
            self.maximum_correction_radians, self.maximum_false_correction_radians,
            self.maximum_jitter_radians,
        )
        if self.maximum_trainable_parameters <= 0 or self.maximum_checkpoint_bytes <= 0 or self.maximum_epochs <= 0 or self.early_stopping_patience <= 0:
            raise ValueError("training policy integer limits must be positive")
        if not all(math.isfinite(value) and value >= 0 for value in numeric) or self.maximum_trainable_fraction > 1:
            raise ValueError("training policy numeric limits are invalid")


def enforce_adapter_contract(bundle: TrainingArtifactBundle, policy: TrainingPolicy) -> None:
    total = bundle.trainable_parameter_count + bundle.frozen_parameter_count
    if bundle.trainable_parameter_count <= 0 or bundle.frozen_parameter_count <= 0 or total <= 0:
        raise ValueError("artifact parameter counts are invalid")
    if bundle.trainable_parameter_count > policy.maximum_trainable_parameters:
        raise ValueError("adapter exceeds the trainable parameter budget")
    if bundle.trainable_parameter_count / total > policy.maximum_trainable_fraction:
        raise ValueError("adapter exceeds the trainable parameter fraction")
    if bundle.maximum_correction_radians > policy.maximum_correction_radians:
        raise ValueError("base training graph exceeds the personal correction safety limit")


@dataclass(frozen=True)
class TrainingProviderStatus:
    provider: str
    inference_available: bool
    training_available: bool
    diagnostic: str


class TrainingRuntimeProbe(Protocol):
    def probe(self, bundle: TrainingArtifactBundle, provider: str) -> tuple[bool, str]: ...


def probe_training_providers(
    bundle: TrainingArtifactBundle,
    runtime: TrainingRuntimeProbe,
    inference_providers: Iterable[str],
) -> tuple[TrainingProviderStatus, ...]:
    inference = set(inference_providers)
    reports = []
    for provider, inference_name in (
        ("CPU", "CPUExecutionProvider"),
        ("CUDA", "CUDAExecutionProvider"),
        ("DIRECTML", "DmlExecutionProvider"),
    ):
        available, diagnostic = runtime.probe(bundle, provider)
        reports.append(TrainingProviderStatus(provider, inference_name in inference, available, diagnostic))
    return tuple(reports)


def select_training_provider(requested: str, statuses: Sequence[TrainingProviderStatus]) -> str:
    by_name = {status.provider: status for status in statuses}
    requested = requested.upper()
    if requested == "AUTO":
        for candidate in ("CUDA", "CPU"):
            if by_name.get(candidate) and by_name[candidate].training_available:
                return candidate
        raise RuntimeError("no verified CPU or CUDA training provider is available")
    status = by_name.get(requested)
    if status is None or not status.training_available:
        detail = status.diagnostic if status else "unknown provider"
        raise RuntimeError(f"forced {requested} training provider unavailable: {detail}")
    return requested


@dataclass(frozen=True)
class EpochMetrics:
    epoch: int
    validation_loss: float
    maximum_correction_radians: float
    clean_false_correction_radians: float
    temporal_jitter_radians: float

    def validate(self, policy: TrainingPolicy) -> None:
        values = (
            self.validation_loss, self.maximum_correction_radians,
            self.clean_false_correction_radians, self.temporal_jitter_radians,
        )
        if self.epoch < 1 or not all(math.isfinite(value) and value >= 0 for value in values):
            raise ValueError("worker returned invalid epoch metrics")
        if self.maximum_correction_radians > policy.maximum_correction_radians:
            raise RuntimeError("candidate exceeded the correction magnitude safety limit")
        if self.clean_false_correction_radians > policy.maximum_false_correction_radians:
            raise RuntimeError("candidate exceeded the clean-motion correction safety limit")
        if self.temporal_jitter_radians > policy.maximum_jitter_radians:
            raise RuntimeError("candidate exceeded the temporal jitter safety limit")


class TrainerBackend(Protocol):
    def frozen_parameter_hashes(self) -> Mapping[str, str]: ...
    def train_epoch(self, epoch: int) -> EpochMetrics: ...
    def write_checkpoint(self, directory: Path) -> None: ...


class EarlyStopping:
    def __init__(self, patience: int, minimum_delta: float):
        self.patience = patience
        self.minimum_delta = minimum_delta
        self.best = math.inf
        self.bad_epochs = 0

    def update(self, loss: float) -> tuple[bool, bool]:
        improved = loss < self.best - self.minimum_delta
        if improved:
            self.best = loss
            self.bad_epochs = 0
        else:
            self.bad_epochs += 1
        return improved, self.bad_epochs >= self.patience


class AtomicCheckpointArchive:
    def __init__(self, path: str | Path):
        requested = Path(path).absolute()
        cursor = requested
        while cursor != cursor.parent:
            if cursor.is_symlink():
                raise ValueError("symbolic links are not allowed for checkpoints")
            cursor = cursor.parent
        self.path = requested

    def save(self, writer: Callable[[Path], None], maximum_bytes: int) -> str:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="nekovr-checkpoint-", dir=self.path.parent) as temporary:
            staging = Path(temporary) / "checkpoint"
            staging.mkdir()
            writer(staging)
            if not any(path.is_file() for path in staging.rglob("*")):
                raise ValueError("backend produced an empty checkpoint")
            archive = Path(temporary) / "checkpoint.zip.partial"
            with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as output:
                for source in sorted(path for path in staging.rglob("*") if path.is_file()):
                    output.write(source, source.relative_to(staging).as_posix())
            with archive.open("r+b") as stream:
                os.fsync(stream.fileno())
            if archive.stat().st_size > maximum_bytes:
                raise ValueError("checkpoint exceeds the configured size budget")
            os.replace(archive, self.path)
        return file_sha256(self.path)

    def extract(self, directory: str | Path) -> Path:
        target = Path(directory).resolve()
        target.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(self.path) as archive:
            for entry in archive.infolist():
                destination = (target / entry.filename).resolve()
                if not destination.is_relative_to(target) or entry.is_dir():
                    raise ValueError("checkpoint archive contains an unsafe entry")
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as source, destination.open("wb") as output:
                    shutil.copyfileobj(source, output)
        return target


@dataclass(frozen=True)
class TrainingRunResult:
    epochs_completed: int
    best_validation_loss: float
    stopped_early: bool
    checkpoint_sha256: str
    metrics: tuple[EpochMetrics, ...]


def run_guarded_training(
    bundle: TrainingArtifactBundle,
    backend: TrainerBackend,
    policy: TrainingPolicy,
    checkpoint: str | Path,
) -> TrainingRunResult:
    enforce_adapter_contract(bundle, policy)
    frozen_before = dict(backend.frozen_parameter_hashes())
    if set(frozen_before) != set(bundle.frozen_parameters):
        raise ValueError("backend frozen parameter set differs from the signed artifact contract")
    stopper = EarlyStopping(policy.early_stopping_patience, policy.early_stopping_min_delta)
    checkpoint_store = AtomicCheckpointArchive(checkpoint)
    checkpoint_hash = ""
    metrics: list[EpochMetrics] = []
    stopped = False
    for epoch in range(1, policy.maximum_epochs + 1):
        value = backend.train_epoch(epoch)
        if value.epoch != epoch:
            raise ValueError("worker epoch sequence is not authoritative")
        value.validate(policy)
        metrics.append(value)
        improved, stopped = stopper.update(value.validation_loss)
        if improved:
            checkpoint_hash = checkpoint_store.save(backend.write_checkpoint, policy.maximum_checkpoint_bytes)
        if dict(backend.frozen_parameter_hashes()) != frozen_before:
            raise RuntimeError("frozen backbone changed during personal training")
        if stopped:
            break
    if not checkpoint_hash:
        raise RuntimeError("training produced no valid checkpoint")
    return TrainingRunResult(len(metrics), stopper.best, stopped, checkpoint_hash, tuple(metrics))


class OrtTrainingRuntimeProbe:
    """Truthful probe: a provider is usable only after loading signed artifacts and running its probe callback."""

    def __init__(self, execute_probe: Callable[[Mapping[str, Path], str], None] | None = None):
        self.execute_probe = execute_probe

    def probe(self, bundle: TrainingArtifactBundle, provider: str) -> tuple[bool, str]:
        if provider == "DIRECTML":
            return False, "DirectML inference does not imply an independently packaged training backend"
        try:
            artifacts = bundle.require_native_ort_artifacts()
            importlib.import_module("onnxruntime.training.api")
            if self.execute_probe is None:
                return False, "ONNX Runtime Training package is present but no packaged execution probe was supplied"
            self.execute_probe(artifacts, provider)
            return True, f"{provider} ONNX Runtime Training probe completed"
        except (ImportError, OSError, RuntimeError, ValueError) as error:
            return False, str(error)


def _ort_device(provider: str) -> str:
    if provider == "CPU":
        return "cpu"
    if provider == "CUDA":
        return "cuda"
    raise ValueError(f"ONNX Runtime Training does not support requested provider {provider}")


def _ort_api():
    return importlib.import_module("onnxruntime.training.api")


def _load_npz_arguments(path: Path, names: Sequence[str]) -> tuple[Any, ...]:
    numpy = importlib.import_module("numpy")
    with numpy.load(path, mmap_mode="r", allow_pickle=False) as values:
        missing = [name for name in names if name not in values]
        if missing:
            raise ValueError(f"training batch misses declared inputs: {missing}")
        return tuple(numpy.asarray(values[name]) for name in names)


def execute_ort_training_probe(artifacts: Mapping[str, Path], provider: str, contract: Mapping[str, Any]) -> None:
    """Create a real training session and complete one optimizer step before declaring a provider available."""
    api = _ort_api()
    names = tuple(str(name) for name in contract.get("training_input_order", ()))
    if not names:
        raise ValueError("ORT training contract does not declare training_input_order")
    state = api.CheckpointState.load_checkpoint(str(artifacts["checkpoint"]))
    module = api.Module(
        str(artifacts["training_model"]), state, str(artifacts["evaluation_model"]),
        device=_ort_device(provider),
    )
    optimizer = api.Optimizer(str(artifacts["optimizer_model"]), module)
    module.train()
    outputs = module(*_load_npz_arguments(artifacts["probe_batch"], names))
    values = outputs if isinstance(outputs, tuple) else (outputs,)
    numpy = importlib.import_module("numpy")
    if not values or any(not numpy.isfinite(numpy.asarray(value)).all() for value in values):
        raise RuntimeError("ORT training probe returned non-finite output")
    optimizer.step()
    optimizer.lazy_reset_grad()


class OrtTrainingBackend:
    """Streaming ONNX Runtime Training backend for optional signed CPU/CUDA worker packages."""

    def __init__(
        self,
        bundle: TrainingArtifactBundle,
        provider: str,
        training_batches: Mapping[str, str],
        validation_batches: Mapping[str, str],
        checkpoint_directory: str | Path | None = None,
    ):
        self.bundle = bundle
        self.provider = provider
        self.artifacts = bundle.require_native_ort_artifacts()
        self.contract = bundle.manifest.get("ort_training_contract")
        if not isinstance(self.contract, dict):
            raise ValueError("signed bundle is missing the ORT training tensor contract")
        self.training_names = tuple(str(name) for name in self.contract.get("training_input_order", ()))
        self.evaluation_names = tuple(str(name) for name in self.contract.get("evaluation_input_order", ()))
        if not self.training_names or not self.evaluation_names:
            raise ValueError("ORT training input orders are missing")
        self.training_batches = self._verify_batches(training_batches)
        self.validation_batches = self._verify_batches(validation_batches)
        if not self.training_batches or not self.validation_batches:
            raise ValueError("ORT training requires streaming train and validation batches")
        api = _ort_api()
        checkpoint = Path(checkpoint_directory).resolve() if checkpoint_directory else self.artifacts["checkpoint"]
        self.state = api.CheckpointState.load_checkpoint(str(checkpoint))
        self.module = api.Module(
            str(self.artifacts["training_model"]), self.state, str(self.artifacts["evaluation_model"]),
            device=_ort_device(provider),
        )
        self.optimizer = api.Optimizer(str(self.artifacts["optimizer_model"]), self.module)
        graph_hash = str(bundle.manifest["artifact_sha256"].get(Path(self.artifacts["training_model"]).relative_to(bundle.root).as_posix(), ""))
        self._frozen = {name: hashlib.sha256(f"{graph_hash}:{name}".encode()).hexdigest() for name in bundle.frozen_parameters}

    @staticmethod
    def _verify_batches(values: Mapping[str, str]) -> tuple[Path, ...]:
        paths = []
        for raw_path, expected_hash in sorted(values.items()):
            candidate = Path(raw_path).absolute()
            cursor = candidate
            while cursor != cursor.parent:
                if cursor.is_symlink():
                    raise ValueError(f"training batch uses a symbolic link: {candidate.name}")
                cursor = cursor.parent
            path = candidate.resolve()
            if not path.is_file() or file_sha256(path) != expected_hash:
                raise ValueError(f"training batch integrity failed: {path.name}")
            paths.append(path)
        return tuple(paths)

    def frozen_parameter_hashes(self) -> Mapping[str, str]:
        # The signed ORT gradient graph contains no gradient outputs for this set.
        return self._frozen

    def train_epoch(self, epoch: int) -> EpochMetrics:
        numpy = importlib.import_module("numpy")
        losses = []
        self.module.train()
        for path in self.training_batches:
            output = self.module(*_load_npz_arguments(path, self.training_names))
            values = output if isinstance(output, tuple) else (output,)
            loss = float(numpy.asarray(values[int(self.contract.get("training_loss_output", 0))]).mean())
            if not math.isfinite(loss):
                raise RuntimeError("ORT training returned non-finite loss")
            losses.append(loss)
            self.optimizer.step()
            self.optimizer.lazy_reset_grad()
        validation_losses: list[float] = []
        maximum = clean_maximum = jitter = 0.0
        self.module.eval()
        correction_index = int(self.contract.get("evaluation_correction_output", 0))
        loss_index = self.contract.get("evaluation_loss_output")
        clean_mask_name = self.contract.get("clean_mask_input")
        for path in self.validation_batches:
            arguments = _load_npz_arguments(path, self.evaluation_names)
            outputs = self.module(*arguments)
            values = outputs if isinstance(outputs, tuple) else (outputs,)
            correction = numpy.asarray(values[correction_index], dtype=float)
            if not numpy.isfinite(correction).all():
                raise RuntimeError("ORT evaluation returned non-finite correction")
            magnitudes = numpy.linalg.norm(correction, axis=-1)
            maximum = max(maximum, float(magnitudes.max(initial=0.0)))
            if clean_mask_name in self.evaluation_names:
                clean_mask = numpy.asarray(arguments[self.evaluation_names.index(clean_mask_name)], dtype=bool)
                if clean_mask.any():
                    clean_maximum = max(clean_maximum, float(magnitudes[clean_mask].max(initial=0.0)))
            if correction.ndim >= 3 and correction.shape[-3] > 1:
                jitter = max(jitter, float(numpy.linalg.norm(numpy.diff(correction, axis=-3), axis=-1).max(initial=0.0)))
            if loss_index is not None:
                validation_losses.append(float(numpy.asarray(values[int(loss_index)]).mean()))
        validation_loss = sum(validation_losses) / len(validation_losses) if validation_losses else sum(losses) / len(losses)
        return EpochMetrics(epoch, validation_loss, maximum, clean_maximum, jitter)

    def write_checkpoint(self, directory: Path) -> None:
        _ort_api().CheckpointState.save_checkpoint(self.state, str(directory), include_optimizer_state=True)


@dataclass(frozen=True)
class IpcMessage:
    kind: str
    request_id: str
    payload: Mapping[str, Any]
    protocol_version: int = IPC_PROTOCOL_VERSION

    def to_json(self) -> str:
        return json.dumps(asdict(self), sort_keys=True, separators=(",", ":"))

    @classmethod
    def parse(cls, line: str) -> "IpcMessage":
        value = json.loads(line)
        if set(value) != {"kind", "request_id", "payload", "protocol_version"}:
            raise ValueError("trainer IPC message fields differ from protocol")
        if value["protocol_version"] != IPC_PROTOCOL_VERSION or not value["kind"] or not value["request_id"] or not isinstance(value["payload"], dict):
            raise ValueError("trainer IPC message is invalid or incompatible")
        return cls(value["kind"], value["request_id"], value["payload"], value["protocol_version"])


class TrainerWorkerProcess:
    """Verified controller for a worker that is always launched outside the server process."""

    def __init__(self, package: VerifiedWorkerPackage):
        self.package = package
        self.process: subprocess.Popen[str] | None = None

    def start(self) -> None:
        if self.process is not None:
            raise RuntimeError("trainer worker is already running")
        self.process = subprocess.Popen(
            [str(self.package.executable), "--ipc-stdio"],
            cwd=self.package.root,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            shell=False,
        )

    def request(self, message: IpcMessage) -> IpcMessage:
        if self.process is None or self.process.stdin is None or self.process.stdout is None:
            raise RuntimeError("trainer worker is not running")
        self.process.stdin.write(message.to_json() + "\n")
        self.process.stdin.flush()
        response = self.process.stdout.readline()
        if not response:
            raise RuntimeError("trainer worker terminated without a response")
        parsed = IpcMessage.parse(response)
        if parsed.request_id != message.request_id:
            raise RuntimeError("trainer worker response request ID mismatch")
        return parsed

    def close(self) -> None:
        if self.process is None:
            return
        if self.process.poll() is None:
            self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
        self.process = None
