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

# v3/initial-default-parquet-data

Input to `reference-tables/GenerateTables.java`. The schema and partition spec are
the spec's JSON forms. Each commit appends one data file holding its rows, written
in the spec's single-value JSON keyed by field ID, with `long` values as strings.
A commit with `schema-updates` instead applies those updates as one schema change:
`add-column` adds a top-level column with its `initial-default`, which also sets
its `write-default`, and `update-default` changes a column's `write-default`.
Rows of later commits are written in the evolved schema.

`expected/scan.json` has no entry for the first snapshot. The spec does not say
which schema a scan at an earlier snapshot projects, so that assertion is left
out until it is settled.

## Input

```json
{
  "format-version": 3,
  "description": "A required int added with an initial-default (42) whose write-default is then changed (10), an optional string added with an initial-default (\"none\"), and an optional string added without a default, after the first append. Two snapshots, four rows; rows written before the change read the initial-defaults and null.",
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
        {"1": "1", "2": "alpha"},
        {"1": "2", "2": "bravo"}
      ]
    },
    {
      "schema-updates": [
        {"action": "add-column", "name": "quantity", "type": "int", "required": true, "initial-default": 42},
        {"action": "add-column", "name": "note", "type": "string", "required": false},
        {"action": "add-column", "name": "label", "type": "string", "required": false, "initial-default": "none"}
      ]
    },
    {
      "schema-updates": [
        {"action": "update-default", "name": "quantity", "write-default": 10}
      ]
    },
    {
      "data-file": "b",
      "rows": [
        {"1": "3", "2": "charlie", "3": 7, "4": "added", "5": "given"},
        {"1": "4", "2": null, "3": 9, "4": null, "5": null}
      ]
    }
  ]
}
```
