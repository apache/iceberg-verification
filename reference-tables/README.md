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

This directory holds complete Iceberg tables: metadata, manifest lists,
manifests, Parquet data files, Parquet position delete files, and Puffin
deletion vectors. Tables are grouped by format version.

## Assertion

```
scan(table, snapshot) == rows
```

For every snapshot a table's metadata lists, a full scan at that snapshot returns
exactly the rows in the table's `expected/scan.json`. Bytes are not compared.

## Size

A table should stay under 100 KB. The seven here are 16 KB to 65 KB.

Size tracks commit count, and almost nothing else. A commit costs roughly 12 to
14 KB, most of it the manifest and manifest list it produces. Rows are close to
free: a Parquet file holding a few rows is 1 to 2 KB, about two thirds of it
footer. A schema change writes no manifest, only a metadata file that is then
pruned, so it costs almost nothing.

So the limit is about seven commits. A table that needs more should say why in
its pull request.

## Paths

Prior to v4 the spec requires every path field to be fully qualified, so each
table carries an absolute prefix in `location`, every `manifest-list`,
`manifest_path`, `file_path` and `referenced_data_file`, every `metadata-log`
entry, the `file_path` column of every position delete file, and a deletion
vector's `referenced-data-file` Puffin blob property. That prefix is the sentinel
root:

```
/tmp/iceberg-verification/reference-tables/<format-version>/<table-name>
```

`location` writes it as `file:///tmp/...` and every other field as
`file:/tmp/...`. Both name the same file.

A consumer resolves the prefix by symlinking `/tmp/iceberg-verification` to the
root of its checkout, which leaves every committed byte untouched:

```
ln -s "$(pwd)" /tmp/iceberg-verification
```

This route is verified on Linux. It is not yet verified on Windows.

Starting with v4 the spec permits relative paths and makes `location` optional,
so a v4 table needs no symlink.

Loading a table opens only its metadata file, so a load succeeds without the
symlink. The scan in the assertion opens every manifest list, manifest and data
file, so a passing scan also shows the symlink resolves.

## Writer behavior outside the spec

The tables carry what Iceberg Java 1.11.0 writes, including three places where it
departs from the spec. The expected values do not encode them, and none of them is
spec behavior to assert.

- v2 and v3 manifests omit the `schema-id` key-value entry, which the spec
  requires for v2 and v3
  ([apache/iceberg#10927](https://github.com/apache/iceberg/issues/10927)).
- The v1 table writes `refs`, which the spec omits for v1.
- The v1 table writes partition field `field-id` in `partition-specs`, the
  deprecated `partition-spec`, and each manifest's `partition-spec` entry. The spec
  omits it for v1. The values, 1000 and 1001, are the ones the spec says v1 readers
  assign.

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

`written-by` is per table: the corpus holds whatever version was current when
each table was written.

A table may be regenerated to cover more of its Scope, or to correct it.

An addition must keep these invariants:

- The table's Scope must remain the same: an addition may only cover more of
  what it states.
- `written-by` must remain the same: the same release must write the table.
- The Input's existing `commits` must remain the same, in the same order.
- The Input may gain columns with new field IDs or rows, and must not lose any.
- Restricted to the field IDs the table had before, every row of the previous
  `expected/scan.json` must appear in the snapshot that the same commit
  produces.

A correction must keep these invariants:

- The table's Scope must remain the same.
- Every change must be to something wrong: the table does not match its Input,
  or the Input does not match the spec.
- `written-by` must remain the same, unless the fault is in that release. The
  new release must then be the table's `written-by` in `index.json`, and its
  departures from the spec documented.
- The pull request must name each expected value that changed and the spec rule
  it now follows.

Any other change must be a new table. Snapshot IDs, UUIDs and file names are
random, so a regeneration rewrites every file: the Input and
`expected/scan.json` are the reviewable diff, and the pull request must say
that the table was regenerated and why.

`GenerateTables.java` writes these tables. It is compiled against
`org.apache.iceberg:iceberg-core`, `iceberg-data` and `iceberg-parquet` 1.11.0
and `org.apache.hadoop:hadoop-common` 3.4.1, and parquet-mr 1.17.1 comes with
`iceberg-parquet`. Its main class is
`org.apache.iceberg.verification.GenerateTables`. It is not built or run by CI.

Each table directory holds a `README.md` with a Scope section and an Input
section. Every table makes the assertion above, so the Scope is what sets a
table apart: it states the reader behavior the table exercises, and what it
deliberately leaves out and why. The Input section gives the table's format
version, schema, partition spec, and each commit: the rows of one data file, the
data files of one append, the row positions of one data file that a deletion
vector deletes, the row positions of one or more data files that a position
delete file deletes, optionally with data files added in the same commit, or the
updates of a schema change. The generator reads that input, creates the table
with `HadoopTables`, and commits each in turn, writing a deletion vector with
Iceberg's `BaseDVFileWriter` in a row delta, and a position delete file with
Iceberg's Parquet position delete writer in a row delta that also adds the
commit's data files. Iceberg Java 1.11.0 does not set a position delete file's
`referenced_data_file`, so the generator sets it when every delete is in one
data file and leaves it null otherwise. It writes
each table directly at the sentinel root, so the absolute paths are correct as
written and nothing is rewritten. After writing, it prunes earlier
`metadata.json` files and deletes Hadoop's `.crc` sidecars and
`version-hint.text`. Output is not byte-reproducible, because Iceberg assigns
random UUIDs, snapshot IDs and timestamps.

A table is added by writing its `README.md` and running the generator with the
`reference-tables` directory and the table's name as arguments, for example
`reference-tables v2/partitioned-parquet-data`. It writes only that table and
prints the table's `index.json` entry. The generator deletes and rewrites the
sentinel root, so it refuses to run while `/tmp/iceberg-verification` is a
symlink, and the consumer symlink is removed first. The generated `data/` and
`metadata/` directories then replace the table's own, which are removed first so
no earlier UUID-named files remain.

Data files and position delete files are GZIP-compressed although each table
sets `write.parquet.compression-codec` to `zstd`, because the generator builds
the Parquet writers without the table's properties.

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
counter, not the table's format version. The tables were written as file system
tables, whose metadata files the spec names `v<V>.metadata.json`, starting at `v1`
on create. So `v1/partitioned-parquet-data` is a format v1 table whose metadata
file is `v3.metadata.json` after two appends.
The directory name is the format version. The file name is not. Read the path
from the index rather than constructing it.

Earlier metadata files are pruned to reduce clutter. `metadata-log` still lists
them, which is correct: it is a record of past commits, not a set of live
references, and nothing resolves those paths on load.

## Expected values

Each table directory holds `expected/scan.json`, with one entry per snapshot:

```json
{
  "snapshots": [
    {
      "snapshot-id": "7973649908883454602",
      "rows": [
        {"1": "1", "2": "alpha", "3": "2026-01-01T00:00:00.000000+00:00"},
        {"1": "2", "2": "bravo", "3": "2026-01-02T00:00:00.000000+00:00"}
      ]
    }
  ]
}
```

- `snapshot-id` is the snapshot scanned.
- `rows` is every row the scan returns at that snapshot. Each row is a struct of
  the table schema, written in the spec's
  [JSON single-value serialization](https://github.com/apache/iceberg/blob/main/format/spec.md#json-single-value-serialization):
  keyed by field ID, with every nested value in the same form.

`snapshot-id` and every `long` value are written as strings, following the
64-bit integer rule in `CONTRIBUTING.md`. This is the one departure from the
single-value serialization, which writes a `long` as a JSON number.

The rows in each file were produced by a full scan at each snapshot with
PyIceberg 0.12.0, encoded as above and sorted by `id`. They were checked against
a read of each snapshot's live data files with no Iceberg library: its manifest
list, each manifest's entries not marked `DELETED`, and the rows of each data
file those entries name, read by field ID with any field a file lacks filled from
its `initial-default`, less the positions any deletion vector that applies to
it deletes. The rows of `v3/deletion-vector-parquet-data` were instead derived
from its Input, and a PyIceberg 0.12.0 scan returns the same rows. The rows of
`v2/position-deletes-parquet-data` were also derived from its Input, by the
spec's scope rule for position delete files, then checked against the same read
with no Iceberg library, applying position delete files by that rule, and
against scans with PyIceberg 0.12.0 and 7864d4c, Iceberg Java 1.11.0 and 1.12.0,
and iceberg-go b9a9bd9.

## Procedure

Each entry in `snapshots` is checked on its own. The table is scanned at that
snapshot, as in the spec's
[Point in Time Reads](https://iceberg.apache.org/spec/#point-in-time-reads-time-travel),
through the implementation's scan API, with no filter and every column projected.
Reading the data files directly bypasses scan planning, so it does not satisfy
the assertion.

Each value is decoded as the type the table schema gives its field ID, and
compared as that decoded value. The returned rows and `rows` are then compared
as multisets, because the spec fixes no row order for a scan. List elements keep
their order. Map entries are compared as a set of key and value pairs, because
a map fixes no entry order.

Every difference is reported, naming the table, the snapshot, and the row.
