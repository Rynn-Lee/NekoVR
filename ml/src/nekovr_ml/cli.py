from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import sys

from .amass import AssetError, load_amass, write_motion_json
from .config import configure_process_determinism, load_config
from .evaluation import EvaluationRecord, evaluate
from .model import CompactCausalModel, ModelConfig, SequenceSample
from .model_metadata import load_sidecar
from .onnx_export import export_model
from .onnx_validation import inspect_export
from .personalization import descriptor_from_model, generate_personalization_artifacts
from .probe import create_probe_bundle
from .provenance import create_run_manifest, write_run_manifest
from .sessions import write_prepared_sessions
from .training import TrainingExample, load_model_checkpoint, train_adapter_heads, write_model_checkpoint


def _default_config() -> Path:
    return Path(__file__).with_name("default_config.json")


def _add_config(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--config", default=str(_default_config()))


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="nekovr-ml", description="NekoVR deterministic ML data pipeline")
    commands = parser.add_subparsers(dest="command", required=True)
    amass = commands.add_parser("prepare-amass", help="convert licensed AMASS/SMPL assets")
    amass.add_argument("--amass", required=True)
    amass.add_argument("--body-model", required=True)
    amass.add_argument("--output", required=True)
    amass.add_argument("--source-license", default="AMASS")
    amass.add_argument("--body-model-license", default="SMPL")
    _add_config(amass)
    sessions = commands.add_parser("prepare-sessions", help="validate and decode canonical .nvrdata archives")
    sessions.add_argument("archives", nargs="+")
    sessions.add_argument("--output", required=True)
    _add_config(sessions)
    train = commands.add_parser("train", help="train deterministic bounded adapter heads")
    train.add_argument("--input", required=True, help="nekovr-training-input-v1 JSON")
    train.add_argument("--output-dir", required=True)
    train.add_argument("--epochs", type=int, default=20)
    train.add_argument("--learning-rate", type=float, default=0.01)
    _add_config(train)
    evaluate_command = commands.add_parser("evaluate", help="evaluate candidate predictions and cohort metrics")
    evaluate_command.add_argument("--input", required=True, help="nekovr-evaluation-input-v1 JSON")
    evaluate_command.add_argument("--output-dir", required=True)
    _add_config(evaluate_command)
    export = commands.add_parser("export-onnx", help="export a framework checkpoint and versioned sidecar")
    export.add_argument("--checkpoint", required=True)
    export.add_argument("--metadata", required=True, help="JSON metadata/provenance input")
    export.add_argument("--output", required=True)
    _add_config(export)
    validate = commands.add_parser("validate-onnx", help="validate ONNX structure, sidecar integrity and CPU loading")
    validate.add_argument("--model", required=True)
    validate.add_argument("--sidecar")
    _add_config(validate)
    probe = commands.add_parser("generate-probe", help="write the deterministic provider/package probe bundle")
    probe.add_argument("--output-dir", required=True)
    _add_config(probe)
    return parser


def _run(arguments: list[str] | None = None) -> int:
    args = _parser().parse_args(arguments)
    config = load_config(args.config)
    configure_process_determinism(config)
    if args.command == "prepare-amass":
        motion = load_amass(args.amass, args.body_model, config.values["sample_rate_hz"], args.source_license, args.body_model_license)
        write_motion_json(motion, args.output)
        return 0
    if args.command == "prepare-sessions":
        write_prepared_sessions(args.archives, args.output)
        return 0
    if args.command == "train":
        payload = json.loads(Path(args.input).read_text(encoding="utf-8"))
        if payload.get("format") != "nekovr-training-input-v1":
            raise ValueError("training input format must be nekovr-training-input-v1")
        model_values = config.values["model"]
        model_config = ModelConfig(
            feature_count=int(model_values["feature_count"]), hidden_size=int(model_values["hidden_size"]),
            temporal_layers=int(model_values["temporal_layers"]), kernel_size=int(model_values["kernel_size"]),
            role_count=int(model_values["role_count"]), max_slots=int(config.values["max_slots"]), output_axes=int(model_values["output_axes"]),
            maximum_correction_radians=math.radians(float(model_values["maximum_correction_degrees"])),
            maximum_drift_rate_radians_per_second=math.radians(float(model_values["maximum_drift_rate_degrees_per_second"])),
        )
        model = CompactCausalModel(model_config, config.seed)
        examples = []
        for value in payload["examples"]:
            sequence = SequenceSample(
                tuple(tuple(tuple(float(x) for x in slot) for slot in frame) for frame in value["features"]),
                tuple(int(x) for x in value["role_ids"]), tuple(bool(x) for x in value["slot_mask"]),
                tuple(tuple(tuple(bool(x) for x in slot) for slot in frame) for frame in value["channel_validity"]),
                tuple(float(x) for x in value["time_deltas_s"]),
            )
            examples.append(TrainingExample(
                value["sample_id"], value.get("split", "train"), sequence,
                tuple(tuple(float(x) for x in slot) for slot in value["target_correction_rotation_vectors"]),
                tuple(float(x) for x in value["target_confidence"]), tuple(float(x) for x in value["loss_weights"]),
            ))
        result = train_adapter_heads(model, examples, args.epochs, args.learning_rate, config.seed)
        output = Path(args.output_dir)
        checkpoint = output / "model-checkpoint.json"
        write_model_checkpoint(model, checkpoint)
        descriptor = descriptor_from_model(payload.get("model_id", "nekovr-small-v1"), model, payload.get("supported_roles", []), payload.get("supported_layouts", config.values["layouts"]))
        personalization = generate_personalization_artifacts(descriptor, output / "personalization")
        metrics = {"epoch_losses": result.epoch_losses, "updated_parameters": result.updated_parameters}
        manifest = create_run_manifest(
            config, Path(__file__).resolve().parents[3], {"training_input": args.input},
            {example.sample_id: example.split for example in examples}, payload.get("normalization", {}), metrics,
            {"checkpoint": checkpoint, "personalization_manifest": personalization / "manifest.json"},
        )
        write_run_manifest(manifest, output / "run.json")
        return 0
    if args.command == "evaluate":
        payload = json.loads(Path(args.input).read_text(encoding="utf-8"))
        if payload.get("format") != "nekovr-evaluation-input-v1":
            raise ValueError("evaluation input format must be nekovr-evaluation-input-v1")
        records = [EvaluationRecord(**value) for value in payload["records"]]
        report = evaluate(records)
        output = Path(args.output_dir)
        output.mkdir(parents=True, exist_ok=True)
        metrics_path = output / "metrics.json"
        metrics_path.write_text(json.dumps(report.to_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
        manifest = create_run_manifest(
            config, Path(__file__).resolve().parents[3], {"evaluation_input": args.input},
            payload.get("split_assignments", {}), payload.get("normalization", {}), report.to_dict(), {"metrics": metrics_path},
        )
        write_run_manifest(manifest, output / "run.json")
        return 0
    if args.command == "export-onnx":
        model = load_model_checkpoint(args.checkpoint)
        metadata = json.loads(Path(args.metadata).read_text(encoding="utf-8"))
        model_path, sidecar_path = export_model(
            model, args.output, model_id=metadata["model_id"], model_version=metadata["model_version"],
            feature_schema=metadata["feature_schema"], normalization=metadata["normalization"],
            supported_roles=metadata["supported_roles"], minimum_slots=int(metadata["slot_bounds"]["minimum"]),
            minimum_context=int(metadata["context_bounds"]["minimum"]), maximum_context=int(metadata["context_bounds"]["maximum"]),
            provenance=metadata["provenance"], validation_metrics=metadata.get("validation_metrics", {}),
            performance_tier=metadata.get("performance_tier", "small"),
        )
        print(json.dumps({"model": str(model_path), "sidecar": str(sidecar_path), "sha256": load_sidecar(sidecar_path, model_path).model_sha256}, sort_keys=True))
        return 0
    if args.command == "validate-onnx":
        sidecar = args.sidecar or str(Path(args.model).with_suffix(Path(args.model).suffix + ".json"))
        metadata = inspect_export(args.model, sidecar)
        print(json.dumps({"model": args.model, "sidecar": sidecar, "sha256": metadata.model_sha256, "opset": metadata.opset, "provider": "CPUExecutionProvider"}, sort_keys=True))
        return 0
    if args.command == "generate-probe":
        manifest = create_probe_bundle(args.output_dir)
        print(json.dumps({"manifest": str(manifest)}, sort_keys=True))
        return 0
    raise RuntimeError(f"unhandled command {args.command}")


def _safe_run(arguments: list[str] | None = None) -> int:
    try:
        return _run(arguments)
    except (AssetError, ValueError, RuntimeError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


def main() -> int:
    return _safe_run()


def prepare_amass_main() -> int:
    return _safe_run(["prepare-amass", *sys.argv[1:]])


def prepare_sessions_main() -> int:
    return _safe_run(["prepare-sessions", *sys.argv[1:]])


def train_main() -> int:
    return _safe_run(["train", *sys.argv[1:]])


def evaluate_main() -> int:
    return _safe_run(["evaluate", *sys.argv[1:]])


def export_onnx_main() -> int:
    return _safe_run(["export-onnx", *sys.argv[1:]])


def validate_onnx_main() -> int:
    return _safe_run(["validate-onnx", *sys.argv[1:]])


def generate_probe_main() -> int:
    return _safe_run(["generate-probe", *sys.argv[1:]])


if __name__ == "__main__":
    raise SystemExit(main())
