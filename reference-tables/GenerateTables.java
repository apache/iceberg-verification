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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.IcebergBuild;
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
import org.apache.iceberg.util.JsonUtil;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type.Repetition;

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
    if (input.has("properties")) {
      // a value that is not a string, such as a name mapping, is written as its compact JSON
      for (Map.Entry<String, JsonNode> e : input.get("properties").properties()) {
        JsonNode value = e.getValue();
        props.put(e.getKey(), value.isTextual() ? value.textValue() : value.toString());
      }
    }
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
      if (commit.has("schema-updates")) {
        updateSchema(table, commit.get("schema-updates"));
        continue;
      }
      if (commit.has("file-schema")) {
        DataFile file = writeWithFileSchema(table, commit);
        table.newAppend().appendFile(file).commit();
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

  /**
   * Writes a data file whose own schema differs from the table's, with parquet-mr rather than
   * Iceberg's writer, so that the file can carry column names, column order and field IDs the table
   * schema does not. The file schema is given in the spec's JSON form. Its field IDs key the rows
   * and are written to the file, except those listed in {@code without-field-ids}, which are
   * written with no field ID at all. The data file entry carries the record count and no column
   * metrics, since metrics are keyed by field ID.
   */
  static DataFile writeWithFileSchema(Table table, JsonNode commit) throws IOException {
    if (table.spec().isPartitioned()) {
      throw new IllegalArgumentException("file-schema is supported for unpartitioned tables only");
    }
    Schema fileSchema = SchemaParser.fromJson(commit.get("file-schema"));
    Set<Integer> withoutIds = new HashSet<>();
    if (commit.has("without-field-ids")) {
      for (JsonNode id : commit.get("without-field-ids")) {
        withoutIds.add(id.intValue());
      }
    }

    List<org.apache.parquet.schema.Type> columns = new ArrayList<>();
    for (Types.NestedField field : fileSchema.columns()) {
      columns.add(parquetType(field, withoutIds));
    }
    MessageType message = new MessageType("table", columns);

    String location = table.location() + "/data/" + commit.get("data-file").asText() + ".parquet";
    SimpleGroupFactory groups = new SimpleGroupFactory(message);
    long count = 0;
    try (ParquetWriter<Group> writer =
        ExampleParquetWriter.builder(new org.apache.hadoop.fs.Path(location))
            .withConf(new Configuration())
            .withType(message)
            .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
            .build()) {
      for (JsonNode row : commit.get("rows")) {
        Group group = groups.newGroup();
        fill(group, fileSchema.asStruct(), record(fileSchema.asStruct(), row));
        writer.write(group);
        count += 1;
      }
    }

    return DataFiles.builder(table.spec())
        .withInputFile(table.io().newInputFile(location))
        .withFormat(FileFormat.PARQUET)
        .withRecordCount(count)
        .build();
  }

  /** A field as a Parquet type, with three-level lists and maps as the spec's Parquet mapping. */
  static org.apache.parquet.schema.Type parquetType(
      Types.NestedField field, Set<Integer> withoutIds) {
    Repetition repetition = field.isOptional() ? Repetition.OPTIONAL : Repetition.REQUIRED;
    Type type = field.type();
    org.apache.parquet.schema.Type result;
    switch (type.typeId()) {
      case LONG:
        result =
            org.apache.parquet.schema.Types.primitive(PrimitiveTypeName.INT64, repetition)
                .named(field.name());
        break;
      case INTEGER:
        result =
            org.apache.parquet.schema.Types.primitive(PrimitiveTypeName.INT32, repetition)
                .named(field.name());
        break;
      case DOUBLE:
        result =
            org.apache.parquet.schema.Types.primitive(PrimitiveTypeName.DOUBLE, repetition)
                .named(field.name());
        break;
      case STRING:
        result =
            org.apache.parquet.schema.Types.primitive(PrimitiveTypeName.BINARY, repetition)
                .as(LogicalTypeAnnotation.stringType())
                .named(field.name());
        break;
      case STRUCT:
        {
          List<org.apache.parquet.schema.Type> fields = new ArrayList<>();
          for (Types.NestedField child : type.asStructType().fields()) {
            fields.add(parquetType(child, withoutIds));
          }
          result = new GroupType(repetition, field.name(), fields);
          break;
        }
      case LIST:
        {
          Types.NestedField element = type.asListType().fields().get(0);
          result =
              org.apache.parquet.schema.Types.buildGroup(repetition)
                  .as(LogicalTypeAnnotation.listType())
                  .addField(
                      new GroupType(
                          Repetition.REPEATED,
                          "list",
                          parquetType(rename(element, "element"), withoutIds)))
                  .named(field.name());
          break;
        }
      case MAP:
        {
          Types.NestedField key = type.asMapType().fields().get(0);
          Types.NestedField value = type.asMapType().fields().get(1);
          result =
              org.apache.parquet.schema.Types.buildGroup(repetition)
                  .as(LogicalTypeAnnotation.mapType())
                  .addField(
                      new GroupType(
                          Repetition.REPEATED,
                          "key_value",
                          parquetType(rename(key, "key"), withoutIds),
                          parquetType(rename(value, "value"), withoutIds)))
                  .named(field.name());
          break;
        }
      default:
        throw new UnsupportedOperationException("No Parquet type for " + type);
    }
    return withoutIds.contains(field.fieldId()) ? result : result.withId(field.fieldId());
  }

  static Types.NestedField rename(Types.NestedField field, String name) {
    return Types.NestedField.from(field).withName(name).build();
  }

  /** Copies a record into a Parquet group of the same shape. A null value is left unset. */
  static void fill(Group group, Types.StructType struct, Record record) {
    List<Types.NestedField> fields = struct.fields();
    for (int i = 0; i < fields.size(); i += 1) {
      Types.NestedField field = fields.get(i);
      Object value = record.get(i);
      if (value != null) {
        add(group, field.name(), field.type(), value);
      }
    }
  }

  static void add(Group group, String name, Type type, Object value) {
    switch (type.typeId()) {
      case LONG:
        group.add(name, (Long) value);
        return;
      case INTEGER:
        group.add(name, (Integer) value);
        return;
      case DOUBLE:
        group.add(name, (Double) value);
        return;
      case STRING:
        group.add(name, (String) value);
        return;
      case STRUCT:
        fill(group.addGroup(name), type.asStructType(), (Record) value);
        return;
      case LIST:
        {
          Group list = group.addGroup(name);
          for (Object element : (List<?>) value) {
            Group entry = list.addGroup("list");
            if (element != null) {
              add(entry, "element", type.asListType().elementType(), element);
            }
          }
          return;
        }
      case MAP:
        {
          Group map = group.addGroup(name);
          for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
            Group entry = map.addGroup("key_value");
            add(entry, "key", type.asMapType().keyType(), e.getKey());
            if (e.getValue() != null) {
              add(entry, "value", type.asMapType().valueType(), e.getValue());
            }
          }
          return;
        }
      default:
        throw new UnsupportedOperationException("No Parquet value for " + type);
    }
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
