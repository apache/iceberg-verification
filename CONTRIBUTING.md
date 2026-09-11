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

## Repository layout

```
table-spec/<surface>/<subdir>/cases.json   spec-derived fixtures, one answer key
table-spec/manifest.json                    the surfaces an implementation can subscribe to
runners/<lang>/                             one runner per language, reads fixtures, reports PASS/FAIL/UNSUPPORTED
dev/                                        validation, reporting, and check scripts
dev/schema/                                 JSON Schemas the fixtures are validated against
```

A surface (for example `types`) is a group of cases with one assertion contract,
documented in its own `README.md` (see `table-spec/types/README.md`). A subdirectory
under a surface is a unit an implementation can opt into or skip.

## Two tiers

Conformance runs at two altitudes, and a surface belongs to whichever fits.

- **Central runner (this repo).** Surfaces whose expected answer is derivable from
  the spec with no implementation-specific knowledge (the type surface is the clear
  case) are exercised by the runners here and reported by the nightly matrix and the
  README badges. This tier needs no buy-in from an implementation to produce a signal.
- **Submodule subscription (the implementation).** Surfaces whose assertion needs an
  implementation's own internals are better owned by that implementation: it pins this
  repository, selects the surfaces it wants, and runs them in its own CI, pre-merge.

Both tiers read the same fixtures. The answer key is single-sourced here regardless of
which tier runs it.

## Fixture format

Each `cases.json` is a JSON object with a `cases` array. Every case has a unique `id`,
a boolean `valid`, and an `input`; a valid case also has `decoded`. The exact per-case
shape is defined by the JSON Schemas in `dev/schema/` and described in each surface's
`README.md`. Validate locally with:

```
python3 dev/validate-fixtures.py
```

## Adding or correcting a fixture

1. Add or edit the case in the surface's `cases.json`. Derive `input`/`decoded` from
   `format/spec.md`, not from any single implementation.
2. If you add a new subdirectory or surface, list it in `table-spec/manifest.json`.
3. Run `python3 dev/validate-fixtures.py` (schema, unique ids, manifest consistency).

## Subscribing from an implementation

1. Pin this repository at a commit (a git submodule, or a sparse checkout of the
   `table-spec/` paths you care about). Bump the pin deliberately.
2. Read `table-spec/manifest.json` to see the available surfaces and subdirectories.
3. Run the surfaces you opted into. Either run this repo's runner scoped to them:

   ```
   conformance-<lang> --surface types            # a whole surface
   conformance-<lang> --surface types/primitive  # one subdirectory
   ```

   (or set `CONFORMANCE_SURFACES="types types/geospatial"`), or read the fixtures and
   assert against them with your own glue. With no `--surface`, a runner reads every
   subscribed surface.
