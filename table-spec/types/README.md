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

# Type decoding

Parsing a type string produces the same type in every implementation. This
surface pins each type the spec defines and the parse rules that attach to it.

## Assertion

```
parse(input) == decoded
```

`input` is a type string, or a JSON object for a nested type; `decoded` is the
language-neutral shape below. Bytes are not compared - each implementation maps
its own type object to `decoded`, so the comparison does not depend on one
language's representation.

- `valid: true` - the parser succeeds and the decoded type equals `decoded`. A
  type an implementation does not model is UNSUPPORTED, not a failure. If the case
  also carries `canonical`, re-serializing the parsed type must equal it byte for
  byte (the write direction).
- `valid: false` - the parser must reject `input`. A rejection passes; a
  successful parse fails.

`canonical` is present only where the spec pins one spelling. `decimal` has two
blessed forms (`decimal(9,2)` and `decimal(9, 2)`), so its cases have no
`canonical` and are compared by `decoded` alone.

## Scope

Each type in isolation, per the Primitive Types table and Appendix C. The full
schema document (schema-id, identifier-field-ids, field ordering) is the `schema`
surface. Whether a type is legal at a given format version is not decided here,
because a type in isolation carries no version.

## Inputs

- `primitive/` - every v1/v2 primitive (`boolean`, `int`, `long`, `float`,
  `double`, `date`, `time`, `timestamp`, `timestamptz`, `string`, `uuid`,
  `binary`, `decimal`, `fixed`) plus the v3 additions `timestamp_ns`,
  `timestamptz_ns`, `unknown`.
- `variant/` - `variant` (v3).
- `nested/` - `struct`, `list`, `map`, including nesting (a `struct` field whose
  type is a `list`).
- `geospatial/` - `geometry` and `geography` (v3), with explicit and default CRS.

## Case format

One `cases.json` per directory, a JSON object with a `cases` array:

| field | meaning |
| --- | --- |
| `id` | unique case id |
| `valid` | `true` if the parser must accept `input`, `false` if it must reject it |
| `input` | the type string (`"decimal(9,2)"`), or a JSON object for a nested type |
| `decoded` | the decoded shape; present only when `valid` is `true` |
| `canonical` | the exact re-serialized string; present only where the spec pins one spelling |
| `clause` | the spec rule this case pins |
| `spec_ref` | anchor into `format/spec.md` |

`decoded` is language-neutral:

- primitive: `{"type": "<name>"}`, e.g. `{"type": "int"}`
- `decimal`: `{"type": "decimal", "precision": P, "scale": S}`
- `fixed`: `{"type": "fixed", "length": L}`
- `geometry`: `{"type": "geometry", "crs": C}`; `geography` adds `"algorithm": A`
- `struct`: `{"type": "struct", "fields": [{"id", "name", "required", "type"}, ...]}`
- `list`: `{"type": "list", "element-id", "element-required", "element"}`
- `map`: `{"type": "map", "key-id", "key", "value-id", "value-required", "value"}`

A nested type's child `type` values are the same shape, recursively.

## Provenance

`input` and `decoded` are derived from `format/spec.md` (the Primitive Types
table and Appendix C), cross-checked against Apache Iceberg Java. There is no
binary artifact; Java is a cross-check, not the source of the inputs.

## Left out on purpose

Inputs the spec neither permits nor forbids, so no answer can be spec-derived:

- `scale > precision`, e.g. `decimal(5, 10)`.
- lower bounds, e.g. `decimal(0, 0)` / `fixed[0]`.
- internal whitespace around every parameter, e.g. `decimal( 9 , 2 )`. The one
  spaced case we do ship, `decimal(9, 2)`, is a *recommended* (SHOULD) accept,
  not a hard requirement: the spec says readers *should*, not *must*, accept
  optional whitespace, so an implementation that rejects it is still conformant.
- keyword case, e.g. `DECIMAL(9,2)`.
- geospatial CRS *quoting*, e.g. `geometry('OGC:CRS84')`. The spec's canonical
  form is unquoted (`geometry(OGC:CRS84)`), which the shipped cases use; whether
  the quoted form is also accepted is unpinned.
