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
"""Validate conformance fixtures against schemas and surface-specific rules.

Each cases.json is validated against the base structural schema and its surface
schema, when present; case ids must be unique within a surface.
"""

import glob
import json
import os
import re
import struct
import sys
import uuid
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal

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


def _partition_transform_errors(path, doc, check_values=True):
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

        if (
            required_result_types is not None
            and isinstance(result_types, list)
            and all(isinstance(result_type, str) for result_type in result_types)
        ):
            if set(result_types) != required_result_types:
                errors.append(
                    f"{label}: decoded.result_types must be exactly "
                    f"{sorted(required_result_types)}"
                )

    if check_values and not errors:
        errors.extend(_computed_value_errors(path, doc))

    return errors


EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
TIMESTAMP = re.compile(
    r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)?$"
)
MASK = 0xFFFFFFFF


def murmur3_x86_32(data):
    """MurmurHash3 x86 32-bit with seed 0, returned as an unsigned integer."""
    h = 0
    for offset in range(0, len(data) // 4 * 4, 4):
        k = int.from_bytes(data[offset : offset + 4], "little")
        k = (k * 0xCC9E2D51) & MASK
        k = ((k << 15) | (k >> 17)) & MASK
        k = (k * 0x1B873593) & MASK
        h ^= k
        h = ((h << 13) | (h >> 19)) & MASK
        h = (h * 5 + 0xE6546B64) & MASK

    tail = data[len(data) // 4 * 4 :]
    if tail:
        k = int.from_bytes(tail, "little")
        k = (k * 0xCC9E2D51) & MASK
        k = ((k << 15) | (k >> 17)) & MASK
        k = (k * 0x1B873593) & MASK
        h ^= k

    h ^= len(data)
    h ^= h >> 16
    h = (h * 0x85EBCA6B) & MASK
    h ^= h >> 13
    h = (h * 0xC2B2AE35) & MASK
    return (h ^ (h >> 16)) & MASK


def epoch_nanos(value):
    """Exact nanoseconds from the Unix epoch; a value without a zone is read as UTC."""
    match = TIMESTAMP.fullmatch(value)
    if not match:
        raise ValueError(f"invalid timestamp: {value}")
    base, fraction, zone = match.groups()
    instant = datetime.fromisoformat(base + ("+00:00" if zone == "Z" else zone or ""))
    instant = instant.astimezone(timezone.utc) if zone else instant.replace(tzinfo=timezone.utc)
    delta = instant - EPOCH
    seconds = delta.days * 86400 + delta.seconds
    digits = (fraction or "").ljust(9, "0")
    return seconds * 1_000_000_000 + int(digits or "0")


def epoch_units(value, nanos=False):
    nanoseconds = epoch_nanos(value)
    if nanos and nanoseconds < 0 and nanoseconds % 1000:
        raise ValueError("negative nanoseconds between microseconds need a spec rounding rule")
    return nanoseconds // 1000


def decimal_bytes(value):
    decimal = Decimal(value)
    digits = decimal.as_tuple()
    unscaled = int("".join(map(str, digits.digits)))
    if digits.sign:
        unscaled = -unscaled
    length = 1
    while not -(1 << (length * 8 - 1)) <= unscaled < (1 << (length * 8 - 1)):
        length += 1
    return unscaled.to_bytes(length, "big", signed=True)


def parse_decimal(source_type, value):
    """Return (precision, scale, unscaled) for a scale-preserving decimal string."""
    precision, scale = map(
        int, re.fullmatch(r"decimal\((\d+), (\d+)\)", source_type).groups()
    )
    decimal_pattern = (
        r"-?(0|[1-9][0-9]*)"
        + (r"\.[0-9]{" + str(scale) + r"}" if scale else "")
    )
    if not isinstance(value, str) or not re.fullmatch(decimal_pattern, value):
        raise ValueError("decimal must be a scale-preserving string")
    decimal = Decimal(value)
    if len(decimal.as_tuple().digits) > precision:
        raise ValueError(f"decimal must fit precision {precision} and scale {scale}")
    return precision, scale, int(value.replace(".", ""))


def format_decimal(unscaled, scale):
    sign = "-" if unscaled < 0 else ""
    digits = str(abs(unscaled)).rjust(scale + 1, "0")
    if not scale:
        return sign + digits
    return f"{sign}{digits[:-scale]}.{digits[-scale:]}"


def encode_for_hash(source_type, value):
    if source_type == "int":
        if type(value) is not int or not -(1 << 31) <= value < (1 << 31):
            raise ValueError("int must be a 32-bit JSON integer")
        return struct.pack("<q", int(value))
    if source_type == "long":
        if not isinstance(value, str) or not re.fullmatch(r"-?(0|[1-9][0-9]*)", value):
            raise ValueError("long must be a decimal string")
        return struct.pack("<q", int(value))
    if source_type.startswith("decimal("):
        parse_decimal(source_type, value)
        return decimal_bytes(value)
    if source_type == "date":
        if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
            raise ValueError("date must use YYYY-MM-DD")
        days = (date.fromisoformat(value) - date(1970, 1, 1)).days
        return struct.pack("<q", days)
    if source_type == "time":
        if not isinstance(value, str) or not re.fullmatch(
            r"\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?", value
        ):
            raise ValueError("time must use ISO local time")
        clock = time.fromisoformat(value)
        micros = ((clock.hour * 60 + clock.minute) * 60 + clock.second) * 1_000_000
        return struct.pack("<q", micros + clock.microsecond)
    if source_type in ("timestamp", "timestamptz", "timestamp_ns", "timestamptz_ns"):
        if not isinstance(value, str):
            raise ValueError("timestamp must be an ISO string")
        match = TIMESTAMP.fullmatch(value)
        if not match or bool(match[3]) != source_type.startswith("timestamptz"):
            raise ValueError("timestamp zone must match source type")
        if match[2] and len(match[2]) > (9 if source_type.endswith("_ns") else 6):
            raise ValueError("timestamp exceeds source type precision")
        return struct.pack("<q", epoch_units(value, source_type.endswith("_ns")))
    if source_type == "string":
        if not isinstance(value, str):
            raise ValueError("string must be a JSON string")
        return value.encode("utf-8")
    if source_type == "uuid":
        if not isinstance(value, str):
            raise ValueError("uuid must be canonical lowercase text")
        parsed = uuid.UUID(value)
        if str(parsed) != value:
            raise ValueError("uuid must be canonical lowercase text")
        return parsed.bytes
    if source_type == "binary" or source_type.startswith("fixed["):
        if not isinstance(value, str) or not re.fullmatch(r"(?:[0-9A-F]{2})*", value):
            raise ValueError("binary must be uppercase hexadecimal bytes")
        if source_type.startswith("fixed[") and len(value) != 2 * int(
            source_type[6:-1]
        ):
            raise ValueError("fixed value must match its declared byte length")
        return bytes.fromhex(value)
    raise ValueError(f"unsupported bucket source type: {source_type}")


def check_appendix_b_vectors():
    vectors = (
        (struct.pack("<q", 34), 2017239379),
        (struct.pack("<q", 17486), -653330422 & MASK),
        (b"iceberg", 1210000089),
    )
    for data, expected_hash in vectors:
        if murmur3_x86_32(data) != expected_hash:
            raise AssertionError("Murmur3 does not reproduce an Appendix B vector")


def bucket_value(count, source_type, value):
    return (murmur3_x86_32(encode_for_hash(source_type, value)) & 0x7FFFFFFF) % count


def truncate_value(width, source_type, value):
    """Truncate Transform Details; remainders are positive, as Python's % is for W > 0."""
    if source_type in ("int", "long"):
        if source_type == "int":
            if type(value) is not int or not -(1 << 31) <= value < (1 << 31):
                raise ValueError("int must be a 32-bit JSON integer")
            number, bits = value, 32
        else:
            if not isinstance(value, str) or not re.fullmatch(r"-?(0|[1-9][0-9]*)", value):
                raise ValueError("long must be a decimal string")
            number, bits = int(value), 64
            if not -(1 << 63) <= number < (1 << 63):
                raise ValueError("long must fit 64 bits")
        truncated = number - number % width
        if truncated < -(1 << (bits - 1)):
            raise ValueError("the specification does not settle integer overflow")
        return truncated if source_type == "int" else str(truncated)
    if source_type.startswith("decimal("):
        precision, scale, unscaled = parse_decimal(source_type, value)
        truncated = unscaled - unscaled % width
        if len(str(abs(truncated))) > precision:
            raise ValueError("the specification does not settle decimal overflow")
        return format_decimal(truncated, scale)
    if source_type == "string":
        if not isinstance(value, str):
            raise ValueError("string must be a JSON string")
        if any(0xD800 <= ord(char) <= 0xDFFF for char in value):
            raise ValueError("string must hold Unicode scalar values")
        return value[:width]
    if source_type == "binary":
        if not isinstance(value, str) or not re.fullmatch(r"(?:[0-9A-F]{2})*", value):
            raise ValueError("binary must be uppercase hexadecimal bytes")
        return value[: 2 * width]
    raise ValueError(f"unsupported truncate source type: {source_type}")


def temporal_value(transform, source_type, value):
    """year, month, day, and hour as ordinals from the Unix epoch, rounded down."""
    if not isinstance(value, str):
        raise ValueError(f"{source_type} must be an ISO string")
    if source_type == "date":
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
            raise ValueError("date must use YYYY-MM-DD")
        day = date.fromisoformat(value)
        hours = None
    else:
        match = TIMESTAMP.fullmatch(value)
        if not match or bool(match[3]) != source_type.startswith("timestamptz"):
            raise ValueError("timestamp zone must match source type")
        if match[2] and len(match[2]) > (9 if source_type.endswith("_ns") else 6):
            raise ValueError("timestamp exceeds source type precision")
        # Floor division is unambiguous for hour and day; epoch_units refuses the
        # same negative sub-microsecond input for bucket, where the spec does not
        # say how to round to microseconds before hashing.
        nanoseconds = epoch_nanos(value)
        hours = nanoseconds // 3_600_000_000_000
        day = date(1970, 1, 1) + timedelta(days=nanoseconds // 86_400_000_000_000)
    if transform == "year":
        return day.year - 1970
    if transform == "month":
        return (day.year - 1970) * 12 + day.month - 1
    if transform == "day":
        return day.isoformat()
    if transform == "hour" and hours is not None:
        return hours
    raise ValueError(f"unsupported {transform} source type: {source_type}")


UNCHECKED = object()


def expected_value(transform, source_type, value):
    """Recompute a transform result from the spec, or UNCHECKED for a transform not recomputed."""
    bucket = re.fullmatch(r"bucket\[([1-9][0-9]*)\]", transform)
    truncate = re.fullmatch(r"truncate\[([1-9][0-9]*)\]", transform)
    if _transform_family(transform) not in ("bucket", "truncate", "year", "month", "day", "hour"):
        return UNCHECKED
    if not (bucket or truncate or transform in ("year", "month", "day", "hour")):
        raise ValueError(f"malformed transform {json.dumps(transform)}")
    if value is None:
        return None
    if bucket:
        return bucket_value(int(bucket[1]), source_type, value)
    if truncate:
        return truncate_value(int(truncate[1]), source_type, value)
    return temporal_value(transform, source_type, value)


def _computed_value_errors(path, doc):
    check_appendix_b_vectors()

    errors = []
    for case_index, case in enumerate(doc["cases"]):
        transform = case["input"]["transform"]
        source_type = case["input"]["source_type"]
        values = case["input"]["values"]
        expected = case["decoded"]["values"]
        for index, (value, wanted) in enumerate(zip(values, expected)):
            try:
                actual = expected_value(transform, source_type, value)
            except (TypeError, ValueError, OverflowError, UnicodeError, struct.error) as exc:
                errors.append(f"{path}: cases/{case_index}/input/values/{index}: {exc}")
                continue
            if actual is UNCHECKED:
                break
            if not _json_scalar_equal(actual, wanted):
                errors.append(
                    f"{path}: cases/{case_index}/decoded/values/{index}: "
                    f"expected {json.dumps(actual)}, found {json.dumps(wanted)}"
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

        file_error_count = len(errors)
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
            errors.extend(
                _partition_transform_errors(
                    path, doc, check_values=len(errors) == file_error_count
                )
            )

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
