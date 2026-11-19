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

# v3/row-lineage-parquet-data

Input to `reference-tables/GenerateTables.java`. The schema and partition spec are
the spec's JSON forms. Rows are written in the spec's single-value JSON keyed by
field ID, with `long` values as strings. The first commit appends one data file.
The second appends two data files in one snapshot. The third is an overwrite that
replaces data file `a` with `a2`, whose rows also carry the row lineage columns
`_row_id` (field ID 2147483540) and `_last_updated_sequence_number` (field ID
2147483539).

`a2` copies the three rows of `a` with their row IDs, updates the `data` of id 2,
and adds id 7. Following
[Row lineage assignment](https://iceberg.apache.org/spec/#row-lineage-assignment),
the copied rows keep their `_last_updated_sequence_number`, while the updated row
and the added row write it as null, and the added row writes a null `_row_id`.

## Scope

Row lineage: two appends that assign row IDs by inheritance, then an overwrite
that writes some row IDs and sequence numbers explicitly and leaves others null.

The two appends write six rows across three data files, so IDs must be assigned
in commit order and across files. The overwrite copies the first file's three
rows with their IDs and original last-updated sequence number, updates one of
them and adds a new one, so the reader must preserve the copied values and write
null for the changed and added rows. The metadata columns are keyed by their
reserved field IDs, 2147483540 and 2147483539, so a reader that treats them as
ordinary columns or resolves them by name returns wrong values. Other v3
features are left to other tables.

## Input

```json
{
  "format-version": 3,
  "description": "Row lineage. Two appends assign row IDs by inheritance; an overwrite then writes some row IDs and sequence numbers explicitly and leaves others null.",
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
        {"1": "2", "2": "bravo"},
        {"1": "3", "2": "charlie"}
      ]
    },
    {
      "data-files": [
        {
          "data-file": "b",
          "rows": [
            {"1": "4", "2": "delta"},
            {"1": "5", "2": "echo"}
          ]
        },
        {
          "data-file": "c",
          "rows": [
            {"1": "6", "2": "foxtrot"}
          ]
        }
      ]
    },
    {
      "overwrite": {
        "replaces": "a",
        "data-file": "a2",
        "rows": [
          {"1": "1", "2": "alpha", "2147483540": "0", "2147483539": "1"},
          {"1": "2", "2": "bravo-updated", "2147483540": "1", "2147483539": null},
          {"1": "3", "2": "charlie", "2147483540": "2", "2147483539": "1"},
          {"1": "7", "2": "golf", "2147483540": null, "2147483539": null}
        ]
      }
    }
  ]
}
```
