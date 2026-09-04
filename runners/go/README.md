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

# Go conformance runner

Reference runner that checks [iceberg-go](https://github.com/apache/iceberg-go)
against the type-surface fixtures. It reads every `table-spec/**/cases.json`,
parses each `input` with iceberg-go's own type parser, and applies the assertion
contract in `table-spec/types/README.md` (`valid`/reject; decoded-shape compare,
no bytes, plus byte-exact `canonical` when the case has it). It exits non-zero if any case FAILs.

## Where this fits (open question)

The community has not yet decided whether runners live in this repository or in
each implementation's own repo. This runner is committed here so the push-model
CI has something to execute; a consumer may equally vendor the fixtures and run
an equivalent check in its own CI (the pull model). See the proposal.

## Running it

The runner depends on the released `iceberg-go` module (the version in `go.mod`);
`go run .` resolves it. To test apache/main instead, use `dev/run-local.sh go`,
which clones main and wires it in via `go mod -replace`.

```sh
# from runners/go (latest release)
go mod tidy
go run .
```

`go run .` discovers the repository root (the directory containing `table-spec/`)
by walking up, so it works from any working directory. Output is one line per
case plus totals; the exit code is non-zero on any FAIL.

A type iceberg-go does not model is reported UNSUPPORTED, not FAIL.
