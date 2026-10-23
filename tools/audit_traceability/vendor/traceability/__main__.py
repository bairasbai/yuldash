import argparse
import json
import sys
from pathlib import Path

from . import __version__
from .io import read_document
from .schema_check import SchemaContractError
from .validator import validate


def emit(result, pretty=False):
    # Escaped JSON remains valid with ASCII stdout and lone surrogate inputs.
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, indent=2 if pretty else None))


def input_failure(code, message):
    return {
        "tool_version": __version__, "valid": False, "gate_passed": False,
        "computed_state": "invalid", "blockers": [{"code": code, "path": "", "message": message}],
        "requirements": [], "limitations": ["No authenticity attestation is made."],
    }


class Parser(argparse.ArgumentParser):
    def error(self, message):
        emit(input_failure("USAGE", message))
        self.exit(2)


def main(argv=None):
    parser = Parser(description="Offline traceability / strict acceptance validator")
    parser.add_argument("--version", action="version", version=__version__)
    sub = parser.add_subparsers(dest="command", required=True, parser_class=Parser)
    command = sub.add_parser("validate", help="Validate one manifest and its local artifact bytes")
    command.add_argument("manifest", type=Path)
    command.add_argument("--baseline", type=Path, help="Check stable feature and requirement identity")
    command.add_argument("--pretty", action="store_true")
    args = parser.parse_args(argv)
    try:
        document = read_document(args.manifest)
        baseline = read_document(args.baseline) if args.baseline else None
        result = validate(document, args.manifest.resolve().parent, baseline=baseline)
    except (OSError, UnicodeError, ValueError, RuntimeError) as exc:
        code = "SCHEMA_ENGINE" if isinstance(exc, SchemaContractError) else "INPUT"
        emit(input_failure(code, str(exc)), args.pretty)
        return 2
    emit(result, args.pretty)
    return 0 if result["gate_passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
