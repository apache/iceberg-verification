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

# v2/partial-field-ids-parquet-data

A table with `schema.name-mapping.default` set and one Parquet data file that
carries field IDs on some fields and not others. The file has field IDs on every
top-level column and on the struct's fields. It has none on the list element, the
map key or the map value.

Each ID-carrying column has a physical name the name mapping does not list (`c1`,
`c2`, and so on), so it resolves only by field ID. The physical column order
differs from the table schema's, inside the struct as well. The list and map use
the three-level form of the spec's Parquet mapping, so the ID-less fields are
named `element`, `key` and `value`, which are the names the mapping gives them.
Field 6, `added`, is in the table schema and the name mapping and is not in the
file.

| Field ID | Table name | Physical name | Field ID in the file |
| -- | -- | -- | -- |
| 1 | `id` | `c1` | yes |
| 2 | `name` | `c2` | yes |
| 3 | `location` | `c3` | yes |
| 7 | `location.lat` | `c3.c7` | yes |
| 8 | `location.lon` | `c3.c8` | yes |
| 4 | `tags` | `c4` | yes |
| 9 | `tags.element` | `c4.list.element` | no |
| 5 | `props` | `c5` | yes |
| 10 | `props.key` | `c5.key_value.key` | no |
| 11 | `props.value` | `c5.key_value.value` | no |
| 6 | `added` | not in the file | |

## Scope

Partial field IDs in a Parquet data file: every top-level column and the struct's
fields carry IDs, while the list element, map key and map value do not, with a
name mapping that names the physical columns. The open case is how a reader
resolves the ID-less fields, so the table has no `expected/scan.json` until the
question on dev@iceberg.apache.org is settled. The values the spec already fixes
are stated in "What the spec already fixes".

## Waiting on

This table has no `expected/scan.json` and no `index.json` entry yet. The spec
does not settle how a reader resolves the fields of this file that carry no field
ID, and the question is under discussion on dev@iceberg.apache.org in
[Name mapping for data files that carry only some field IDs](https://lists.apache.org/thread/vgxtsjvcfhjq06lcg3mnstp2fyglgzbd).

Two sentences of the spec's
[Column Projection](https://iceberg.apache.org/spec/#column-projection) section
bear on it. One says to use `schema.name-mapping.default` "to map field id to
columns without field id". The other says the mappings provide fallback field IDs
"when a data file does not contain field id information". This file contains
field ID information, and three of its fields have none.

The open question is what a read of fields 4 and 5 returns. Read field by field,
under the first sentence, the ID-less element, key and value take their IDs from
the mapping. Read file by file, under the second, the mapping does not apply to
this file, and the question becomes what a list or map whose element, key or
value has no field ID reads as: a null list or map, a failure, or a behavior the
spec leaves unspecified.

The expected values are written once the thread settles that.

## What the spec already fixes

Unless the answer is that a file like this one cannot be read at all, the spec
fixes these values for a scan of this table that does not project fields 4 and 5.
They are stated here rather than as a partial `expected/scan.json`, because the
reference tables have no format for a scan that covers only some fields.

- Fields 1, 2, 3, 7 and 8 are read from columns `c1`, `c2`, `c3`, `c3.c7` and
  `c3.c8` by their field IDs, as the rows in the Input give them. Columns are
  selected by field ID. Under the first sentence above the mapping applies only
  to columns without a field ID, and under the second it does not apply to a file
  that carries field IDs, so neither reading lets it resolve these columns.
- Field 6 reads as `null` in every row. The table is unpartitioned, the file has
  no column for it, and it has no `initial-default`. The mapping names it `added`,
  and the file has no column of that name.

A projection of one of these columns, or of these columns in another order,
selects the same values by field ID.

## Input

Input to `reference-tables/GenerateTables.java`. The schema, partition spec and
name mapping are the spec's JSON forms. The commit gives the data file's own
schema, whose field IDs key the rows and are written to the file except those in
`without-field-ids`. Rows are in the spec's single-value JSON keyed by field ID,
with `long` values as strings.

```json
{
  "format-version": 2,
  "description": "Name mapping set, and one Parquet data file with field IDs on its top-level columns and struct fields but not on its list element, map key or map value. ID-carrying columns have names the mapping does not list. One snapshot, three rows.",
  "properties": {
    "schema.name-mapping.default": [
      {"field-id": 1, "names": ["id"]},
      {"field-id": 2, "names": ["name"]},
      {
        "field-id": 3,
        "names": ["location"],
        "fields": [
          {"field-id": 7, "names": ["lat"]},
          {"field-id": 8, "names": ["lon"]}
        ]
      },
      {"field-id": 4, "names": ["tags"], "fields": [{"field-id": 9, "names": ["element"]}]},
      {
        "field-id": 5,
        "names": ["props"],
        "fields": [
          {"field-id": 10, "names": ["key"]},
          {"field-id": 11, "names": ["value"]}
        ]
      },
      {"field-id": 6, "names": ["added"]}
    ]
  },
  "schema": {
    "type": "struct",
    "schema-id": 0,
    "fields": [
      {"id": 1, "name": "id", "required": true, "type": "long"},
      {"id": 2, "name": "name", "required": false, "type": "string"},
      {
        "id": 3,
        "name": "location",
        "required": false,
        "type": {
          "type": "struct",
          "fields": [
            {"id": 7, "name": "lat", "required": true, "type": "double"},
            {"id": 8, "name": "lon", "required": true, "type": "double"}
          ]
        }
      },
      {
        "id": 4,
        "name": "tags",
        "required": false,
        "type": {"type": "list", "element-id": 9, "element": "string", "element-required": false}
      },
      {
        "id": 5,
        "name": "props",
        "required": false,
        "type": {
          "type": "map",
          "key-id": 10,
          "key": "string",
          "value-id": 11,
          "value": "int",
          "value-required": false
        }
      },
      {"id": 6, "name": "added", "required": false, "type": "string"}
    ]
  },
  "partition-spec": {
    "spec-id": 0,
    "fields": []
  },
  "commits": [
    {
      "data-file": "a",
      "file-schema": {
        "type": "struct",
        "schema-id": 0,
        "fields": [
          {
            "id": 4,
            "name": "c4",
            "required": false,
            "type": {"type": "list", "element-id": 9, "element": "string", "element-required": false}
          },
          {"id": 1, "name": "c1", "required": true, "type": "long"},
          {
            "id": 5,
            "name": "c5",
            "required": false,
            "type": {
              "type": "map",
              "key-id": 10,
              "key": "string",
              "value-id": 11,
              "value": "int",
              "value-required": false
            }
          },
          {
            "id": 3,
            "name": "c3",
            "required": false,
            "type": {
              "type": "struct",
              "fields": [
                {"id": 8, "name": "c8", "required": true, "type": "double"},
                {"id": 7, "name": "c7", "required": true, "type": "double"}
              ]
            }
          },
          {"id": 2, "name": "c2", "required": false, "type": "string"}
        ]
      },
      "without-field-ids": [9, 10, 11],
      "rows": [
        {"1": "1", "2": "alpha", "3": {"7": 40.7128, "8": -74.006}, "4": ["east", "coastal"], "5": {"keys": ["pop"], "values": [8336817]}},
        {"1": "2", "2": null, "3": null, "4": null, "5": null},
        {"1": "3", "2": "charlie", "3": {"7": 51.5074, "8": -0.1278}, "4": ["west", null], "5": {"keys": ["pop", "area"], "values": [null, 1572]}}
      ]
    }
  ]
}
```
