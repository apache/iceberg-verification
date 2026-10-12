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

[![Conformance (Java)](https://github.com/nssalian/iceberg-verification/actions/workflows/conformance-java.yml/badge.svg)](https://github.com/nssalian/iceberg-verification/actions/workflows/conformance-java.yml)

Checks [Apache Iceberg (Java)](https://github.com/apache/iceberg) against the
`table-spec/types` fixtures. See [`runners/README.md`](../README.md) for the shared
four-state contract.

## Running it

```sh
# from runners/java (bundled wrapper, JDK 17)
./gradlew run                                 # against the build.gradle floor
./gradlew run -PicebergVersion=<x>-SNAPSHOT   # against a dev SNAPSHOT (CI uses this)
```

Depends on `iceberg-api` + `iceberg-core` (the type parsers) and `jackson-databind`,
from Maven Central. Prints one line per case plus totals; exits non-zero on any fail.
