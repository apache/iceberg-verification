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
"""Validate the conformance fixtures.

Reads every table-spec/**/cases.json and checks each case: a unique id, a boolean
`valid`, an `input`, and a `decoded` when `valid` is true. Prints every problem
and exits non-zero if any are found.
"""

import glob
import json
import sys

CASE_GLOBS = ["table-spec/**/cases.json"]


def validate_case(case, where, errors, seen_ids, require_spec):
    if not isinstance(case, dict):
        errors.append(f"{where}: case is not a JSON object")
        return

    cid = case.get("id")
    if not isinstance(cid, str) or not cid:
        errors.append(f"{where}: missing or empty string 'id'")
    elif cid in seen_ids:
        errors.append(f"{where}: duplicate id '{cid}' (first seen at {seen_ids[cid]})")
    else:
        seen_ids[cid] = where

    valid = case.get("valid")
    if not isinstance(valid, bool):
        errors.append(f"{where}: 'valid' must be a boolean")

    if "input" not in case:
        errors.append(f"{where}: missing 'input'")

    if valid is True and "decoded" not in case:
        errors.append(f"{where}: valid case must have 'decoded'")
    if valid is False and "decoded" in case:
        errors.append(f"{where}: invalid case must not have 'decoded'")

    # `canonical` is an optional types-surface field: the exact re-serialized
    # string. It only makes sense on a valid case and must be a string.
    if "canonical" in case:
        if valid is not True:
            errors.append(f"{where}: 'canonical' is only allowed on a valid case")
        elif not isinstance(case["canonical"], str):
            errors.append(f"{where}: 'canonical' must be a string")

    # The types surface requires a spec citation on every case; other surfaces
    # (e.g. schema) do not.
    if require_spec:
        for field in ("clause", "spec_ref"):
            if not isinstance(case.get(field), str) or not case.get(field):
                errors.append(f"{where}: missing spec citation '{field}'")


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    files = sorted({p for g in CASE_GLOBS for p in glob.glob(f"{root}/{g}", recursive=True)})
    if not files:
        print("no cases.json files found", file=sys.stderr)
        return 1

    errors = []
    seen_ids = {}
    total = 0
    for path in files:
        require_spec = "/table-spec/types/" in path or path.startswith("table-spec/types/")
        try:
            with open(path, encoding="utf-8") as fh:
                doc = json.load(fh)
        except json.JSONDecodeError as e:
            errors.append(f"{path}: invalid JSON: {e}")
            continue
        cases = doc.get("cases") if isinstance(doc, dict) else None
        if not isinstance(cases, list):
            errors.append(f"{path}: top level must be an object with a 'cases' array")
            continue
        for i, case in enumerate(cases):
            validate_case(case, f"{path}[{i}]", errors, seen_ids, require_spec)
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
