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
"""Format the JSON fixtures in one canonical layout.

The top level, each top-level array, and each object in it are expanded one
member per line; anything nested deeper stays on a single line. For a cases.json
that puts every case field on its own line, while a nested value such as
decoded stays compact.

With --check, nothing is rewritten; the script lists unformatted files and
exits nonzero.
"""

import glob
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXPAND_DEPTH = 3
INDENT = "  "


def _compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(", ", ": "))


def _format(value, depth=0):
    if depth >= EXPAND_DEPTH or not isinstance(value, (dict, list)) or not value:
        return _compact(value)
    pad = INDENT * (depth + 1)
    if isinstance(value, dict):
        items = [f"{pad}{_compact(k)}: {_format(v, depth + 1)}" for k, v in value.items()]
        open_, close = "{", "}"
    else:
        items = [f"{pad}{_format(v, depth + 1)}" for v in value]
        open_, close = "[", "]"
    return open_ + "\n" + ",\n".join(items) + "\n" + INDENT * depth + close


def main():
    check = "--check" in sys.argv[1:]
    files = sorted(glob.glob(os.path.join(ROOT, "table-spec", "**", "*.json"), recursive=True))
    unformatted = []
    for path in files:
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        formatted = _format(json.loads(text)) + "\n"
        if formatted == text:
            continue
        rel = os.path.relpath(path, ROOT)
        unformatted.append(rel)
        if not check:
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(formatted)
            print(f"formatted {rel}")

    if check and unformatted:
        print("Unformatted fixtures (run `make format`):", file=sys.stderr)
        for rel in unformatted:
            print(f"  {rel}", file=sys.stderr)
        return 1
    print(f"{len(files)} fixture file(s) checked")
    return 0


if __name__ == "__main__":
    sys.exit(main())
