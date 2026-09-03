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

package org.apache.iceberg.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/**
 * Reference runner that checks Apache Iceberg (Java) against the type-surface conformance fixtures.
 * It reads every {@code table-spec/**}/cases.json and parses each {@code input} with the published
 * reader: a string input via {@link Types#fromTypeName(String)}, an object input (a nested type) via
 * {@link org.apache.iceberg.SchemaParser#fromJson(String)}. It then applies the assertion contract:
 *
 * <pre>
 *   valid=false            -&gt; the parser must throw (PASS); returning a type is a FAIL
 *   valid=true             -&gt; parse ok and decoded shape == decoded (PASS); a mismatch is a FAIL
 *   valid=true, not modeled -&gt; the parser throws because Java does not model the type (UNSUPPORTED)
 * </pre>
 *
 * <p>Java currently models every table-spec type, so the UNSUPPORTED path is dormant; the
 * classification matches the Go and Rust runners regardless. The process exits 0 when no case
 * fails, 1 on any FAIL, and 2 on a setup error such as a missing tree or an unreadable fixture.
 */
public final class VerifyTypes {

  // Directories under the repo root that hold cases.
  private static final String[] SURFACE_ROOTS = {"table-spec"};

  private VerifyTypes() {}

  public static void main(String[] args) {
    Path start = args.length > 0 ? Paths.get(args[0]) : Paths.get(System.getProperty("user.dir"));
    Path root = findRepoRoot(start.toAbsolutePath());
    if (root == null) {
      System.err.println("Could not locate repo root (no table-spec/) above " + start);
      System.exit(2);
    }

    List<JsonNode> cases;
    try {
      cases = loadCases(root);
    } catch (IOException e) {
      // A fixture-read failure is a setup error (exit 2), not a conformance FAIL.
      System.err.println("Setup error reading fixtures: " + e.getMessage());
      System.exit(2);
      return;
    }
    if (cases.isEmpty()) {
      System.err.println("No cases found under " + root);
      System.exit(2);
    }

    int pass = 0;
    int fail = 0;
    int unsupported = 0;
    List<String> failIds = new ArrayList<>();
    List<String> unsupportedIds = new ArrayList<>();

    for (JsonNode node : cases) {
      String id = node.get("id").asText();
      JsonNode input = node.get("input");
      boolean valid = node.get("valid").asBoolean();

      Type parsed = null;
      RuntimeException parseError = null;
      try {
        parsed = parseType(input);
      } catch (RuntimeException e) {
        parseError = e;
      }

      if (!valid) {
        if (parseError != null) {
          System.out.printf("%-32s PASS (rejected: %s)%n", id, parseError.getMessage());
          pass++;
        } else {
          System.out.printf("%-32s FAIL (expected reject, parsed as \"%s\")%n", id, parsed);
          fail++;
          failIds.add(id);
        }
        continue;
      }

      // valid=true: a parser throw means Java does not model this type, not a failure.
      if (parseError != null) {
        System.out.printf("%-32s UNSUPPORTED (not modeled: %s)%n", id, parseError.getMessage());
        unsupported++;
        unsupportedIds.add(id);
        continue;
      }

      JsonNode decoded = node.get("decoded");
      String mismatch = decodedMismatch(parsed, decoded);
      if (mismatch != null) {
        System.out.printf(
            "%-32s FAIL (decoded mismatch: expected %s, actual %s [%s])%n",
            id, decoded, describe(parsed), mismatch);
        fail++;
        failIds.add(id);
        continue;
      }

      // Optional canonical check: when a case carries a "canonical" field, the re-serialized type
      // string must match it. Cases without the field remain decode-only.
      if (node.hasNonNull("canonical")) {
        String canonical = node.get("canonical").asText();
        String actual = parsed.toString();
        if (!actual.equals(canonical)) {
          System.out.printf(
              "%-32s FAIL (Canonical mismatch: expected \"%s\", actual \"%s\")%n",
              id, canonical, actual);
          fail++;
          failIds.add(id);
          continue;
        }
      }

      System.out.printf("%-32s PASS%n", id);
      pass++;
    }

    System.out.printf(
        "%nTOTALS: %d cases | PASS=%d FAIL=%d UNSUPPORTED=%d SKIP=0%n",
        cases.size(), pass, fail, unsupported);
    if (!failIds.isEmpty()) {
      System.out.println("FAIL ids: " + failIds);
    }
    if (!unsupportedIds.isEmpty()) {
      System.out.println("UNSUPPORTED ids: " + unsupportedIds);
    }
    if (fail > 0) {
      System.exit(1);
    }
  }

  /**
   * Parses a type from its Appendix-C JSON: a bare string for primitive and geospatial types (via
   * {@link Types#fromTypeName(String)}), or an object for a nested type, wrapped as the single field
   * of a schema and parsed via {@link SchemaParser#fromJson(String)}. The wrapper field uses the
   * highest non-reserved field id (2147483447) so it never collides with the ids inside a nested
   * type under test, which Iceberg would otherwise reject as duplicate schema ids.
   */
  private static Type parseType(JsonNode input) {
    if (input.isTextual()) {
      return Types.fromTypeName(input.asText());
    }
    String schemaJson =
        "{\"type\":\"struct\",\"schema-id\":0,\"fields\":[{\"id\":2147483447,\"name\":\"f\","
            + "\"required\":true,\"type\":"
            + input
            + "}]}";
    return SchemaParser.fromJson(schemaJson).columns().get(0).type();
  }

  /** Walks up from {@code start} until it finds a directory containing table-spec/. */
  private static Path findRepoRoot(Path start) {
    for (Path dir = start; dir != null; dir = dir.getParent()) {
      if (Files.isDirectory(dir.resolve("table-spec"))) {
        return dir;
      }
    }
    return null;
  }

  /** Reads every cases.json under the surface roots, sorted by case id. */
  private static List<JsonNode> loadCases(Path root) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    List<JsonNode> cases = new ArrayList<>();
    for (String surface : SURFACE_ROOTS) {
      Path base = root.resolve(surface);
      if (!Files.isDirectory(base)) {
        continue;
      }
      List<Path> files;
      try (Stream<Path> walk = Files.walk(base)) {
        files =
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals("cases.json"))
                .collect(Collectors.toList());
      }
      for (Path file : files) {
        JsonNode document = mapper.readTree(file.toFile());
        JsonNode array = document.get("cases");
        if (array == null || !array.isArray()) {
          throw new IOException("Missing \"cases\" array in " + file);
        }
        for (JsonNode node : array) {
          cases.add(node);
        }
      }
    }
    cases.sort(Comparator.comparing(node -> node.get("id").asText()));
    return cases;
  }

  /**
   * Compares a parsed type against the language-neutral {@code decoded} shape. Returns null on
   * match, or a short reason on mismatch.
   */
  private static String decodedMismatch(Type type, JsonNode decoded) {
    String kind = decoded.get("type").asText();
    switch (kind) {
      case "decimal":
        if (!(type instanceof Types.DecimalType)) {
          return "Not a decimal";
        }
        Types.DecimalType d = (Types.DecimalType) type;
        return d.precision() == decoded.get("precision").asInt()
                && d.scale() == decoded.get("scale").asInt()
            ? null
            : "Precision/scale";
      case "fixed":
        if (!(type instanceof Types.FixedType)) {
          return "Not a fixed";
        }
        return ((Types.FixedType) type).length() == decoded.get("length").asInt() ? null : "Length";
      case "geometry":
        if (!(type instanceof Types.GeometryType)) {
          return "Not a geometry";
        }
        return ((Types.GeometryType) type).crs().equals(decoded.get("crs").asText()) ? null : "Crs";
      case "geography":
        if (!(type instanceof Types.GeographyType)) {
          return "Not a geography";
        }
        Types.GeographyType g = (Types.GeographyType) type;
        return g.crs().equals(decoded.get("crs").asText())
                && g.algorithm().toString().equals(decoded.get("algorithm").asText())
            ? null
            : "Crs/algorithm";
      case "struct":
        if (!(type instanceof Types.StructType)) {
          return "Not a struct";
        }
        List<Types.NestedField> fields = ((Types.StructType) type).fields();
        JsonNode fieldsNode = decoded.get("fields");
        if (fields.size() != fieldsNode.size()) {
          return "Field count";
        }
        for (int i = 0; i < fields.size(); i++) {
          Types.NestedField f = fields.get(i);
          JsonNode fn = fieldsNode.get(i);
          if (f.fieldId() != fn.get("id").asInt()) {
            return "Field id";
          }
          if (!f.name().equals(fn.get("name").asText())) {
            return "Field name";
          }
          if (f.isRequired() != fn.get("required").asBoolean()) {
            return "Field required";
          }
          String sub = decodedMismatch(f.type(), fn.get("type"));
          if (sub != null) {
            return "field[" + i + "]: " + sub;
          }
        }
        return null;
      case "list":
        if (!(type instanceof Types.ListType)) {
          return "Not a list";
        }
        Types.ListType lt = (Types.ListType) type;
        if (lt.elementId() != decoded.get("element-id").asInt()) {
          return "element-id";
        }
        if (lt.isElementRequired() != decoded.get("element-required").asBoolean()) {
          return "element-required";
        }
        return decodedMismatch(lt.elementType(), decoded.get("element"));
      case "map":
        if (!(type instanceof Types.MapType)) {
          return "Not a map";
        }
        Types.MapType mt = (Types.MapType) type;
        if (mt.keyId() != decoded.get("key-id").asInt()) {
          return "key-id";
        }
        if (mt.valueId() != decoded.get("value-id").asInt()) {
          return "value-id";
        }
        if (mt.isValueRequired() != decoded.get("value-required").asBoolean()) {
          return "value-required";
        }
        String keyMismatch = decodedMismatch(mt.keyType(), decoded.get("key"));
        if (keyMismatch != null) {
          return "key: " + keyMismatch;
        }
        return decodedMismatch(mt.valueType(), decoded.get("value"));
      default:
        // Primitive: the language-neutral name equals the canonical type string.
        return type.toString().equals(kind) ? null : "Primitive name";
    }
  }

  private static String describe(Type type) {
    return type.getClass().getSimpleName() + "(\"" + type + "\")";
  }
}
