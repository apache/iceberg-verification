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

# v2/position-deletes-parquet-data

Input to `reference-tables/GenerateTables.java`. The schema and partition spec are
the spec's JSON forms. The first commit appends three data files in one snapshot,
each holding its rows, written in the spec's single-value JSON keyed by field ID,
with `long` values as strings. Each later commit adds one Parquet position delete
file, named by `file`, holding the listed row positions of each named data file,
also written as strings, in the order given. The last commit also adds a data file
in the same row delta.

## Scope

Position delete files: their `file_path` and `pos` columns, with the reserved
field IDs 2147483546 and 2147483545, and the spec's scope rule for applying them:

> A _position_ delete file must be applied to a data file when all of the
> following are true:
>
> - The data file's `file_path` is equal to the delete file's
>   `referenced_data_file` if it is non-null
> - The data file's data sequence number is _less than or equal to_ the delete
>   file's data sequence number

`pd1` deletes positions 0 and 3 of `a`, and `pd3` position 0 of `d`, so each
deletes from one data file only and sets `referenced_data_file`. `pd2` deletes
position 4 of `a` and position 1 of `b`, so it covers two data files and leaves
`referenced_data_file` null. A reader must then match each entry's `file_path`
against the data file: one that applies every position of `pd2` to every file
also deletes `a`'s position 1 and `c`'s position 1. `c` is never deleted from,
so a reader that applies deletes by position alone drops rows of `c`.

`d` and `pd3` are added in the same commit and share a data sequence number. The
spec says "Position deletes (vectors and files) must be applied to data files
from the same commit, when the data and delete file data sequence numbers are
equal", so a reader that compares sequence numbers with strictly-less keeps
`d`'s position 0.

The delete files carry no `row` column, which is optional and which Java 1.11.0
deprecates writing. Equality delete files are left out, and so are position
deletes in a table upgraded to v3; each needs its own table.

## Input

```json
{
  "format-version": 2,
  "description": "Flat schema of long, string and int. One append of three data files, then three Parquet position delete files: one for a single file, one covering two files with no referenced data file, and one committed with the data file it deletes from.",
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
            {"1": "7", "2": "golf", "3": 70},
            {"1": "8", "2": "hotel", "3": 80}
          ]
        },
        {
          "data-file": "c",
          "rows": [
            {"1": "9", "2": "india", "3": 90},
            {"1": "10", "2": "juliet", "3": 100}
          ]
        }
      ]
    },
    {
      "position-deletes": {
        "file": "pd1",
        "deletes": [
          {"data-file": "a", "positions": ["0", "3"]}
        ]
      }
    },
    {
      "position-deletes": {
        "file": "pd2",
        "deletes": [
          {"data-file": "a", "positions": ["4"]},
          {"data-file": "b", "positions": ["1"]}
        ]
      }
    },
    {
      "position-deletes": {
        "file": "pd3",
        "data-files": [
          {
            "data-file": "d",
            "rows": [
              {"1": "11", "2": "kilo", "3": 110},
              {"1": "12", "2": "lima", "3": 120}
            ]
          }
        ],
        "deletes": [
          {"data-file": "d", "positions": ["0"]}
        ]
      }
    }
  ]
}
```
