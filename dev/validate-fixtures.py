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
"""Validate the conformance fixtures against the per-surface JSON Schemas.

Also checks ids are globally unique and every fixture dir is listed in the manifest.
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


def _check_manifest(root, files, errors):
    """Every fixture dir must be listed in the manifest, and every listed dir must exist."""
    mpath = os.path.join(root, "table-spec", "manifest.json")
    if not os.path.exists(mpath):
        errors.append(f"{mpath}: missing surfaces manifest")
        return
    try:
        with open(mpath, encoding="utf-8") as fh:
            manifest = json.load(fh)
    except json.JSONDecodeError as e:
        errors.append(f"{mpath}: invalid JSON: {e}")
        return
    surfaces = manifest.get("surfaces") if isinstance(manifest, dict) else None
    if not isinstance(surfaces, list):
        errors.append(f"{mpath}: 'surfaces' must be an array")
        return

    listed = set()
    for s in surfaces:
        spath = s.get("path") if isinstance(s, dict) else None
        if not isinstance(spath, str) or not spath:
            errors.append(f"{mpath}: a surface is missing a string 'path'")
            continue
        if not os.path.isdir(os.path.join(root, spath)):
            errors.append(f"{mpath}: surface path '{spath}' does not exist")
        for sd in s.get("subdirs") or []:
            listed.add(f"{spath}/{sd}")
            if not os.path.exists(os.path.join(root, spath, sd, "cases.json")):
                errors.append(f"{mpath}: '{spath}/{sd}' is listed but has no cases.json")

    for path in files:
        d = _rel(os.path.dirname(os.path.relpath(path, root)))
        if d not in listed:
            errors.append(f"{path}: fixture dir '{d}' is not listed in {mpath}")


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

    _check_manifest(root, files, errors)

    if errors:
        print(f"\nFAILED: {len(errors)} problem(s) in {len(files)} file(s):", file=sys.stderr)
        for e in errors:
            print(f"  {e}", file=sys.stderr)
        return 1

    print(f"\nOK: {total} cases across {len(files)} file(s), {len(seen_ids)} unique ids.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
