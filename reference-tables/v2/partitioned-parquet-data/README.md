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

# v2/partitioned-parquet-data

Input to `reference-tables/GenerateTables.java`. The schema and partition spec are
the spec's JSON forms. Each commit appends one data file holding its rows, written
in the spec's single-value JSON keyed by field ID, with `long` values as strings.

## Scope

Partitioned scans in a v2 table: an identity partition on a `string` and a `day`
transform on a `timestamptz`, with one data file per partition across two
snapshots.

The two transforms stand for the two ways a partition value relates to its
source: `identity` stores the source value itself, while `day` stores a value
derived from it, of a different type, as `year`, `month` and `hour` also do. A
reader must read both kinds of partition tuple from the manifests. Two rows
share the first partition and the second snapshot adds another, so each snapshot
sees a different set of partitions. Other transforms are left out: their values
are covered by `table-spec/partition-transforms`, and this table checks that
partition metadata is read, not how each transform computes its value.

## Input

```json
{
  "format-version": 2,
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
