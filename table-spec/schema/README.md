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

# Schema decoding

Reading a schema JSON produces the same schema in every implementation. Field id
is column identity in Iceberg, so two implementations that disagree about which
id a nested field carries read each other's data files incorrectly and raise
nothing. That is the failure this surface exists to catch.

## Assertion

```
project(parse(input)) == decoded
```

Bytes are not compared. The spec fixes no key order in a schema JSON, and does
not say whether an absent optional field is written out, so two byte-different
schema JSONs can be the same schema. `decoded` is the comparable form.

## Scope

This surface reads the schema JSON object and nothing around it. Writing a schema
back out is a later phase, per `CONTRIBUTING.md`. `initial-default` and
`write-default` decode by the Appendix D single value rules and belong in their
own surface. Whether a type is legal at a given format version cannot be decided
here, because a schema JSON carries no version. And a decimal whose scale exceeds
its precision, such as `decimal(3, 6)`, is left out because the spec neither
permits nor forbids it.

## Inputs

`core/` holds the shape cases: nesting, every id space, column order, `doc`,
`schema-id`, `identifier-field-ids`, and every v1 and v2 primitive type. Each
other subdirectory covers one v3 type and is named for it.

Subscribe by subdirectory. An implementation with no geospatial support runs
everything except `geospatial/` and names that in its own configuration. A
subdirectory exists where an implementation can lack what it covers, which is why
nesting is not one: it carries the most risk here, and nobody opts out of lists
and maps.

## Case format

Each file holds a `cases` array. One case, complete:

```json
{
  "id": "map-of-primitive",
  "valid": true,
  "input": {
    "type": "struct", "schema-id": 0,
    "fields": [
      { "id": 1, "name": "id", "required": true, "type": "long" },
      { "id": 2, "name": "props", "required": true, "type": {
          "type": "map", "key-id": 3, "key": "string",
          "value-id": 4, "value-required": false, "value": "int" } }
    ]
  },
  "decoded": {
    "schema-id": 0,
    "identifier-field-ids": [],
    "fields": [
      { "id": 1, "name": "id",    "parent": null, "required": true,  "type": "long",   "doc": null },
      { "id": 2, "name": "props", "parent": null, "required": true,  "type": "map",    "doc": null },
      { "id": 3, "name": "key",   "parent": 2,    "required": true,  "type": "string", "doc": null },
      { "id": 4, "name": "value", "parent": 2,    "required": false, "type": "int",    "doc": null }
    ]
  }
}
```

- `id` is a stable name for the case, used to label failures.
- `valid` is whether parsing should succeed. A `valid: false` case has no
  `decoded`.
- `input` is a schema JSON.
- `decoded` is the expected result of parsing it: `schema-id`,
  `identifier-field-ids`, and `fields`, one row per field id in document order.

Each row of `decoded.fields` is:

- `id`, the field id. List elements, map keys and map values carry `element-id`,
  `key-id` and `value-id`.
- `name`, the field name. Elements, keys and values are named `element`, `key`
  and `value`.
- `parent`, the enclosing field's id, or `null` at the top level.
- `required`, the field's required flag. Map keys are always `true`.
- `type`, one of `struct`, `list` or `map` for a nested type, whose children are
  their own rows. Otherwise the canonical type string from the Appendix C types
  table.
- `doc`, the field's doc string, or `null`.

## Procedure

The cases in each subscribed subdirectory are worked through one at a time.

The schema in `input` is read through the same path the implementation uses to
read a schema out of table metadata.

A case marked `"valid": false` holds a malformed schema, and the read is expected
to fail. The case passes if it does. If the read succeeds instead, the case
fails, and the parser is more lenient than the spec.

Otherwise the read is expected to succeed. The resulting schema is turned into
the row list described above, with each value read through the implementation's
public accessors. Serializing the schema and reading values back out of the JSON
checks the writer rather than the reader, so it does not satisfy the case. The
row list is then compared against `decoded`.

The comparison is exact. A value differing only in whitespace or letter case is
still a difference, and the case fails. Row order is preserved, because that
order is the column order the schema asserts, and sorting rows by id discards the
assertion. `identifier-field-ids` is the one exception, and is compared as a set,
which is what the spec calls it.

Every difference in a case is reported, not only the first, so that one run shows
everything that diverged. A report names the subdirectory, the case and the
value, reading like `core/list-of-struct fields[2].parent`.

## Notes

Expected values are JSON. Field ids, `schema-id` and `parent` are written as
numbers, because the spec types field ids as `int` and caps them below
`Integer.MAX_VALUE - 200`. `parent` and `doc` are written as `null` where they
have no value, and `null` there is a decoded value, not a missing assertion.

A decimal projects as `decimal(9, 2)`, with the space. The Appendix C row is
inconsistent, carrying the template `"decimal(<P>,<S>)"` alongside both
`"decimal(9,2)"` and `"decimal(9, 2)"`.
[apache/iceberg#16798](https://github.com/apache/iceberg/pull/16798) declared the
table's type strings canonical and its title states the intended form is
`decimal(P, S)`, but it did not update the row. Every implementation emits the
spaced form. A one line fix to the row closes this.
