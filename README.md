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

# Apache Iceberg Verification

A repository of conformance fixtures for
[Apache Iceberg](https://iceberg.apache.org/) implementations. Language-neutral
inputs, each paired with the expected value the spec fixes.

## Motivation

The Iceberg [specification](https://iceberg.apache.org/spec/) is prose. Each
implementation parses and serializes it on its own, so they drift. Tests
maintained in isolation in each implementation cannot catch this, because each
checks its own reading of the spec against itself. A shared misreading stays
green and often surfaces only as a post-release bug report.

Expected values here are derived from the spec, not copied from an
implementation, so a fixture catches both implementation bugs and genuine spec
ambiguity. The expected value is single-sourced, so a corrected reading is
corrected in one place.

## Scope

Initial scope is read conformance. A reference writer emits each fixture, and
every implementation verifies that it consumes the fixture correctly. Writer
conformance is a later phase.

Fixtures are what everything else is built on. Tooling that runs them may
follow, and today each consumer writes its own assertions.

This repository validates artifacts, not behavior. Engine query results, live
protocol behavior, and physical encoding choices such as compression codec and
file format writer version are out of scope. Any spec-valid encoding is valid.

Adoption is by self-election and is incremental. An implementation pins this
repository to a commit, runs the surfaces it opts into, and bumps that pin
deliberately. There is no central conformance gate and no pass/fail matrix
across implementations.

## Contributing

TODO: `CONTRIBUTING.md`, covering repository layout, fixture format, and how to
add or correct a fixture.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

Fixture files are test data and carry no license header, per the
[ASF Source Header and Copyright Notice Policy](https://www.apache.org/legal/src-headers.html),
which exempts "test data for which the addition of a source header would cause
the tests to fail". Every other file carries the standard ASF source header.
