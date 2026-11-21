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
name mapping that names the physical columns. The file is ID-bearing at several
depths, so the name mapping is not used at all and the ID-less nested fields are
absent. The table is unpartitioned and v2, so there is no identity-partition
fallback and no default values: the absent fields read as null. Partition
metadata, defaults, other file formats and reads that fail on absent fields are
left to other tables.

## Assertion

Under [Column Projection](https://iceberg.apache.org/spec/#column-projection),
readers check the full file schema before projection. Because this file carries
field IDs at any depth, readers must use stored IDs for the whole file;
`schema.name-mapping.default` applies only to a data file with no field IDs. A
list or map whose element, key or value has no ID is absent as a whole, and
absent fields resolve through the unchanged rules: partition metadata,
`initial-default`, then null. Here that means fields `4` (`tags`), `5` (`props`)
and `6` (`added`) are null in every row.

`expected/scan.json` records the full scan of the only snapshot, keyed by field
ID: every row has fields `1`, `2`, `3`, `7` and `8` read from columns `c1`, `c2`,
`c3`, `c3.c7` and `c3.c8` by ID, and `4`, `5` and `6` as explicit null. `long`
values are strings, following the repository convention. The physical column
order differs from the schema's, and the names of the ID-carrying columns are
absent from the name mapping, so neither name nor position can produce these
values.

Resolution is independent of projection: the file is ID-bearing whether or not
the projection includes the ID-less fields, so a scan of only the ID-carrying
fields returns the same values as the full scan. Reference tables have no format
for a partial projection, so this is stated here rather than asserted in a
separate file.

## Spec basis

The reading was settled on dev@iceberg.apache.org in
[Name mapping for data files that carry only some field IDs](https://lists.apache.org/thread/tct2yp45ytsb6dqw1tq7b8gnfnp7jndo)
and written into the spec in
[apache/iceberg#19037](https://github.com/apache/iceberg/pull/19037). The two
points pinned there are that a missing ID in an ID-bearing file makes the field
absent, resolved as null here rather than by the name mapping, and that whether
an ID'd field can be read does not depend on other fields lacking IDs.

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
