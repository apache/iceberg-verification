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

# v3/deletion-vector-parquet-data

Input to `reference-tables/GenerateTables.java`. The schema and partition spec are
the spec's JSON forms. The first commit appends two data files in one snapshot,
each holding its rows, written in the spec's single-value JSON keyed by field ID,
with `long` values as strings. The second commit adds one deletion vector for the
named data file, setting the listed row positions, also written as strings.

## Input

```json
{
  "format-version": 3,
  "description": "Flat schema of long, string and int. One append of two data files (five and two rows), then a deletion vector that deletes positions 0 and 3 of the first file only.",
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
        "name": "count",
        "required": false,
        "type": "int"
      }
    ]
  },
  "partition-spec": {
    "spec-id": 0,
    "fields": []
  },
  "commits": [
    {
      "data-files": [
        {
          "data-file": "a",
          "rows": [
            {"1": "1", "2": "alpha", "3": 10},
            {"1": "2", "2": "bravo", "3": 20},
            {"1": "3", "2": "charlie", "3": null},
            {"1": "4", "2": null, "3": 40},
            {"1": "5", "2": "echo", "3": 50}
          ]
        },
        {
          "data-file": "b",
          "rows": [
            {"1": "6", "2": "foxtrot", "3": 60},
            {"1": "7", "2": "golf", "3": 70}
          ]
        }
      ]
    },
    {
      "deletion-vector": {
        "data-file": "a",
        "positions": ["0", "3"]
      }
    }
  ]
}
```
