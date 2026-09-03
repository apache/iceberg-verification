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

# Contributing

First, thank you for contributing to iceberg-verification! The goal of this
document is to provide the guidelines you need to maintain high quality
conformance fixtures for all Iceberg implementations.

[Iceberg](https://iceberg.apache.org/) is first and foremost a Spec, an
agreement between different implementations on the structure and meaning of the
artifacts that make up a table. And as such, it is crucial that the way each
implementation interprets the spec, and the values and artifacts it stores and
transfers as a result, stay consistent across implementations. Each
implementation's own test suite cannot catch a divergence here, because it
checks that implementation against its own reading of the spec.

This framework aims to become a central place where the materialized value
expectations of the spec are discussed, so that their representations and
expectations can be used to verify implementations across languages and
platforms.

One thing to be clear about up front: we surface ambiguity here, we do not
settle it here. When a case has no clear answer in the spec, raise it on
[dev@iceberg.apache.org](https://lists.apache.org/list.html?dev@iceberg.apache.org),
and commit the case in the meantime as an open case with no expected value. See
[Cases the spec has not settled](#cases-the-spec-has-not-settled). Committing it
is often what gives the discussion something specific to point at.

## Does your change belong here?

This repository holds artifacts and the expected values the spec fixes for
them.

If you have found a bug in one implementation, that belongs in that
implementation's repository. Open a fixture pull request here when the spec
fixes an expected value and an implementation disagrees with it.

Engine query results, live catalog protocol behavior, and physical encoding
choices such as compression codec and file format writer version are out of
scope. Any spec-valid encoding is valid.

## Choosing a test surface

Given the above goal, it is important that contributors think deeply about what
we are trying to verify when we introduce a new test surface.

A test surface is one spec behavior verified by one assertion. Each surface
directory holds its input artifacts, the expected values co-located with them,
and a `README.md` stating that assertion. Surfaces are grouped by the spec they
cover, and test surfaces often map to specs. Co-located can mean an
expected-value file beside each input, or one record carrying both. Where the
input is small enough to write inline, one record is usually the clearer form.

A surface may be divided into subdirectories along an internal dimension such
as type or format version. The subdirectories share the surface's assertion and
its `README.md`. A dimension that changes the assertion is not an internal
dimension, it is a second surface.

A subdirectory is the unit of subscription. It exists so that a consumer can run
one directory to answer one conformance question, for example whether its
implementation supports `variant` columns, and name that directory in its
own configuration rather than filtering cases at runtime. Taking some
subdirectories of a surface and not others is normal. Organize them so that the
choice is expressible by path.

Specs can have dependencies on other Iceberg specs. For example, Table Spec has
a dependency on Iceberg Type Spec. In such cases, we recommend creating test
surfaces for both layers at the root level, and focusing on the coverage that
pertains to each level.

If there are edge case value concerns with the type spec, or we want to verify
the field structure of a type spec (e.g. Geography Type), we want to include
that coverage in the surface for the Iceberg Type Spec. Geography Type support
in a Table V3 spec can be added as a separate test at the Table Spec test
surface, but the scope of testing will be different. In the Type Spec surface,
we test edge cases in the type representation. In the Table Spec test, we check
that V3 supports a representation of Geography Type.

Notice that this is not all that different from how we reason through regular
test suites. Keep this model in mind when you work on introducing a new test
surface.

## What should my test fixtures and expectations look like?

There is no single answer here, and that is deliberate. Each combination of
input fixture and expectation differs by the surface under test, because what
the spec fixes differs by surface. The spec fixes the canonical form of a type
string. It pins no such form for `metadata.json`, so two byte-different files
can represent the same table metadata, and only the decoded values are
comparable.

It is the contributor's responsibility to understand the spec, work out the
specification that needs to hold true, and design the input and the assertions
that define correctness of the materialized values for that spec. Before you
write a fixture, you should be able to answer:

1. Which clause of which spec fixes the correct answer here? Link it.
2. What is the input, and what is the smallest complete statement of the
   expected result?
3. What must an implementation do to check it, in one sentence? That sentence
   belongs in the surface `README.md`, and it is the contract consumers
   implement.
4. Which boundary cases does the spec decide, and which does it leave open?

That said, a few things hold no matter which surface you are working on.

### Bytes or decoded values?

Where the spec fixes a canonical form, as it does for type strings, we can
assert that re-serializing the parsed input reproduces that form exactly.
Everywhere else, we compare the decoded logical values.

It is worth thinking carefully about this, because the stricter option may not
be the most suitable assertion. If we compare a `metadata.json` or an Avro
manifest against an expected form byte for byte, we end up freezing one
implementation's key ordering, and its choices about optional fields and
codecs, as if the spec had required them. Other implementations would then fail
our fixture for reasons the spec does not care about, and that is not what we
want to achieve with the verification framework.

When we compare decoded values, we still need to be careful about what we
normalize away. Where the spec fixes a logical value but allows more than one
encoding, we normalize to the value. For example, a `day` transform result is a
`date`, and the spec requires readers to accept a plain `int` as the same date,
so the two should compare equal. Where the spec pins a physical type, we keep
it. `equality_ids` elements are `int`, not `long`, so a decode that reads both
as `1` would miss the difference.

So the only things we assert are the ones the spec pins. Where the spec allows
more than one encoding of the same logical value, we normalize and there is no
difference left to fail on. Where the spec has not decided the answer at all,
see the next section.

### Cases the spec has not settled

Often the most interesting case is one where the spec does not yet give a clear
answer, and implementations have landed in different places. For example, the
spec caps decimal precision at 38, but whether a reader must reject
`decimal(40, 2)` is not settled.

We do not want to leave these out, and we do not want to invent an answer for
them either. Instead we commit the case as an open case. The input is pinned,
the expected values the spec does not fix are not committed, and the case
carries a reference to the question. Capturing it this way is what makes the
boundary concrete, so that implementations can show where they currently land
and the discussion has something specific to point at.

Open is per assertion, not per case. A surface can assert several things about
one input, and the spec can fix some and not others. Leave open only what it
does not fix, so an unsettled serialized form does not also cost you a settled
parse.

Where no discussion has been opened yet, reference the change or the clause the
ambiguity originates in, and say so in the pull request.

How the open state is written is up to the surface, but every surface must make
it possible for a consumer to tell an open case from an assertable one without
reading the fixture by hand.

An open case never fails a consumer. Running one and recording what your
implementation does is optional, and it is the most useful thing you can bring
back to the discussion.

Once the community settles the question, the case gains its expected value and
stops being open. Call that out in the pull request, because a consumer bumping
its pin may start failing a case that used to pass.

### Keep expected values unambiguous across implementations

Your fixture will be read by Go, Python, Rust, Java, C++ consumers and other
Iceberg implementations that may not be managed by the Apache Iceberg
community, so the expected value itself must not be ambiguous to read. Say in
your surface `README.md` how your expected files are written.

Before inventing a convention, look at how existing surfaces handle the same
problem and follow them where it fits. Once a convention has proven consistent
across surfaces, we can promote it into this document as a repository-wide
rule.

#### If your expected values are JSON

- **Record the type wherever the spec pins one.** JSON has a single number
  type, so `[1]` reads the same whether an implementation decoded an `int` or
  a `long`. `equality_ids` elements are `int`, and an expected value that
  recorded only the number would not catch an implementation that decoded them
  as `long`.
- **Write 64-bit integers as strings.** Most JSON parsers read numbers as
  doubles, which are exact only up to 2^53. A `long` value of
  `3055729675574597004` comes back as `3055729675574597120` after such a parse,
  with no error raised. Do this for every field the spec types as 64-bit,
  whatever the value happens to be, so that a field is not a number in one case
  and a string in another.
- **Write binary as uppercase hex.** JSON has no binary type. An array of
  integers diverges immediately, because languages disagree on whether a byte
  is signed. A Java `byte` is signed and writes `-1` where Go and Python write
  `255`, and base64 has more than one valid encoding of the same bytes.
- **Write `null` out rather than leaving the field absent**, so that a reader
  can tell a value that decoded to null from a field the assertion does not
  cover.

Override any of these where they do not fit your surface, and say why in its
`README.md`.

Some values need more than these conventions. For example, the unscaled value
of a `decimal(38, 10)` does not fit in 64 bits at all, so a surface that covers
one will need to say how it writes it.

## What we expect of a fixture

- **Derive it from the spec, do not copy it from an implementation.** The
  implementation you copy from may already share the bug you are trying to
  catch. Where the spec ships reference vectors, reproduce those first.
- **Cross-check before you open the pull request.** Confirm the expected value
  against at least one implementation other than the one that prompted the
  fixture.
- **Pin boundary cases, not just the spec's examples.** Drift lives at the
  edges. Include the cases that must be rejected, not only the ones that must
  be accepted. A corpus of valid inputs alone cannot catch a parser that is too
  lenient.
- **Keep committed files implementation-neutral.** Name the diverging
  implementation in the pull request, not in the fixture.
- **Say whether you are adding or correcting.** A consumer bumping its pin
  needs to know whether a newly failing case is expected.

## Opening a pull request

1. Identify the surface. Reuse an existing one where the assertion already
   fits.
2. Add the input and its expected value to that surface.
3. Extend the surface `README.md` if the addition changes what the assertion
   covers.
4. In the pull request, state the spec clause the expected value derives from,
   which implementations you checked it against, and whether the case is an
   addition or a correction.

A new surface additionally needs a `README.md`. Section names and order are up
to you, but it has to cover:

- The assertion, stated as an expression and not only as prose.
- What the surface covers, and what was deliberately left out and why.
- Each subdirectory, what it covers, and whether a consumer can subscribe to it
  on its own.
- The shape of a case, and how to read its expected values.
- The consumer loop, written so that two implementers cannot read it two ways.
  It has to settle the comparison rule, how a failure is labeled, and the
  dispatch on an open case where the surface has any. Pseudocode is one way to do
  that and prose is another.

## License headers

Please do not add license headers to fixture files. They are test data, and a
header would alter the artifact under test. The
[ASF Source Header and Copyright Notice Policy](https://www.apache.org/legal/src-headers.html)
exempts "test data for which the addition of a source header would cause the
tests to fail". Every other file in this repository carries the standard ASF
source header.
