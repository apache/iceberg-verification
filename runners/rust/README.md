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

# Rust conformance runner

Reference runner that checks [iceberg-rust](https://github.com/apache/iceberg-rust)
against the type-surface fixtures. It reads every `table-spec/**/cases.json`,
deserializes each `input` into iceberg-rust's own `Type` with `serde_json`, and
applies the assertion contract in `table-spec/types/README.md` (`valid`/reject;
decoded-shape compare, no bytes, plus byte-exact `canonical` when the case has it).
It exits non-zero if any case FAILs. See [`../README.md`](../README.md) for the
shared contract that every language runner follows.

## Running it

The runner depends on the released `iceberg` crate (the version in `Cargo.toml`);
`cargo run` resolves it from crates.io. To test apache/main instead, use
`dev/run-local.sh rust`, which clones main and wires it in via `[patch.crates-io]`.

```sh
# from runners/rust (latest release)
cargo run
```

`cargo run` discovers the repository root (the directory containing `table-spec/`)
by walking up, so it works from any working directory. Output is one line per
case plus totals; the exit code is non-zero on any FAIL.

## Expected outcomes

A type iceberg-rust's `Type` enum does not model is reported UNSUPPORTED, not
FAIL. The shipped fixtures exercise this for `unknown`, `geometry`, and
`geography` (iceberg-rust has `struct` / `list` / `map` / `variant` and the v3
`timestamp_ns` / `timestamptz_ns`, but not `unknown` or the geospatial types).

Two cases FAIL today, and these are real iceberg-rust divergences from the
spec-derived expectation, not runner bugs:

- `decimal-precision-over-38` - iceberg-rust accepts `decimal(39, 0)`; the spec
  caps precision at 38.
- `fixed-unterminated` - iceberg-rust accepts the unterminated `fixed[16`.

Both should stay red until iceberg-rust tightens its parser (or the community
decides otherwise).
