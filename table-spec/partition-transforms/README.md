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

# Partition transforms

Binding a partition transform to a source type and applying it to values
produces the same logical values and result type in every implementation. The
Iceberg specification is the source of truth for this surface.

## Assertion

```
apply(bind(transform, source_type), ordered_inputs) == ordered_expected_values
result_type(bind(transform, source_type)) is one of allowed_result_types
```

Bytes are not compared. Each implementation decodes the language-neutral input
values, binds the transform, applies it in order, and maps its results back to
the language-neutral representation below.

## Scope

This surface covers the value transform and its result type. It binds every
standard transform to each source type permitted by the Partition Transforms
table, subject to the deliberate omissions below. The cases include the spec's
examples, boundary values, and an explicit `null` input for every
transform/source-type path. As required by the spec, "All transforms must return
`null` for a `null` input value."

Consumers subscribe by subdirectory:

- `identity/` - "Source value, unmodified."
- `bucket/` - Murmur3 x86 32-bit, seed 0, with the sign bit discarded before
  taking modulo `N`.
- `truncate/` - integer, long, decimal, string, and binary truncation.
- `year/`, `month/`, `day/`, and `hour/` - temporal ordinals from the Unix
  epoch. `day` expected values are logical dates.
- `void/` - "Always produces `null`."

The subdirectories are independent subscription boundaries. For example, an
implementation without nanosecond timestamp types may skip cases for those
source types while running the other cases in the same directory.

## Case format

Each `cases.json` holds a `cases` array. A case has this shape:

```json
{
  "id": "bucket-int-100",
  "valid": true,
  "input": {
    "transform": "bucket[100]",
    "source_type": "int",
    "values": [34, null]
  },
  "decoded": {
    "result_types": ["int"],
    "values": [79, null]
  },
  "clause": "Bucket Transform Details and Appendix B: int hash reference vector",
  "spec_ref": "format/spec.md#appendix-b-32-bit-hash-requirements"
}
```

| field | meaning |
| --- | --- |
| `id` | case id, unique within this surface |
| `valid` | always `true`; invalid binding and transform-string parsing are outside this surface |
| `input.transform` | the transform to construct and bind |
| `input.source_type` | the source type to bind |
| `input.values` | ordered logical source values |
| `decoded.result_types` | all result types the specification permits |
| `decoded.values` | expected logical results corresponding by index to `input.values` |
| `clause` | the specification rule or example the case pins |
| `spec_ref` | anchor into `format/spec.md` |

The result type list normally has one item. The `void` transform permits either
the source type or `int`, so those cases list both choices (deduplicated when
the source type is already `int`).

## Language-neutral values

- `boolean`, `int`, and temporal ordinal results are JSON primitives.
- `long` values are decimal strings so JSON number handling cannot lose 64-bit
  precision.
- Decimal values are scale-preserving strings. For example, `"14.20"` is not
  rewritten as `"14.2"`.
- `fixed` and `binary` values are uppercase hexadecimal strings with two digits
  per byte and no separators.
- Dates use ISO `YYYY-MM-DD`. Times and timestamps without a zone use ISO local
  forms and retain the precision shown by the string.
- Timestamps with a zone use ISO forms ending in `Z` or a numeric offset. They
  are decoded as instants; offset-equivalent strings compare as the same instant.
- Nanosecond timestamp values use nine fractional digits where the additional
  precision is material.
- UUID values use canonical lowercase hyphenated text.
- Strings are Unicode scalar values. String truncation counts code points, not
  UTF-8 bytes. Binary truncation counts bytes.
- Every asserted null is written explicitly as JSON `null`.

`day` results are normalized to the logical date strings used here before
comparison. A reader that exposes the spec-equivalent integer day ordinal must
convert that ordinal to a date relative to `1970-01-01`.

## Procedure and comparison

For each case in a subscribed directory:

1. Construct `input.source_type` and `input.transform`, then bind the transform
   to the source type.
2. Assert that the bound transform's result type is a member of
   `decoded.result_types`.
3. Decode each member of `input.values`, apply the bound transform in order,
   and normalize only the transport encodings documented above.
4. Compare each result exactly with the value at the same index in
   `decoded.values`.

Report every difference. A result-type failure is labeled
`<subdirectory>/<case-id> result_type`; a value failure is labeled
`<subdirectory>/<case-id> values[<index>]`. A runner reports `pass`, `fail`, or
`skip`; `skip` means the subdirectory or source type is unsupported and was not
run.

## Provenance

The Partition Transforms table, Bucket Transform Details, Truncate Transform
Details, and Appendix B of `format/spec.md` define the expected values. The
bucket vectors are copied from Appendix B and converted to `bucket[100]` using
the specified sign-bit rule. The values were independently cross-checked
against Apache Iceberg Java transform tests and PyIceberg transform tests. Those
implementations are cross-checks, not the source of truth.

## Left out on purpose

- Partition-spec JSON, parsing invalid transform strings, and invalid
  transform/source bindings.
- Partition evolution, predicate projection and residuals, sorting,
  human-readable paths, table metadata, manifests, and physical encodings.
- Minimum or maximum values for `N` and `W`, and integer-overflow behavior. The
  specification does not settle those choices for this surface.
- `identity` for `geometry` and `geography`, which the Partition Transforms
  table explicitly excludes.
- `void` for nested values and `variant`. The cases cover the primitive types
  shared with the type surface. `geometry` and `geography` are null-only because
  this surface does not choose a language-neutral geospatial value encoding.
- Exact bytes except where a transform definition operates on bytes. Even for
  `fixed` and `binary`, the assertion is the decoded logical byte sequence, not
  a file or metadata encoding.
