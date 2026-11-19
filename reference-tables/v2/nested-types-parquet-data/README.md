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

# v2/nested-types-parquet-data

Input to `reference-tables/GenerateTables.java`. The schema and partition spec are
the spec's JSON forms. Each commit appends one data file holding its rows, written
in the spec's single-value JSON keyed by field ID, with `long` values as strings.

## Scope

Field IDs in nested types: a struct, a list and a map, in four rows that hold
them populated, null, empty, and holding a null.

Struct fields, list elements, and map keys and values each get their own field
IDs, so between them the three types use every kind of nested ID a schema
assigns, and a reader must resolve each by ID rather than by name or position.
The rows separate the states that Parquet's three-level lists and maps encode
differently: a populated container, a null container, an empty list and map, and
a list element and a map value that are null. The struct's fields are required,
so the struct itself is its only optional level. Partitioning, deletes and the
breadth of primitive types are left to other tables.

## Input

```json
{
  "format-version": 2,
  "description": "Struct, list and map, so every nested id space appears. One snapshot, four rows: populated containers, null containers, empty containers, and a null list element and null map value.",
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
        "name": "location",
        "required": false,
        "type": {
          "type": "struct",
          "fields": [
            {
              "id": 5,
              "name": "lat",
              "required": true,
              "type": "double"
            },
            {
              "id": 6,
              "name": "lon",
              "required": true,
              "type": "double"
            }
          ]
        }
      },
      {
        "id": 3,
        "name": "tags",
        "required": false,
        "type": {
          "type": "list",
          "element-id": 7,
          "element": "string",
          "element-required": false
        }
      },
      {
        "id": 4,
        "name": "props",
        "required": false,
        "type": {
          "type": "map",
          "key-id": 8,
          "key": "string",
          "value-id": 9,
          "value": "int",
          "value-required": false
        }
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
        {"1": "1", "2": {"5": 40.7128, "6": -74.006}, "3": ["east", "coastal"], "4": {"keys": ["pop"], "values": [8336817]}},
        {"1": "2", "2": null, "3": null, "4": null},
        {"1": "3", "2": {"5": 51.5074, "6": -0.1278}, "3": [], "4": {"keys": [], "values": []}},
        {"1": "4", "2": null, "3": ["west", null], "4": {"keys": ["pop"], "values": [null]}}
      ]
    }
  ]
}
```
