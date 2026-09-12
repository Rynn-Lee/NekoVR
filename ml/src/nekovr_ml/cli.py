from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import sys

from .amass import AssetError, load_amass, write_motion_json
from .config import configure_process_determinism, load_config
from .evaluation import replay_checkpoint
from .model import CompactCausalModel, ModelConfig, collate_variable_layout
from .model_metadata import load_sidecar
from .onnx_export import export_model
from .onnx_validation import generate_parity_evidence, inspect_export
from .personalization import descriptor_from_model, generate_personalization_artifacts
from .preparation import prepare_amass_motion, prepare_real_session, write_canonical_preparation
from .probe import create_probe_bundle
from .promotion import generate_promotion_decision, publish_catalog_entry, stage_atomic_model_bundle
from .provenance import canonical_hash, compare_run_manifests, create_run_manifest, file_sha256, write_run_manifest
from .sessions import load_session, write_prepared_sessions
from .simulation import SimulationConfig
from .training import load_model_checkpoint, train_global_model, training_example_from_mapping, write_model_checkpoint
from .workflow import build_training_plan, qualify_context_features


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
    prepare = commands.add_parser("prepare", help="produce one canonical training-example contract")
    prepare.add_argument("--amass")
    prepare.add_argument("--body-model")
    prepare.add_argument("--archive", action="append", default=[])
    prepare.add_argument("--layout", action="append", type=int)
    prepare.add_argument("--source-license", default="AMASS")
    prepare.add_argument("--body-model-license", default="SMPL")
    prepare.add_argument("--output", required=True)
    _add_config(prepare)
    train = commands.add_parser("train", help="split, normalize, qualify, and train the global model")
    train.add_argument("--input", required=True, help="nekovr-training-input-v1 JSON")
    train.add_argument("--output-dir", required=True)
    train.add_argument("--epochs", type=int, default=20)
    train.add_argument("--learning-rate", type=float, default=0.01)
    _add_config(train)
    evaluate_command = commands.add_parser("evaluate", help="replay a real checkpoint over canonical holdouts")
    evaluate_command.add_argument("--input", required=True, help="canonical nekovr-training-input-v1 JSON")
    evaluate_command.add_argument("--checkpoint", required=True)
    evaluate_command.add_argument("--output-dir", required=True)
    _add_config(evaluate_command)
    promote = commands.add_parser("promote", help="derive a promotion decision from generated evidence")
    promote.add_argument("--model", required=True)
    promote.add_argument("--sidecar", required=True)
    promote.add_argument("--evaluation", required=True)
    promote.add_argument("--parity", required=True)
    promote.add_argument("--feature-qualification", required=True)
    promote.add_argument("--output", required=True)
    promote.add_argument("--bundle-root")
    _add_config(promote)
    publish = commands.add_parser("publish-catalog", help="publish a verified atomic model bundle")
    publish.add_argument("--bundle", required=True)
    publish.add_argument("--catalog", required=True)
    _add_config(publish)
    parity = commands.add_parser("parity", help="generate checkpoint-bound framework/ONNX parity evidence")
    parity.add_argument("--input", required=True, help="canonical nekovr-training-input-v1 JSON")
    parity.add_argument("--checkpoint", required=True)
    parity.add_argument("--model", required=True)
    parity.add_argument("--sidecar", required=True)
    parity.add_argument("--output", required=True)
    parity.add_argument("--tolerance", type=float, default=1e-5)
    _add_config(parity)
    compare = commands.add_parser("compare-runs", help="compare deterministic run manifests")
    compare.add_argument("left")
    compare.add_argument("right")
    compare.add_argument("--output", required=True)
    compare.add_argument("--metric-absolute-tolerance", type=float, default=1e-9)
    _add_config(compare)
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
    if args.command == "prepare":
        if bool(args.amass) != bool(args.body_model):
            raise ValueError("--amass and --body-model must be supplied together")
        if not args.amass and not args.archive:
            raise ValueError("prepare requires licensed AMASS/SMPL assets, a validated archive, or both")
        sources, examples = [], []
        if args.amass:
            motion = load_amass(
                args.amass,
                args.body_model,
                config.values["sample_rate_hz"],
                args.source_license,
                args.body_model_license,
            )
            source, prepared = prepare_amass_motion(
                motion,
                args.layout or config.values["layouts"],
                int(config.values["max_slots"]),
                SimulationConfig.from_mapping(config.values.get("simulation", {})),
                config.seed,
            )
            sources.append(source)
            examples.extend(prepared)
        for archive in args.archive:
            source, prepared = prepare_real_session(load_session(archive), int(config.values["max_slots"]))
            sources.append(source)
            examples.extend(prepared)
        write_canonical_preparation(sources, examples, args.output)
        return 0
    if args.command == "train":
        payload = json.loads(Path(args.input).read_text(encoding="utf-8"))
        plan = build_training_plan(payload, config.values, config.seed)
        model_values = config.values["model"]
        model_config = ModelConfig(
            feature_count=int(model_values["feature_count"]), hidden_size=int(model_values["hidden_size"]),
            temporal_layers=int(model_values["temporal_layers"]), kernel_size=int(model_values["kernel_size"]),
            role_count=int(model_values["role_count"]), max_slots=int(config.values["max_slots"]), output_axes=int(model_values["output_axes"]),
            maximum_correction_radians=math.radians(float(model_values["maximum_correction_degrees"])),
            maximum_drift_rate_radians_per_second=math.radians(float(model_values["maximum_drift_rate_degrees_per_second"])),
        )
        model = CompactCausalModel(model_config, config.seed)
        if model_config.feature_count != len(plan.feature_specifications):
            raise ValueError("configured model feature count differs from canonical feature schema")
        examples = tuple(training_example_from_mapping(value, config.values.get("losses")) for value in plan.examples)
        result = train_global_model(model, examples, args.epochs, args.learning_rate, config.seed)
        output = Path(args.output_dir)
        output.mkdir(parents=True, exist_ok=True)
        checkpoint = output / "model-checkpoint.json"
        write_model_checkpoint(model, checkpoint)
        plan_path = output / "training-plan.json"
        _write_json(plan_path, plan.to_dict())
        split_audit_path = output / "split-audit.json"
        _write_json(split_audit_path, plan.split_audit)
        normalization_path = output / "normalization.json"
        _write_json(normalization_path, plan.normalization)
        qualification = qualify_context_features(model, examples, plan.feature_specifications, plan.normalization, config.values.get("features", {}))
        qualification_path = output / "feature-qualification.json"
        _write_json(qualification_path, qualification)
        descriptor = descriptor_from_model(payload.get("model_id", "nekovr-small-v1"), model, payload.get("supported_roles", []), payload.get("supported_layouts", config.values["layouts"]))
        personalization = generate_personalization_artifacts(descriptor, output / "personalization")
        metrics = {
            "epoch_losses": result.epoch_losses, "updated_parameters": result.updated_parameters,
            "loss_summary": result.loss_summary, "feature_qualification": qualification,
        }
        manifest = create_run_manifest(
            config, Path(__file__).resolve().parents[3], {"training_input": args.input},
            plan.assignments, plan.normalization, metrics,
            {
                "checkpoint": checkpoint, "training_plan": plan_path, "split_audit": split_audit_path,
                "normalization": normalization_path, "feature_qualification": qualification_path,
                "personalization_manifest": personalization / "manifest.json",
            },
            lineage=_plan_lineage(payload, plan.to_dict()) | {
                "loss_summary": result.loss_summary,
                "feature_decisions": qualification["decisions"],
                "checkpoint": {"path": checkpoint.name, "sha256": file_sha256(checkpoint)},
                "personalization": {"manifest_sha256": file_sha256(personalization / "manifest.json")},
            },
        )
        write_run_manifest(manifest, output / "run.json")
        return 0
    if args.command == "evaluate":
        payload = json.loads(Path(args.input).read_text(encoding="utf-8"))
        plan = build_training_plan(payload, config.values, config.seed)
        examples = tuple(training_example_from_mapping(value, config.values.get("losses")) for value in plan.examples)
        checkpoint = load_model_checkpoint(args.checkpoint)
        replay = replay_checkpoint(checkpoint, examples)
        records = replay.pop("records")
        evidence = {
            "format": "nekovr-checkpoint-evaluation-v1",
            "checkpoint_sha256": file_sha256(args.checkpoint),
            "input_sha256": file_sha256(args.input),
            "split_sha256": canonical_hash(plan.assignments),
            "normalization_sha256": canonical_hash(plan.normalization),
            "predictions_sha256": canonical_hash(records),
            **replay,
            "records": records,
        }
        output = Path(args.output_dir)
        output.mkdir(parents=True, exist_ok=True)
        metrics_path = output / "metrics.json"
        _write_json(metrics_path, evidence)
        manifest = create_run_manifest(
            config, Path(__file__).resolve().parents[3], {"evaluation_input": args.input, "checkpoint": args.checkpoint},
            plan.assignments, plan.normalization, evidence, {"metrics": metrics_path},
            lineage=_plan_lineage(payload, plan.to_dict()) | {
                "checkpoint": {"path": Path(args.checkpoint).name, "sha256": evidence["checkpoint_sha256"]},
                "evaluation": {"path": metrics_path.name, "sha256": file_sha256(metrics_path)},
            },
        )
        write_run_manifest(manifest, output / "run.json")
        return 0
    if args.command == "promote":
        decision = generate_promotion_decision(
            args.model, args.sidecar, args.evaluation, args.parity, args.feature_qualification, args.output,
        )
        bundle_root = Path(args.bundle_root) if args.bundle_root else Path(args.output).parent / "model-bundles"
        bundle = (
            stage_atomic_model_bundle(args.model, args.sidecar, args.parity, args.evaluation, args.output, bundle_root)
            if decision["passed"] else None
        )
        print(json.dumps({"output": args.output, "bundle": str(bundle) if bundle else None, "passed": decision["passed"], "failures": decision["failures"]}, sort_keys=True))
        return 0
    if args.command == "publish-catalog":
        result = publish_catalog_entry(args.bundle, args.catalog)
        print(json.dumps({"bundle": args.bundle, "catalog": args.catalog, "passed": result.passed}, sort_keys=True))
        return 0
    if args.command == "parity":
        payload = json.loads(Path(args.input).read_text(encoding="utf-8"))
        plan = build_training_plan(payload, config.values, config.seed)
        examples = tuple(training_example_from_mapping(value, config.values.get("losses")) for value in plan.examples if value["split"] in {"validation", "test"})
        checkpoint = load_model_checkpoint(args.checkpoint)
        batches = tuple(collate_variable_layout([value.sequence], checkpoint.config.feature_count, checkpoint.config.max_slots) for value in examples)
        evidence = generate_parity_evidence(checkpoint, args.checkpoint, args.model, args.sidecar, batches, args.tolerance)
        _write_json(args.output, evidence)
        print(json.dumps({"output": args.output, "passed": evidence["passed"], "maximum_absolute_error": evidence["maximum_absolute_error"]}, sort_keys=True))
        return 0
    if args.command == "compare-runs":
        left = json.loads(Path(args.left).read_text(encoding="utf-8"))
        right = json.loads(Path(args.right).read_text(encoding="utf-8"))
        comparison = compare_run_manifests(left, right, args.metric_absolute_tolerance)
        _write_json(args.output, comparison)
        print(json.dumps({"output": args.output, "passed": comparison["passed"]}, sort_keys=True))
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
        export_manifest = create_run_manifest(
            config, Path(__file__).resolve().parents[3], {"checkpoint": args.checkpoint, "metadata": args.metadata},
            metadata.get("split_assignments", {}), metadata["normalization"], metadata.get("validation_metrics", {}),
            {"model": model_path, "sidecar": sidecar_path},
            lineage={
                "sources": metadata.get("sources", []),
                "checkpoint": {"path": Path(args.checkpoint).name, "sha256": file_sha256(args.checkpoint)},
                "evaluation": metadata.get("evaluation", {}),
                "exported_artifacts": {
                    "model": {"path": model_path.name, "sha256": file_sha256(model_path)},
                    "sidecar": {"path": sidecar_path.name, "sha256": file_sha256(sidecar_path)},
                },
            },
        )
        write_run_manifest(export_manifest, model_path.with_suffix(model_path.suffix + ".run.json"))
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


def _write_json(path: str | Path, payload: object) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".partial")
    temporary.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(target)


def _plan_lineage(payload: dict, plan: dict) -> dict:
    assignments = plan["assignments"]
    return {
        "sources": payload.get("sources", []),
        "groups": [
            {
                "sample_id": value["sample_id"], "source_sha256": value["source_sha256"],
                "group": value["group"], "split": assignments[value["sample_id"]],
            }
            for value in payload.get("examples", [])
        ],
        "windows": [
            {
                "sample_id": value["sample_id"], "parent_sample_id": value["parent_sample_id"],
                "split": value["split"], "window": value["window"],
            }
            for value in plan["examples"]
        ],
    }


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


def prepare_main() -> int:
    return _safe_run(["prepare", *sys.argv[1:]])


def train_main() -> int:
    return _safe_run(["train", *sys.argv[1:]])


def evaluate_main() -> int:
    return _safe_run(["evaluate", *sys.argv[1:]])


def parity_main() -> int:
    return _safe_run(["parity", *sys.argv[1:]])


def promote_main() -> int:
    return _safe_run(["promote", *sys.argv[1:]])


def publish_catalog_main() -> int:
    return _safe_run(["publish-catalog", *sys.argv[1:]])


def compare_runs_main() -> int:
    return _safe_run(["compare-runs", *sys.argv[1:]])


def export_onnx_main() -> int:
    return _safe_run(["export-onnx", *sys.argv[1:]])


def validate_onnx_main() -> int:
    return _safe_run(["validate-onnx", *sys.argv[1:]])


def generate_probe_main() -> int:
    return _safe_run(["generate-probe", *sys.argv[1:]])


if __name__ == "__main__":
    raise SystemExit(main())
