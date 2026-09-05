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

# Reference tables

This directory hosts whole Iceberg tables reference fixtures.
Surfaces reference a table by path and hold their own expected
values, so one table can support several surfaces without being copied.

Tables are grouped by format version.

## Size

A table should stay under 100 KB. The three here are 16 KB to 28 KB.

Size tracks commit count, and almost nothing else. A commit costs roughly 12 to
14 KB, over half of it the manifest and manifest list it produces. Rows are close
to free: a Parquet file holding five rows is under 1 KB and is nearly all footer.
A deletion vector is 385 bytes. A v3 table runs slightly heavier per commit than
v1 or v2, because a v3 manifest entry carries the deletion vector fields whether
or not a vector exists.

So the limit is about seven commits. A table that needs more should say why in
its pull request.

## Paths

Prior to v4 the spec requires every path field to be fully qualified, so each
table carries an absolute prefix in `location`, in every `manifest-list`, and in
every `file_path`. That prefix is the sentinel root:

```
file:///tmp/iceberg-verification/reference-tables/<format-version>/<table-name>
```

A consumer resolves it one of two ways. Symlinking `/tmp/iceberg-verification` at
the checkout leaves every committed byte untouched. Substituting the prefix while
loading works where symlinks do not, notably on Windows. Both are valid, because
the assertion in every surface is on decoded values rather than bytes.

Substitution has to match on the path,
`/tmp/iceberg-verification/reference-tables`, and not on a scheme prefix. The two
spellings are not consistent across fields: `location` carries `file:///tmp/...`
with an empty authority, while the paths Hadoop wrote
into the manifest lists and manifests carry `file:/tmp/...`. Both are valid URIs
and both name the same file. Symlinking avoids the question.

Starting with v4 the spec permits relative paths and makes `location` optional,
so a v4 table needs neither.

Data files are real Parquet and the tables are scannable, so surfaces over this
corpus are not limited to metadata decoding.

## What is here

`index.json` lists every table: its name, format version, the implementation and
release that wrote it, the path to its metadata file, and a one-line description.
Consumers read it; the `description` field is documentation and is ignored.

```json
{
  "tables": [
    {
      "name": "v2/simple-append-parquet-data",
      "format-version": 2,
      "written-by": "Iceberg Java 1.11.0",
      "data-file-format": "parquet",
      "metadata": "v2/simple-append-parquet-data/metadata/v3.metadata.json",
      "description": "Flat schema of long, string and timestamptz. Two appends, four rows, one null."
    }
  ]
}
```

A table is written once and not regenerated, so `written-by` is per table: the
corpus holds whatever version was current when each table was added.

## Naming

A table directory names every choice an implementation has to support in order
to run it. Today that is the format version, which is the parent directory, and
the data file format, which is the suffix:

```
reference-tables/<format-version>/<what-it-covers>-<data-file-format>-data
```

So `v2/simple-append-parquet-data` is readable without opening anything: a v2
table whose data files are Parquet. The suffix names the data files specifically,
because the manifests in the same directory are Avro and the metadata is JSON. An
implementation that reads no Parquet skips it by path, without parsing the index.
The same choices appear as fields in `index.json` for consumers that would rather
filter there.

A later fixture that varies something else, delete file format for instance,
carries that in the name too.

## Entry point

Resolve a table's `metadata` path from `index.json` and open it as a static
table. Every implementation takes an explicit metadata file path:

| | |
| -- | -- |
| Iceberg Java | `new BaseTable(new StaticTableOperations(path, io), name)` |
| PyIceberg | `StaticTable.from_metadata(path)` |
| iceberg-rust | `StaticTable::from_metadata_file(path, ident, io)` |
| iceberg-go | `table.NewFromLocation(..., path, ...)` |
| iceberg-cpp | `TableMetadataUtil::Read(io, path)`, then `StaticTable::Make(ident, metadata, path, io)` |

No catalog is involved and no directory listing is needed.

The corpus carries no `metadata/version-hint.text`. That is a Hadoop convention
rather than a spec artifact, and only Iceberg Java and PyIceberg read it. An
explicit metadata path is the one mechanism all five share.

Only the current `metadata.json` is committed. Its name carries a version
counter, not the table's format version: a writer names each metadata commit
`v<V>.metadata.json` starting at `v1` on create, so `v1/partitioned-parquet-data`
is a format v1 table whose metadata file is `v3.metadata.json` after two appends.
The directory name is the format version. The file name is not. Read the path
from the index rather than constructing it.

Earlier metadata files are pruned to reduce clutter. `metadata-log` still lists
them, which is correct: it is a record of past commits, not a set of live
references, and nothing resolves those paths on load.
