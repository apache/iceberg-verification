#!/usr/bin/env python3
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
"""Validate conformance fixtures against the JSON Schemas in dev/schema/.

Each cases.json is validated against the base structural schema and its surface
schema, when present; case ids must be unique within a surface.
"""

import glob
import json
import os
import sys

import jsonschema

HERE = os.path.dirname(os.path.abspath(__file__))
SCHEMA_DIR = os.path.join(HERE, "schema")


def _load_schema(name):
    with open(os.path.join(SCHEMA_DIR, name), encoding="utf-8") as fh:
        return json.load(fh)


def _rel(path):
    return path.replace(os.sep, "/")


def _is_types_surface(path):
    p = _rel(path)
    return "/table-spec/types/" in p or p.startswith("table-spec/types/")


def _is_partition_transforms_surface(path):
    p = _rel(path)
    return "/table-spec/partition-transforms/" in p or p.startswith(
        "table-spec/partition-transforms/"
    )


IDENTITY_TYPES = {
    "unknown",
    "boolean",
    "int",
    "long",
    "float",
    "double",
    "decimal",
    "date",
    "time",
    "timestamp",
    "timestamptz",
    "timestamp_ns",
    "timestamptz_ns",
    "string",
    "uuid",
    "fixed",
    "binary",
}
BUCKET_TYPES = {
    "int",
    "long",
    "decimal",
    "date",
    "time",
    "timestamp",
    "timestamptz",
    "timestamp_ns",
    "timestamptz_ns",
    "string",
    "uuid",
    "fixed",
    "binary",
}
TRUNCATE_TYPES = {"int", "long", "decimal", "string", "binary"}
DATE_TRANSFORM_TYPES = {
    "date",
    "timestamp",
    "timestamptz",
    "timestamp_ns",
    "timestamptz_ns",
}
HOUR_TYPES = {"timestamp", "timestamptz", "timestamp_ns", "timestamptz_ns"}
VOID_TYPES = IDENTITY_TYPES | {"geometry", "geography"}


def _type_family(type_name):
    if not isinstance(type_name, str):
        return None
    if type_name.startswith("decimal("):
        return "decimal"
    if type_name.startswith("fixed["):
        return "fixed"
    if type_name.startswith("geometry("):
        return "geometry"
    if type_name.startswith("geography("):
        return "geography"
    return type_name


def _transform_family(transform):
    if not isinstance(transform, str):
        return None
    if transform.startswith("bucket["):
        return "bucket"
    if transform.startswith("truncate["):
        return "truncate"
    return transform


def _json_scalar_equal(left, right):
    return type(left) is type(right) and left == right


def _partition_transform_errors(path, doc):
    errors = []
    cases = doc.get("cases") if isinstance(doc, dict) else None
    if not isinstance(cases, list):
        return errors

    allowed_sources = {
        "identity": IDENTITY_TYPES,
        "bucket": BUCKET_TYPES,
        "truncate": TRUNCATE_TYPES,
        "year": DATE_TRANSFORM_TYPES,
        "month": DATE_TRANSFORM_TYPES,
        "day": DATE_TRANSFORM_TYPES,
        "hour": HOUR_TYPES,
        "void": VOID_TYPES,
    }

    for index, case in enumerate(cases):
        if not isinstance(case, dict):
            continue
        input_value = case.get("input")
        decoded = case.get("decoded")
        if not isinstance(input_value, dict) or not isinstance(decoded, dict):
            continue

        transform = input_value.get("transform")
        source_type = input_value.get("source_type")
        values = input_value.get("values")
        result_types = decoded.get("result_types")
        expected = decoded.get("values")
        transform_family = _transform_family(transform)
        source_family = _type_family(source_type)
        label = f"{path}: cases/{index}"

        permitted = allowed_sources.get(transform_family)
        if permitted is None:
            errors.append(f"{label}: unknown transform '{transform}'")
        elif source_family not in permitted:
            errors.append(
                f"{label}: transform '{transform}' does not support source type "
                f"'{source_type}'"
            )

        if isinstance(values, list) and isinstance(expected, list):
            if len(values) != len(expected):
                errors.append(
                    f"{label}: input.values and decoded.values must have equal lengths"
                )
            if None not in values:
                errors.append(f"{label}: input.values must include null")
            for value_index, (value, output) in enumerate(zip(values, expected)):
                if value is None and output is not None:
                    errors.append(
                        f"{label}: decoded.values/{value_index} must be null for a "
                        "null input"
                    )
                if transform_family == "void" and output is not None:
                    errors.append(
                        f"{label}: decoded.values/{value_index} must be null for void"
                    )
                if transform_family == "identity" and not _json_scalar_equal(
                    value, output
                ):
                    errors.append(
                        f"{label}: decoded.values/{value_index} must equal its "
                        "identity input"
                    )

        required_result_types = None
        if transform_family in {"bucket", "year", "month", "hour"}:
            required_result_types = {"int"}
        elif transform_family == "day":
            required_result_types = {"date"}
        elif transform_family in {"identity", "truncate"} and isinstance(
            source_type, str
        ):
            required_result_types = {source_type}
        elif transform_family == "void" and isinstance(source_type, str):
            required_result_types = {source_type, "int"}

        if required_result_types is not None and isinstance(result_types, list):
            if set(result_types) != required_result_types:
                errors.append(
                    f"{label}: decoded.result_types must be exactly "
                    f"{sorted(required_result_types)}"
                )

    return errors


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    base_validator = jsonschema.Draft202012Validator(_load_schema("cases.base.schema.json"))
    types_validator = jsonschema.Draft202012Validator(_load_schema("cases.types.schema.json"))
    partition_transforms_validator = jsonschema.Draft202012Validator(
        _load_schema("cases.partition-transforms.schema.json")
    )

    files = sorted(glob.glob(f"{root}/table-spec/**/cases.json", recursive=True))
    if not files:
        print("no cases.json files found", file=sys.stderr)
        return 1

    errors = []
    seen_ids = {}
    total = 0
    for path in files:
        try:
            with open(path, encoding="utf-8") as fh:
                doc = json.load(fh)
        except json.JSONDecodeError as e:
            errors.append(f"{path}: invalid JSON: {e}")
            continue

        validators = [base_validator]
        if _is_types_surface(path):
            validators.append(types_validator)
        if _is_partition_transforms_surface(path):
            validators.append(partition_transforms_validator)
        for validator in validators:
            for err in sorted(validator.iter_errors(doc), key=str):
                where = "/".join(str(p) for p in err.absolute_path) or "(root)"
                errors.append(f"{path}: {where}: {err.message}")

        if _is_partition_transforms_surface(path):
            errors.extend(_partition_transform_errors(path, doc))

        cases = doc.get("cases") if isinstance(doc, dict) else None
        if isinstance(cases, list):
            # ids are unique per surface (the top-level dir under table-spec), so a
            # future surface may reuse names like int/string/timestamp.
            parts = _rel(os.path.relpath(path, root)).split("/")
            surface = parts[parts.index("table-spec") + 1] if "table-spec" in parts and parts.index("table-spec") + 1 < len(parts) else parts[0]
            for i, case in enumerate(cases):
                if isinstance(case, dict) and isinstance(case.get("id"), str) and case["id"]:
                    cid = case["id"]
                    key = (surface, cid)
                    if key in seen_ids:
                        errors.append(f"{path}[{i}]: duplicate id '{cid}' in surface '{surface}' (first seen at {seen_ids[key]})")
                    else:
                        seen_ids[key] = f"{path}[{i}]"
            total += len(cases)
            print(f"  {path}: {len(cases)} cases")

    if errors:
        print(f"\nFAILED: {len(errors)} problem(s) in {len(files)} file(s):", file=sys.stderr)
        for e in errors:
            print(f"  {e}", file=sys.stderr)
        return 1

    print(f"\nOK: {total} cases across {len(files)} file(s), {len(seen_ids)} unique ids.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
