<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~   http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing,
  ~ software distributed under the License is distributed on an
  ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~ KIND, either express or implied.  See the License for the
  ~ specific language governing permissions and limitations
  ~ under the License.
  -->

# Python conformance runner

Reference runner that checks [pyiceberg](https://github.com/apache/iceberg-python)
against the type-surface fixtures. It reads every `table-spec/**/cases.json`,
parses each `input` with pyiceberg's own type parser
(`pyiceberg.types.IcebergType.model_validate`), and applies the assertion
contract in `runners/README.md` and `table-spec/types/README.md` (`valid`/reject;
decoded-shape compare, no bytes, plus byte-exact `canonical` when the case has it). It exits non-zero if any case FAILs.

See `runners/README.md` for the shared runner contract; this README only covers
how to run the Python one.

## Running it

The runner depends on an installed `pyiceberg`. Install a released build and run
it:

```sh
# from runners/python, using uv
uv venv --python 3.12
source .venv/bin/activate
uv pip install pyiceberg

# or with plain pip
python -m venv .venv && source .venv/bin/activate
pip install pyiceberg

python runner.py
```

`python runner.py` discovers the repository root (the directory containing
`table-spec/`) by walking up, so it works from any working directory. Output is
one line per case plus totals; the exit code is non-zero on any FAIL.

A type pyiceberg does not model is reported UNSUPPORTED, not FAIL.
Today `variant` is UNSUPPORTED: pyiceberg has no variant type, so its parser
rejects the `variant` keyword outright rather than a specific malformed form.
