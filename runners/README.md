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

# Runners

A runner is a small program in one language that checks that implementation
against the fixtures. Each is self-contained under `runners/<language>/` and is
driven by one isolated CI workflow (`.github/workflows/conformance-<language>.yml`),
so a break in one language's runner cannot redden another's.

Every runner does the same thing; only the parser it calls differs:

1. Read every `table-spec/**/cases.json` (a JSON object with a `cases` array).
2. For each case, parse `input` with the implementation's own type parser.
3. Apply the surface's assertion (see `table-spec/types/README.md`):
   - `valid: false` -> the parser must reject `input`.
   - `valid: true` -> parse succeeds and the decoded shape equals `decoded`; if
     the case has `canonical`, the re-serialized type must also equal it byte for byte.
4. Report a type the implementation does not model as UNSUPPORTED.
5. Exit non-zero if any case FAILs. UNSUPPORTED does not fail the run.

The three outcomes are distinct on purpose: FAIL is a genuine divergence from the
spec-derived expectation; UNSUPPORTED is a type the implementation has not built
yet (a skip-list candidate); ERROR is a build or setup failure, not a verdict.

## How a runner reaches its implementation

In CI each runner tests the implementation's latest published release:

| Language | Runner | Latest-release dependency |
| --- | --- | --- |
| Go | `runners/go/` | the `iceberg-go` module required in `go.mod` |
| Java | `runners/java/` | the `org.apache.iceberg:iceberg-api` jar from Maven Central |
| Python | `runners/python/` | the `pyiceberg` package installed by the workflow |
| Rust | `runners/rust/` | the `iceberg` crate from crates.io |

C++ has no runner yet.

### Nightly lane

`conformance-nightly.yml` runs all four daily and renders the status matrix (and
README badges). It tests the same release pins, except Java, which tracks the
moving `1.12.0-SNAPSHOT` from the ASF snapshot repository. Report-only, non-gating.

### Local runs against apache/main

`dev/run-local.sh <lang>` clones the implementation's latest `apache/main`, wires
the runner against that source the way the CI job does (Go `-replace`, Rust
`[patch.crates-io]`, Python `pip install <clone>`, Java `--include-build`), runs
it, and prints the report. The tested commit is shown in the output.

## Push and pull

These runners are the push model: this repository runs them in CI against each
implementation. A consumer may instead vendor the fixtures and run an equivalent
check in its own CI (the pull model). Where runners should ultimately live -
here or in each implementation's repo - is still an open question for the
community; they are kept here for now so the push-model CI has something to run.
