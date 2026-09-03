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

# Java conformance runner

Reference runner that checks [Apache Iceberg (Java)](https://github.com/apache/iceberg)
against the type-surface fixtures. It reads every `table-spec/**/cases.json`,
parses each `input` with the published readers - `org.apache.iceberg.types.Types.fromTypeName`
for a string (primitive/geospatial) type, `org.apache.iceberg.SchemaParser.fromJson`
for an object (nested) type - and applies the assertion
contract in `table-spec/types/README.md` (`valid`/reject; decoded-shape compare,
no bytes, plus byte-exact `canonical` when the case has it). It exits non-zero if any case FAILs. See
[`runners/README.md`](../README.md) for the shared contract.

## How it reaches the implementation

Unlike the Go and Rust runners, this one does not build Iceberg from source: it
depends on the released `org.apache.iceberg:iceberg-api` jar (the primitive/geospatial
parser) and `org.apache.iceberg:iceberg-core` jar (the nested-type reader
`SchemaParser`), version in `build.gradle` (currently 1.11.0), plus
`jackson-databind` to read the fixtures, all from Maven Central. To test
apache/main instead, use `dev/run-local.sh java`, which clones main and wires it
in via Gradle `--include-build`.

## Running it

```sh
# from runners/java
./gradlew run
```

The bundled Gradle wrapper needs no preinstalled Gradle; a JDK 17 is enough. The
runner discovers the repository root (the directory containing `table-spec/`) by
walking up, so it works from any working directory. Output is one line per case
plus totals; the exit code is non-zero on any FAIL.

A type Java does not model is reported UNSUPPORTED, not FAIL.
