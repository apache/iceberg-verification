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
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

/**
 * Checks Apache Iceberg (Java) against the type-surface fixtures. Parses each case {@code input}
 * with {@link Types#fromTypeName(String)} (string types) or {@link SchemaParser#fromJson(String)}
 * (nested types) and grades it per the shared contract in {@code runners/README.md}. Exits 0 when
 * no case fails, 1 on any fail, 2 on a setup error.
 */
public final class VerifyTypes {

  private static final String[] SURFACE_ROOTS = {"table-spec/types"};

  private VerifyTypes() {}

  public static void main(String[] args) {
    Path start = Paths.get(System.getProperty("user.dir"));
    List<String> surfaces = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (a.equals("--surface")) {
        if (i + 1 >= args.length) {
          System.err.println("Setup error: --surface requires a value");
          System.exit(2);
        }
        surfaces.add(args[++i]);
      } else if (a.startsWith("--surface=")) {
        surfaces.add(a.substring("--surface=".length()));
      } else {
        start = Paths.get(a);
      }
    }
    String env = System.getenv("CONFORMANCE_SURFACES");
    if (env != null) {
      for (String s : env.split("[,\\s]+")) {
        if (!s.isEmpty()) {
          surfaces.add(s);
        }
      }
    }
    Path root = findRepoRoot(start.toAbsolutePath());
    if (root == null) {
      System.err.println("Could not locate repo root (no table-spec/) above " + start);
      System.exit(2);
    }

    List<JsonNode> cases;
    try {
      cases = loadCases(root, surfaces);
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
    int advisoryFail = 0;
    int skip = 0;
    List<String> failIds = new ArrayList<>();
    List<String> advisoryIds = new ArrayList<>();
    List<String> skipIds = new ArrayList<>();

    for (JsonNode node : cases) {
      String id = node.get("id").asText();
      String[] graded = grade(node);
      String status = graded[0];
      String detail = graded[1];

      switch (status) {
        case "pass":
          pass++;
          break;
        case "advisory_fail":
          advisoryFail++;
          advisoryIds.add(id);
          break;
        case "skip":
          skip++;
          skipIds.add(id);
          break;
        default:
          fail++;
          failIds.add(id);
          break;
      }
      if (detail.isEmpty()) {
        System.out.printf(Locale.ROOT, "%-32s %s%n", id, status);
      } else {
        System.out.printf(Locale.ROOT, "%-32s %s (%s)%n", id, status, detail);
      }
    }

    System.out.printf(
        Locale.ROOT,
        "%nTOTALS: %d cases | pass=%d fail=%d advisory_fail=%d skip=%d%n",
        cases.size(),
        pass,
        fail,
        advisoryFail,
        skip);
    if (!failIds.isEmpty()) {
      System.out.println("FAIL ids: " + failIds);
    }
    if (!advisoryIds.isEmpty()) {
      System.out.println("ADVISORY_FAIL ids: " + advisoryIds);
    }
    if (!skipIds.isEmpty()) {
      System.out.println("SKIP ids: " + skipIds);
    }
    if (fail > 0) {
      System.exit(1);
    }
  }

  /** Grades one case, returning {@code {status, detail}}. */
  static String[] grade(JsonNode node) {
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

    String status;
    String detail;

    if (!valid) {
      if (parseError != null) {
        status = "pass";
        detail = "rejected: " + parseError.getMessage();
      } else {
        status = failState(node);
        detail = "expected reject, parsed as \"" + parsed + "\"";
      }
    } else if (parseError != null) {
      if (!isModeled(input)) {
        status = "skip";
        detail = "not modeled: " + parseError.getMessage();
      } else {
        status = failState(node);
        detail =
            "expected accept, parse threw "
                + parseError.getClass().getSimpleName()
                + ": "
                + parseError.getMessage();
      }
    } else {
      JsonNode decoded = node.get("decoded");
      if (decoded == null) {
        System.err.println("Setup error: valid case " + id + " has no decoded shape");
        System.exit(2);
      }
      String mismatch = decodedMismatch(parsed, decoded);
      if (mismatch != null) {
        status = failState(node);
        detail =
            "decoded mismatch: expected "
                + decoded
                + ", actual "
                + describe(parsed)
                + " ["
                + mismatch
                + "]";
      } else if (node.hasNonNull("canonical")
          && !parsed.toString().equals(node.get("canonical").asText())) {
        // The case pins a canonical spelling: the re-serialized type must reproduce it.
        status = failState(node);
        detail =
            "canonical mismatch: expected \""
                + node.get("canonical").asText()
                + "\", actual \""
                + parsed
                + "\"";
      } else {
        status = "pass";
        detail = "";
      }
    }
    return new String[] {status, detail};
  }

  static String failState(JsonNode node) {
    return node.hasNonNull("normative_level")
            && "should".equals(node.get("normative_level").asText())
        ? "advisory_fail"
        : "fail";
  }

  private static final Set<String> EXACT_KEYWORDS =
      Set.of(
          "boolean",
          "string",
          "int",
          "long",
          "float",
          "double",
          "timestamp",
          "timestamptz",
          "timestamp_ns",
          "timestamptz_ns",
          "date",
          "time",
          "uuid",
          "binary",
          "unknown",
          "variant");
  private static final String[] PREFIX_KEYWORDS = {"fixed", "decimal", "geometry", "geography"};

  static boolean isModeled(JsonNode input) {
    if (!input.isTextual()) {
      return true;
    }
    String s = input.asText();
    if (EXACT_KEYWORDS.contains(s)) {
      return true;
    }
    for (String p : PREFIX_KEYWORDS) {
      if (s.startsWith(p)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Parses a type from its Appendix-C JSON: a bare string for primitive and geospatial types (via
   * {@link Types#fromTypeName(String)}), or an object for a nested type, wrapped as the single
   * field of a schema and parsed via {@link SchemaParser#fromJson(String)}. The wrapper field uses
   * a very high field id (2147483447) so it never collides with the ids inside a nested type under
   * test, which Iceberg would otherwise reject as duplicate schema ids.
   */
  static Type parseType(JsonNode input) {
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

  private static Path findRepoRoot(Path start) {
    for (Path dir = start; dir != null; dir = dir.getParent()) {
      if (Files.isDirectory(dir.resolve("table-spec"))) {
        return dir;
      }
    }
    return null;
  }

  private static boolean surfaceMatch(String rel, List<String> surfaces) {
    if (surfaces.isEmpty()) {
      return true;
    }
    for (String s : surfaces) {
      String prefix = "table-spec/" + s.replaceAll("/+$", "");
      if (rel.equals(prefix) || rel.startsWith(prefix + "/")) {
        return true;
      }
    }
    return false;
  }

  private static List<JsonNode> loadCases(Path root, List<String> surfaces) throws IOException {
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
        String rel = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
        if (!surfaceMatch(rel, surfaces)) {
          continue;
        }
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
  static String decodedMismatch(Type type, JsonNode decoded) {
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
