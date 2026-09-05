import json
import sys

from .reader import DatasetReader


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: python -m nekovr_dataset SESSION.nvrdata", file=sys.stderr)
        return 2
    print(json.dumps(DatasetReader().inspect_archive(sys.argv[1]), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
