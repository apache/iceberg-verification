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
"""Validate the conformance type fixtures against the JSON Schemas in dev/schema/.

Each cases.json is validated against the base structural schema and the types schema
(which requires clause and spec_ref); case ids must be globally unique.
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


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    base_validator = jsonschema.Draft202012Validator(_load_schema("cases.base.schema.json"))
    types_validator = jsonschema.Draft202012Validator(_load_schema("cases.types.schema.json"))

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
        for validator in validators:
            for err in sorted(validator.iter_errors(doc), key=str):
                where = "/".join(str(p) for p in err.absolute_path) or "(root)"
                errors.append(f"{path}: {where}: {err.message}")

        cases = doc.get("cases") if isinstance(doc, dict) else None
        if isinstance(cases, list):
            for i, case in enumerate(cases):
                if isinstance(case, dict) and isinstance(case.get("id"), str) and case["id"]:
                    cid = case["id"]
                    if cid in seen_ids:
                        errors.append(f"{path}[{i}]: duplicate id '{cid}' (first seen at {seen_ids[cid]})")
                    else:
                        seen_ids[cid] = f"{path}[{i}]"
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
