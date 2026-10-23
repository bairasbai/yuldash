"""Validate the deliberately small JSON Schema vocabulary shipped with this tool.

This is NOT a general-purpose JSON Schema engine. Unsupported keywords fail
closed. The shipped schema is the sole supported contract; no remote refs run.
"""

import json
import math
import re
from pathlib import Path


SCHEMA_PATH = Path(__file__).resolve().parent.parent / "schema" / "manifest.schema.json"
SUPPORTED = {
    "$schema", "$id", "$defs", "$ref", "title", "description", "type", "enum",
    "const", "properties", "required", "additionalProperties", "items",
    "minItems", "uniqueItems", "minLength", "pattern", "minimum", "maximum",
}


class SchemaContractError(ValueError):
    """The bundled contract uses an unsupported or unsafe construct."""


def load_schema():
    return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


def check_schema_contract(schema):
    def walk(node):
        if not isinstance(node, dict):
            raise SchemaContractError("Schema nodes must be objects")
        unknown = set(node) - SUPPORTED
        if unknown:
            raise SchemaContractError(f"Unsupported schema keywords: {sorted(unknown)}")
        if "$ref" in node:
            ref = node["$ref"]
            if not ref.startswith("#/$defs/") or ref[8:] not in schema.get("$defs", {}):
                raise SchemaContractError("Only existing local $defs references are supported")
            if len(node) != 1:
                raise SchemaContractError("$ref siblings are unsupported")
        if "type" in node and node["type"] not in {
            "object", "array", "string", "integer", "number", "boolean", "null"
        }:
            raise SchemaContractError("Unsupported schema type")
        if "additionalProperties" in node and node["additionalProperties"] is not False:
            raise SchemaContractError("Only closed objects are supported")
        if "pattern" in node:
            re.compile(node["pattern"])
        for container in ("$defs", "properties"):
            for child in node.get(container, {}).values():
                walk(child)
        if "items" in node:
            walk(node["items"])

    walk(schema)


def validate_structure(document, schema=None):
    schema = load_schema() if schema is None else schema
    check_schema_contract(schema)
    errors = []

    def error(path, message):
        errors.append({"code": "SCHEMA", "path": path, "message": message})

    def pointer(value):
        return str(value).replace("~", "~0").replace("/", "~1")

    def walk(value, node, path):
        if "$ref" in node:
            node = schema["$defs"][node["$ref"][8:]]
        typ = node.get("type")
        matches = {
            "object": isinstance(value, dict),
            "array": isinstance(value, list),
            "string": isinstance(value, str),
            "integer": type(value) is int,
            "number": type(value) in (int, float),
            "boolean": type(value) is bool,
            "null": value is None,
        }
        if typ is not None and not matches[typ]:
            error(path, f"Expected {typ}")
            return
        if "enum" in node and value not in node["enum"]:
            error(path, f"Expected one of {node['enum']}")
        if "const" in node and value != node["const"]:
            error(path, f"Expected constant {node['const']!r}")
        if isinstance(value, dict):
            for key in node.get("required", []):
                if key not in value:
                    error(path + "/" + pointer(key), "Required property is missing")
            properties = node.get("properties", {})
            for key, child in value.items():
                child_path = path + "/" + pointer(key)
                if key in properties:
                    walk(child, properties[key], child_path)
                elif node.get("additionalProperties") is False:
                    error(child_path, "Unknown property")
        if isinstance(value, list):
            if len(value) < node.get("minItems", 0):
                error(path, f"At least {node['minItems']} items required")
            if node.get("uniqueItems"):
                canonical = [json.dumps(item, sort_keys=True, ensure_ascii=False) for item in value]
                if len(set(canonical)) != len(canonical):
                    error(path, "Duplicate array entries")
            if "items" in node:
                for i, item in enumerate(value):
                    walk(item, node["items"], path + f"/{i}")
        if isinstance(value, str):
            if len(value) < node.get("minLength", 0):
                error(path, f"At least {node['minLength']} characters required")
            if "pattern" in node and not re.search(node["pattern"], value):
                error(path, "String does not match the required pattern")
        if type(value) in (int, float):
            if isinstance(value, float) and not math.isfinite(value):
                error(path, "Non-finite numbers are not JSON numbers")
            if "minimum" in node and value < node["minimum"]:
                error(path, "Number is below minimum")
            if "maximum" in node and value > node["maximum"]:
                error(path, "Number is above maximum")

    walk(document, schema, "")
    return errors
