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

# v2/primitive-types-parquet-data

Input to `reference-tables/GenerateTables.java`. The schema and partition spec are
the spec's JSON forms. The one commit appends one data file holding its rows,
written in the spec's single-value JSON keyed by field ID, with `long` values as
strings.

The table holds one column of every v1 and v2 primitive type. The three decimal
columns use each of the Parquet physical types the spec maps a decimal to:
`int32` for precision up to 9, `int64` up to 18, and `fixed_len_byte_array`
otherwise. A reader that decodes only one of them fails on the others. The
`timestamp` and `timestamptz` columns differ only in the `TIMESTAMP_MICROS`
annotation's `isAdjustedToUTC` flag, so a reader that ignores the flag returns
one of them with the wrong type, or shifted by its local zone.

Row 1 holds typical values, except that `int_col` and `long_col` hold their
maxima and `time_col` holds `00:00:00`. Row 2 holds boundary values: the int and
long minima, a negative decimal with every digit of its precision used, a date
and timestamps one microsecond before the epoch, `23:59:59.999999`, an empty
string and an empty binary, and an all-ones uuid and fixed. Row 1's string is
non-ASCII and includes a character outside the Basic Multilingual Plane. Row 3
is null in every optional column.

Values are written as follows:

- A decimal is the spec's decimal string, with exactly `scale` digits after the
  point. The unscaled value of the `decimal(38, 10)` column overflows 64 bits,
  and a string carries it without loss.
- `fixed` and `binary` values are uppercase hex, following `CONTRIBUTING.md`.
  The spec's example is lowercase. The generator rejects lowercase hex.
- A `uuid` is the lowercase uuid string, as the spec says.
- Every `float` and `double` value is exactly representable in binary32 or
  binary64, so the decimal text decodes to one value in every language.

NaN, positive and negative infinity, and -0.0 are left out. JSON cannot carry
NaN or the infinities, and the single-value serialization does not define a form
for them. A -0.0 compares equal to 0.0 under most decoded-double equality, so a
multiset comparison would not tell them apart. These are open questions for the
dev list.

## Input

```json
{
  "format-version": 2,
  "description": "Every v1 and v2 primitive type, read back from Parquet. One append of one data file, three rows: typical values with int and long maxima, boundary values, and every optional column null.",
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
        "name": "bool_col",
        "required": false,
        "type": "boolean"
      },
      {
        "id": 3,
        "name": "int_col",
        "required": false,
        "type": "int"
      },
      {
        "id": 4,
        "name": "long_col",
        "required": false,
        "type": "long"
      },
      {
        "id": 5,
        "name": "float_col",
        "required": false,
        "type": "float"
      },
      {
        "id": 6,
        "name": "double_col",
        "required": false,
        "type": "double"
      },
      {
        "id": 7,
        "name": "decimal_9_2",
        "required": false,
        "type": "decimal(9, 2)"
      },
      {
        "id": 8,
        "name": "decimal_18_4",
        "required": false,
        "type": "decimal(18, 4)"
      },
      {
        "id": 9,
        "name": "decimal_38_10",
        "required": false,
        "type": "decimal(38, 10)"
      },
      {
        "id": 10,
        "name": "date_col",
        "required": false,
        "type": "date"
      },
      {
        "id": 11,
        "name": "time_col",
        "required": false,
        "type": "time"
      },
      {
        "id": 12,
        "name": "timestamp_col",
        "required": false,
        "type": "timestamp"
      },
      {
        "id": 13,
        "name": "timestamptz_col",
        "required": false,
        "type": "timestamptz"
      },
      {
        "id": 14,
        "name": "string_col",
        "required": false,
        "type": "string"
      },
      {
        "id": 15,
        "name": "uuid_col",
        "required": false,
        "type": "uuid"
      },
      {
        "id": 16,
        "name": "fixed_col",
        "required": false,
        "type": "fixed[4]"
      },
      {
        "id": 17,
        "name": "binary_col",
        "required": false,
        "type": "binary"
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
        {"1": "1", "2": true, "3": 2147483647, "4": "9223372036854775807", "5": 1.5, "6": -0.25, "7": "1234.56", "8": "12345678.9012", "9": "1234567890.0123456789", "10": "2026-01-15", "11": "00:00:00.000000", "12": "2026-01-15T10:30:00.123456", "13": "2026-01-15T10:30:00.123456+00:00", "14": "Grüße, 世界 🧊", "15": "f79c3e09-677c-4bbd-a479-3f349cb785e7", "16": "000102FF", "17": "DEADBEEF"},
        {"1": "2", "2": false, "3": -2147483648, "4": "-9223372036854775808", "5": -0.375, "6": 1048576.125, "7": "-9999999.99", "8": "-99999999999999.9999", "9": "-9999999999999999999999999999.9999999999", "10": "1969-12-31", "11": "23:59:59.999999", "12": "1969-12-31T23:59:59.999999", "13": "1969-12-31T23:59:59.999999+00:00", "14": "", "15": "ffffffff-ffff-ffff-ffff-ffffffffffff", "16": "FFFFFFFF", "17": ""},
        {"1": "3", "2": null, "3": null, "4": null, "5": null, "6": null, "7": null, "8": null, "9": null, "10": null, "11": null, "12": null, "13": null, "14": null, "15": null, "16": null, "17": null}
      ]
    }
  ]
}
```
