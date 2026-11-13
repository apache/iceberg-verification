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

# v2/simple-append-parquet-data

Input to `reference-tables/GenerateTables.java`. The schema and partition spec are
the spec's JSON forms. Each commit appends one data file holding its rows, written
in the spec's single-value JSON keyed by field ID, with `long` values as strings.

## Input

```json
{
  "format-version": 2,
  "description": "Flat schema of long, string and timestamptz. Two appends, four rows, one null.",
  "schema": {
    "type": "struct",
    "schema-id": 0,
    "fields": [
      {
        "id": 1,
        "name": "id",
        "required": true,
        "type": "long"
      },
      {
        "id": 2,
        "name": "data",
        "required": false,
        "type": "string"
      },
      {
        "id": 3,
        "name": "event_ts",
        "required": false,
        "type": "timestamptz"
      }
    ]
  },
  "partition-spec": {
    "spec-id": 0,
    "fields": []
  },
  "commits": [
    {
      "data-file": "a",
      "rows": [
        {"1": "1", "2": "alpha", "3": "2026-01-01T00:00:00.000000+00:00"},
        {"1": "2", "2": "bravo", "3": "2026-01-02T00:00:00.000000+00:00"}
      ]
    },
    {
      "data-file": "b",
      "rows": [
        {"1": "3", "2": "charlie", "3": "2026-01-03T00:00:00.000000+00:00"},
        {"1": "4", "2": null, "3": "2026-01-04T00:00:00.000000+00:00"}
      ]
    }
  ]
}
```
