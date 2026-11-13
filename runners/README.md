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

A runner parses each fixture `input` with one implementation's own type parser and
grades the result. Each lives under `runners/<language>/` with its own workflow, so
one runner's break can't redden another's.

## Support

| Runner | Surfaces | CI |
| --- | --- | --- |
| [Java](java/) | `types` | [![Java](https://github.com/nssalian/iceberg-verification/actions/workflows/conformance-java.yml/badge.svg)](https://github.com/nssalian/iceberg-verification/actions/workflows/conformance-java.yml) |
| Go | _planned_ | - |
| Rust | _planned_ | - |
| Python | _planned_ | - |

Surfaces are the fixture sets a runner opts into (`table-spec/manifest.json`); `schema`
has fixtures but no runner yet. The CI column is this repo's own run status, not a
conformance ranking of the implementations.

Each case grades into four states (full contract in `table-spec/types/README.md`):

- **pass** - matches the expectation.
- **fail** - a `must` violation; the only state that exits non-zero.
- **advisory_fail** - a failed `normative_level: should` case; reported, never blocks.
- **skip** - not run here (surface not subscribed, or the type isn't modeled).

`--surface <name>` (or `CONFORMANCE_SURFACES`) limits the run to a surface in
`table-spec/manifest.json`; the default runs every surface the runner implements. A
build or setup failure exits 2 (ERROR), distinct from a conformance `fail`. Output is
one line per case plus a summary:

```
TOTALS: N cases | pass=.. fail=.. advisory_fail=.. skip=..
FAIL ids: [...]
ADVISORY_FAIL ids: [...]
SKIP ids: [...]
```

The Java runner implements the `types` surface against the latest `iceberg-api`
SNAPSHOT (no release carries the v3 types yet). Go, Rust, Python, and a nightly lane
are not part of this change.

## Consuming the fixtures

Vendor this repo as a pinned git submodule and run the fixtures in your own CI:

```
git submodule add https://github.com/apache/iceberg-verification conformance
```

Then reuse a runner here (pointed at your build via `--include-build`, scoped with
`--surface`) or write your own check over `conformance/table-spec/<surface>/**/cases.json`:
parse `input`, compare to `decoded` (and `canonical` if present), report the four
states. Only `fail` blocks.
