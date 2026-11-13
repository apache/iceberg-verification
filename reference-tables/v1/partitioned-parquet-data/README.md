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

# v1/partitioned-parquet-data

Input to `reference-tables/GenerateTables.java`. The schema and partition spec are
the spec's JSON forms. Each commit appends one data file holding its rows, written
in the spec's single-value JSON keyed by field ID, with `long` values as strings.

## Input

```json
{
  "format-version": 1,
  "description": "Identity partition on category and a day transform on event_ts. Two snapshots, one data file per partition, three rows.",
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
        "name": "category",
        "required": true,
        "type": "string"
      },
      {
        "id": 3,
        "name": "event_ts",
        "required": true,
        "type": "timestamptz"
      }
    ]
  },
  "partition-spec": {
    "spec-id": 0,
    "fields": [
      {
        "name": "category",
        "transform": "identity",
        "source-id": 2,
        "field-id": 1000
      },
      {
        "name": "event_day",
        "transform": "day",
        "source-id": 3,
        "field-id": 1001
      }
    ]
  },
  "commits": [
    {
      "data-file": "a",
      "rows": [
        {"1": "1", "2": "books", "3": "2026-01-01T00:00:00.000000+00:00"},
        {"1": "2", "2": "books", "3": "2026-01-01T00:00:00.000000+00:00"}
      ]
    },
    {
      "data-file": "b",
      "rows": [
        {"1": "3", "2": "music", "3": "2026-01-02T00:00:00.000000+00:00"}
      ]
    }
  ]
}
```
