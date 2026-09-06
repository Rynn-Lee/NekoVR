import json
import argparse

from .reader import DatasetReader


def main() -> int:
    parser = argparse.ArgumentParser(description="Inspect a canonical NekoVR dataset archive")
    parser.add_argument("archive")
    parser.add_argument("--output", help="write the JSON report to this file")
    args = parser.parse_args()
    payload = json.dumps(DatasetReader().inspect_archive(args.archive), indent=2)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as output:
            output.write(payload + "\n")
    else:
        print(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
