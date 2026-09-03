#!/usr/bin/env python3
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

"""Reference runner that checks pyiceberg against the type-surface fixtures.

It reads every table-spec/**/cases.json (each a JSON object {"cases": [...]}),
parses each `input` with pyiceberg's own type parser
(pyiceberg.types.IcebergType.model_validate), and applies the case contract:

    valid=false             -> the parser must raise (PASS); if it parses, FAIL
    valid=true              -> parse ok and the decoded shape == `decoded`;
                               if the parser raises for a type pyiceberg does
                               not model at all, the case is UNSUPPORTED, not a
                               failure

The process exits non-zero if any case FAILs.
"""

import json
import os
import sys

from pyiceberg.types import (
    DecimalType,
    FixedType,
    IcebergType,
    ListType,
    MapType,
    PrimitiveType,
    StructType,
)

# Geometry/Geography were added after some pyiceberg releases; tolerate their
# absence so the runner still loads. Those cases then parse-fail and are handled
# by the usual UNSUPPORTED/FAIL logic.
try:
    from pyiceberg.types import GeographyType, GeometryType
except ImportError:
    GeometryType = GeographyType = None

# Directory under the repo root that holds the type-surface cases.
SURFACE_ROOT = "table-spec"

# Keywords pyiceberg's handle_primitive_type validator recognizes. Mirrors the
# branches in pyiceberg/types.py: if a type keyword is NOT here, pyiceberg lacks
# the type entirely (UNSUPPORTED) rather than rejecting a specific form (FAIL).
EXACT_KEYWORDS = {
    "boolean", "string", "int", "long", "float", "double",
    "timestamp", "timestamptz", "timestamp_ns", "timestamptz_ns",
    "date", "time", "uuid", "binary", "unknown",
}
PREFIX_KEYWORDS = ("fixed", "decimal", "geometry", "geography")


def find_repo_root(start):
    """Walk up from start until a directory containing table-spec/ is found."""
    d = os.path.abspath(start)
    while True:
        if os.path.isdir(os.path.join(d, SURFACE_ROOT)):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            raise RuntimeError(
                f"could not locate repo root (no {SURFACE_ROOT}/) above {start}"
            )
        d = parent


def load_cases(root):
    """Read every cases.json under the surface root, sorted by id."""
    cases = []
    base = os.path.join(root, SURFACE_ROOT)
    for dirpath, _dirnames, filenames in os.walk(base):
        if "cases.json" not in filenames:
            continue
        path = os.path.join(dirpath, "cases.json")
        with open(path) as f:
            doc = json.load(f)
        for c in doc["cases"]:
            c["_source"] = os.path.relpath(path, root)
            cases.append(c)
    cases.sort(key=lambda c: c["id"])
    return cases


def is_recognized(inp):
    """True if pyiceberg models this type at all (else UNSUPPORTED)."""
    if isinstance(inp, dict):
        return inp.get("type") in ("struct", "list", "map")
    if inp in EXACT_KEYWORDS:
        return True
    return any(inp.startswith(p) for p in PREFIX_KEYWORDS)


def decoded_shape(t):
    """Map a pyiceberg type object to the fixture's language-neutral shape.

    Every value is read from the parsed object `t`; only the tag/key names are
    literal (the neutral vocabulary shared with the fixtures).
    """
    if isinstance(t, DecimalType):
        return {"type": "decimal", "precision": t.precision, "scale": t.scale}
    if isinstance(t, FixedType):
        return {"type": "fixed", "length": len(t)}
    if GeographyType is not None and isinstance(t, GeographyType):
        return {"type": "geography", "crs": t.crs, "algorithm": t.algorithm}
    if GeometryType is not None and isinstance(t, GeometryType):
        return {"type": "geometry", "crs": t.crs}
    if isinstance(t, StructType):
        return {"type": "struct", "fields": [
            {"id": f.field_id, "name": f.name, "required": f.required,
             "type": decoded_shape(f.field_type)} for f in t.fields]}
    if isinstance(t, ListType):
        return {"type": "list", "element-id": t.element_id,
                "element-required": t.element_required,
                "element": decoded_shape(t.element_type)}
    if isinstance(t, MapType):
        return {"type": "map", "key-id": t.key_id, "key": decoded_shape(t.key_type),
                "value-id": t.value_id, "value-required": t.value_required,
                "value": decoded_shape(t.value_type)}
    if isinstance(t, PrimitiveType):
        return {"type": json.loads(t.model_dump_json())}
    raise TypeError(f"unmapped type object: {t!r}")


def main():
    start = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
    try:
        root = find_repo_root(start)
    except RuntimeError as e:
        print(e, file=sys.stderr)
        sys.exit(2)

    cases = load_cases(root)
    if not cases:
        print(f"no cases found under {root}", file=sys.stderr)
        sys.exit(2)

    results = []  # (id, status, detail)
    for c in cases:
        cid = c["id"]
        inp = c["input"]
        valid = c["valid"]

        parsed_obj = None
        err = None
        try:
            parsed_obj = IcebergType.model_validate(inp)
        except Exception as e:  # noqa: BLE001 - see valid-branch handling below
            err = e

        if not valid:
            if err is not None:
                results.append((cid, "PASS", f"rejected: {type(err).__name__}"))
            else:
                results.append((cid, "FAIL",
                                f"expected reject, parsed as {decoded_shape(parsed_obj)!r}"))
            continue

        if err is not None:
            if not is_recognized(inp):
                results.append((cid, "UNSUPPORTED",
                                f"pyiceberg lacks type ({type(err).__name__})"))
            else:
                results.append((cid, "FAIL",
                                f"expected accept, parse raised {type(err).__name__}: {err}"))
            continue

        got = decoded_shape(parsed_obj)
        want = c["decoded"]
        if got != want:
            results.append((cid, "FAIL", f"decoded mismatch: expected {want}, actual {got}"))
            continue

        if "canonical" in c:
            want_canon = c["canonical"]
            got_canon = json.loads(parsed_obj.model_dump_json())
            if got_canon != want_canon:
                results.append((cid, "FAIL",
                                f"canonical mismatch: expected {want_canon!r}, actual {got_canon!r}"))
                continue

        results.append((cid, "PASS", ""))

    width = max(len(cid) for cid, _, _ in results)
    for cid, status, detail in results:
        line = f"{cid.ljust(width)}  {status}"
        if detail and status != "PASS":
            line += f"  ({detail})"
        print(line)

    counts = {"PASS": 0, "FAIL": 0, "UNSUPPORTED": 0, "SKIP": 0}
    for _, status, _ in results:
        counts[status] = counts.get(status, 0) + 1

    print(f"\nTOTALS: {len(results)} cases | "
          f"PASS={counts['PASS']} FAIL={counts['FAIL']} "
          f"UNSUPPORTED={counts['UNSUPPORTED']} SKIP=0")

    fail_ids = [cid for cid, s, _ in results if s == "FAIL"]
    unsupported_ids = [cid for cid, s, _ in results if s == "UNSUPPORTED"]
    if fail_ids:
        print(f"FAIL ids: {fail_ids}")
    if unsupported_ids:
        print(f"UNSUPPORTED ids: {unsupported_ids}")

    sys.exit(1 if fail_ids else 0)


if __name__ == "__main__":
    main()
