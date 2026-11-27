/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.verification;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileMetadata;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.IcebergBuild;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionSpecParser;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.deletes.BaseDVFileWriter;
import org.apache.iceberg.deletes.PositionDelete;
import org.apache.iceberg.deletes.PositionDeleteWriter;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.DeleteWriteResult;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.CharSequenceSet;
import org.apache.iceberg.util.JsonUtil;

/**
 * Writes reference tables from the Input block in each table's README.md.
 *
 * <p>Tables are written at the sentinel path so the absolute paths the spec requires prior to v4
 * are correct in the committed bytes, with no rewriting afterwards.
 *
 * <p>Usage: {@code GenerateTables <reference-tables directory> <table name>...}. Each named table
 * is read from {@code <directory>/<name>/README.md} and written under the sentinel root, and its
 * index.json entry is printed.
 */
public class GenerateTables {

  static final String SENTINEL = "file:///tmp/iceberg-verification/reference-tables";
  static final String LOCAL_ROOT = "/tmp/iceberg-verification/reference-tables";

  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      throw new IllegalArgumentException(
          "Usage: GenerateTables <reference-tables directory> <table name>...");
    }

    // The consumer setup symlinks /tmp/iceberg-verification to a checkout. Writing through that
    // link would delete the committed corpus, so the generator refuses to run while it exists.
    Path base = Paths.get(LOCAL_ROOT).getParent();
    if (Files.isSymbolicLink(base) || Files.isSymbolicLink(Paths.get(LOCAL_ROOT))) {
      throw new IllegalStateException(
          base + " is a symlink. The generator deletes and rewrites " + LOCAL_ROOT
              + ", so remove the consumer symlink before running it.");
    }

    Path inputs = Paths.get(args[0]);
    HadoopTables tables = new HadoopTables(new Configuration());
    for (int i = 1; i < args.length; i += 1) {
      String name = args[i];
      JsonNode input = readInput(inputs.resolve(name).resolve("README.md"));
      Path dir = Paths.get(LOCAL_ROOT, name);
      if (Files.exists(dir)) {
        deleteRecursively(dir);
      }
      generate(tables, name, input);
    }

    System.out.println("\nIndex entries:\n" + indexEntries());
    System.out.println("Generated under " + LOCAL_ROOT);
  }

  /** The JSON block under the README's "## Input" heading. */
  static JsonNode readInput(Path readme) throws IOException {
    String text = new String(Files.readAllBytes(readme), StandardCharsets.UTF_8);
    int heading = text.indexOf("\n## Input\n");
    int start = text.indexOf("```json\n", heading);
    int end = text.indexOf("\n```", start + 8);
    if (heading < 0 || start < 0 || end < 0) {
      throw new IllegalArgumentException("No Input JSON block in " + readme);
    }
    return JsonUtil.mapper().readTree(text.substring(start + 8, end));
  }

  static void generate(HadoopTables tables, String name, JsonNode input) throws IOException {
    Schema schema = SchemaParser.fromJson(input.get("schema"));
    PartitionSpec spec = PartitionSpecParser.fromJson(schema, input.get("partition-spec"));
    int formatVersion = input.get("format-version").asInt();

    Map<String, String> props = new HashMap<>();
    props.put(TableProperties.FORMAT_VERSION, String.valueOf(formatVersion));
    Table table = tables.create(schema, spec, props, SENTINEL + "/" + name);

    for (JsonNode commit : input.get("commits")) {
      if (commit.has("data-files")) {
        appendFiles(table, commit.get("data-files"));
        continue;
      }
      if (commit.has("deletion-vector")) {
        addDeletionVector(table, commit.get("deletion-vector"));
        continue;
      }
      if (commit.has("position-deletes")) {
        addPositionDeletes(table, commit.get("position-deletes"));
        continue;
      }
      if (commit.has("schema-updates")) {
        updateSchema(table, commit.get("schema-updates"));
        continue;
      }
      List<Record> records = new ArrayList<>();
      for (JsonNode row : commit.get("rows")) {
        records.add(record(table.schema().asStruct(), row));
      }
      DataFile file = write(table, commit.get("data-file").asText(), records);
      table.newAppend().appendFile(file).commit();
    }

    finish(table, name, input.get("description").asText());
  }

  /** Several data files in one append, each written as in a single-file commit. */
  static void appendFiles(Table table, JsonNode files) throws IOException {
    AppendFiles append = table.newAppend();
    for (JsonNode file : files) {
      List<Record> records = new ArrayList<>();
      for (JsonNode row : file.get("rows")) {
        records.add(record(table.schema().asStruct(), row));
      }
      append.appendFile(write(table, file.get("data-file").asText(), records));
    }
    append.commit();
  }

  /** One deletion vector for the given row positions of a live data file, in a row delta. */
  static void addDeletionVector(Table table, JsonNode dv) throws IOException {
    String tag = dv.get("data-file").asText();
    DataFile target = null;
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      for (FileScanTask task : tasks) {
        if (task.file().location().endsWith("/data/" + tag + ".parquet")) {
          if (!task.deletes().isEmpty()) {
            throw new IllegalArgumentException("Data file " + tag + " already has deletes");
          }
          target = task.file();
        }
      }
    }
    if (target == null) {
      throw new IllegalArgumentException("No live data file " + tag);
    }

    String location = table.location() + "/data/" + tag + "-deletes.puffin";
    BaseDVFileWriter writer =
        new BaseDVFileWriter(() -> table.io().newOutputFile(location), path -> null);
    try (BaseDVFileWriter open = writer) {
      for (JsonNode node : dv.get("positions")) {
        long pos = Long.parseLong(node.asText());
        if (pos < 0 || pos >= target.recordCount()) {
          throw new IllegalArgumentException("Position " + pos + " is not a row of " + tag);
        }
        open.delete(target.location(), pos, table.specs().get(target.specId()), target.partition());
      }
    }

    DeleteWriteResult result = writer.result();
    RowDelta delta = table.newRowDelta().validateDataFilesExist(result.referencedDataFiles());
    result.deleteFiles().forEach(delta::addDeletes);
    delta.commit();
  }

  /**
   * One Parquet position delete file for the given row positions of one or more data files, in a
   * row delta together with any data files it adds, so their deletes share its sequence number.
   */
  static void addPositionDeletes(Table table, JsonNode pd) throws IOException {
    if (((HasTableOperations) table).operations().current().formatVersion() != 2) {
      throw new IllegalArgumentException("Position delete files may be added to v2 tables only");
    }

    Map<String, DataFile> live = new HashMap<>();
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      for (FileScanTask task : tasks) {
        live.put(tag(task.file()), task.file());
      }
    }

    RowDelta delta = table.newRowDelta();
    if (pd.has("data-files")) {
      for (JsonNode file : pd.get("data-files")) {
        List<Record> records = new ArrayList<>();
        for (JsonNode row : file.get("rows")) {
          records.add(record(table.schema().asStruct(), row));
        }
        DataFile added = write(table, file.get("data-file").asText(), records);
        live.put(tag(added), added);
        delta.addRows(added);
      }
    }

    String location = table.location() + "/data/" + pd.get("file").asText() + ".parquet";
    PositionDeleteWriter<Record> writer =
        Parquet.writeDeletes(table.io().newOutputFile(location))
            .withSpec(table.spec())
            .metricsConfig(MetricsConfig.forPositionDelete(table))
            .overwrite()
            .buildPositionWriter();

    // The spec requires entries sorted by file_path, then pos. They are written in Input order, so
    // an Input that is not sorted is refused rather than reordered.
    PositionDelete<Record> entry = PositionDelete.create();
    String lastPath = null;
    long lastPos = -1;
    try (PositionDeleteWriter<Record> open = writer) {
      for (JsonNode node : pd.get("deletes")) {
        String tag = node.get("data-file").asText();
        DataFile target = live.get(tag);
        if (target == null) {
          throw new IllegalArgumentException("No live data file " + tag);
        }
        String path = target.location();
        for (JsonNode p : node.get("positions")) {
          long pos = Long.parseLong(p.asText());
          if (pos < 0 || pos >= target.recordCount()) {
            throw new IllegalArgumentException("Position " + pos + " is not a row of " + tag);
          }
          int order = lastPath == null ? 1 : path.compareTo(lastPath);
          if (order == 0 && pos == lastPos) {
            throw new IllegalArgumentException("Position " + pos + " of " + tag + " is repeated");
          }
          if (order < 0 || (order == 0 && pos < lastPos)) {
            throw new IllegalArgumentException("Deletes are not sorted by file_path, then pos");
          }
          open.write(entry.set(path, pos));
          lastPath = path;
          lastPos = pos;
        }
      }
    }

    // Java 1.11 never sets referenced_data_file on a position delete file. The spec allows it
    // when every delete is in one data file, so it is set then and left null otherwise.
    DeleteFile deletes = writer.toDeleteFile();
    CharSequenceSet referenced = writer.referencedDataFiles();
    if (referenced.size() == 1) {
      deletes =
          FileMetadata.deleteFileBuilder(table.spec())
              .copy(deletes)
              .withReferencedDataFile(referenced.iterator().next())
              .build();
    }
    delta.addDeletes(deletes).validateDataFilesExist(referenced).commit();
  }

  /** The Input's name for a data file, which write() puts at data/<tag>.parquet. */
  static String tag(DataFile file) {
    String location = file.location();
    String name = location.substring(location.lastIndexOf('/') + 1);
    return name.substring(0, name.length() - ".parquet".length());
  }

  /** One schema commit of add-column and update-default actions, with defaults as input values. */
  static void updateSchema(Table table, JsonNode updates) {
    UpdateSchema update = table.updateSchema();
    for (JsonNode u : updates) {
      String action = u.get("action").asText();
      String name = u.get("name").asText();
      switch (action) {
        case "add-column":
          Type type = Types.fromPrimitiveString(u.get("type").asText());
          Literal<?> initial = literal(type, u.get("initial-default"));
          if (u.get("required").asBoolean()) {
            update.addRequiredColumn(name, type, initial);
          } else {
            update.addColumn(name, type, initial);
          }
          break;
        case "update-default":
          Type current = update.apply().findType(name);
          if (current == null) {
            throw new IllegalArgumentException("Unknown column " + name);
          }
          update.updateColumnDefault(name, literal(current, u.get("write-default")));
          break;
        default:
          throw new IllegalArgumentException("Unknown schema update action " + action);
      }
    }
    update.commit();
    // A schema commit leaves the table's cached metadata stale, and finish() reads it.
    table.refresh();
  }

  static Literal<?> literal(Type type, JsonNode node) {
    Object value = value(type, node);
    return value == null ? null : Expressions.lit(value);
  }

  /** A data file belongs to one partition, so every record shares the first record's value. */
  static DataFile write(Table table, String tag, List<Record> records) throws IOException {
    StructLike partition = null;
    if (table.spec().isPartitioned()) {
      InternalRecordWrapper wrapper = new InternalRecordWrapper(table.schema().asStruct());
      PartitionKey key = new PartitionKey(table.spec(), table.schema());
      key.partition(wrapper.wrap(records.get(0)));
      for (Record record : records) {
        PartitionKey other = new PartitionKey(table.spec(), table.schema());
        other.partition(wrapper.wrap(record));
        if (!other.equals(key)) {
          throw new IllegalArgumentException(
              "Data file " + tag + " holds rows from more than one partition");
        }
      }
      partition = key;
    }

    OutputFile out = table.io().newOutputFile(table.location() + "/data/" + tag + ".parquet");
    DataWriter<Record> writer =
        Parquet.writeData(out)
            .schema(table.schema())
            .createWriterFunc(GenericParquetWriter::create)
            .withSpec(table.spec())
            .withPartition(partition)
            .overwrite()
            .build();

    try (DataWriter<Record> open = writer) {
      for (Record record : records) {
        open.write(record);
      }
    }
    return writer.toDataFile();
  }

  // input values, in the spec's single-value JSON keyed by field id, with long values as strings

  static Record record(Types.StructType struct, JsonNode node) {
    Record record = GenericRecord.create(struct);
    for (Types.NestedField field : struct.fields()) {
      record.setField(field.name(), value(field.type(), node.get(String.valueOf(field.fieldId()))));
    }
    return record;
  }

  static Object value(Type type, JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    switch (type.typeId()) {
      case LONG:
        return Long.parseLong(node.asText());
      case INTEGER:
        return node.intValue();
      case DOUBLE:
        return node.doubleValue();
      case STRING:
        return node.textValue();
      case TIMESTAMP:
        if (!((Types.TimestampType) type).shouldAdjustToUTC()) {
          break;
        }
        return OffsetDateTime.parse(node.textValue());
      case STRUCT:
        return record(type.asStructType(), node);
      case LIST:
        List<Object> list = new ArrayList<>();
        for (JsonNode element : node) {
          list.add(value(type.asListType().elementType(), element));
        }
        return list;
      case MAP:
        Map<Object, Object> map = new LinkedHashMap<>();
        JsonNode keys = node.get("keys");
        JsonNode values = node.get("values");
        for (int i = 0; i < keys.size(); i += 1) {
          map.put(
              value(type.asMapType().keyType(), keys.get(i)),
              value(type.asMapType().valueType(), values.get(i)));
        }
        return map;
      default:
        break;
    }
    throw new UnsupportedOperationException("No input conversion for type " + type);
  }

  // post-processing

  /** Per table, in generation order, for the index. */
  static final Map<String, String[]> entries = new LinkedHashMap<>();

  /**
   * Keeps only the current metadata.json and removes what is not an Iceberg artifact: the .crc
   * sidecars Hadoop's local filesystem writes, and version-hint.text, a Hadoop convention that
   * iceberg-rust and iceberg-go do not read.
   */
  static void finish(Table table, String name, String description) throws IOException {
    Path root = Paths.get(LOCAL_ROOT, name);
    Path metadata = root.resolve("metadata");
    int current =
        Integer.parseInt(
            new String(Files.readAllBytes(metadata.resolve("version-hint.text"))).trim());
    try (Stream<Path> files = Files.list(metadata)) {
      for (Path p : files.toArray(Path[]::new)) {
        String fn = p.getFileName().toString();
        if (fn.endsWith(".metadata.json") && !fn.equals("v" + current + ".metadata.json")) {
          Files.delete(p);
        }
      }
    }
    try (Stream<Path> all = Files.walk(root)) {
      for (Path p : all.toArray(Path[]::new)) {
        if (p.getFileName().toString().endsWith(".crc")) {
          Files.delete(p);
        }
      }
    }
    Files.delete(metadata.resolve("version-hint.text"));
    int formatVersion = ((HasTableOperations) table).operations().current().formatVersion();
    entries.put(
        name,
        new String[] {
          String.valueOf(formatVersion),
          "Iceberg Java " + IcebergBuild.version(),
          "parquet",
          name + "/metadata/v" + current + ".metadata.json",
          description
        });
    System.out.printf(
        "  %-22s v%d  snapshots=%d%n", name, formatVersion, size(table.snapshots()));
  }

  static String indexEntries() {
    StringBuilder json = new StringBuilder();
    int i = 0;
    for (Map.Entry<String, String[]> e : entries.entrySet()) {
      String[] v = e.getValue();
      json.append("    {\n")
          .append("      \"name\": \"").append(e.getKey()).append("\",\n")
          .append("      \"format-version\": ").append(v[0]).append(",\n")
          .append("      \"written-by\": \"").append(v[1]).append("\",\n")
          .append("      \"data-file-format\": \"").append(v[2]).append("\",\n")
          .append("      \"metadata\": \"").append(v[3]).append("\",\n")
          .append("      \"description\": \"").append(v[4]).append("\"\n")
          .append("    }").append(++i < entries.size() ? ",\n" : "\n");
    }
    return json.toString();
  }

  static int size(Iterable<?> iterable) {
    int n = 0;
    for (Object ignored : iterable) {
      n += 1;
    }
    return n;
  }

  static void deleteRecursively(Path path) throws IOException {
    try (Stream<Path> walk = Files.walk(path)) {
      walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
  }
}
