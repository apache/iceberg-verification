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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

/** Guards the grader - grade() dispatch and the comparison verbs - against regressions. */
class VerifyTypesTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode json(String raw) throws Exception {
    return mapper.readTree(raw);
  }

  @Test
  void decodedMismatchReturnsNullOnMatchAndReasonOnMismatch() throws Exception {
    assertNull(VerifyTypes.decodedMismatch(Types.IntegerType.get(), json("{\"type\":\"int\"}")));
    assertNotNull(VerifyTypes.decodedMismatch(Types.StringType.get(), json("{\"type\":\"int\"}")));
    assertNull(
        VerifyTypes.decodedMismatch(
            Types.DecimalType.of(9, 2),
            json("{\"type\":\"decimal\",\"precision\":9,\"scale\":2}")));
    assertNotNull(
        VerifyTypes.decodedMismatch(
            Types.DecimalType.of(9, 2),
            json("{\"type\":\"decimal\",\"precision\":10,\"scale\":2}")));
  }

  @Test
  void failStateIsAdvisoryForShouldAndFailOtherwise() throws Exception {
    assertEquals("advisory_fail", VerifyTypes.failState(json("{\"normative_level\":\"should\"}")));
    assertEquals("fail", VerifyTypes.failState(json("{}")));
    assertEquals("fail", VerifyTypes.failState(json("{\"normative_level\":\"must\"}")));
  }

  @Test
  void isModeledTracksTheAllowList() throws Exception {
    assertTrue(VerifyTypes.isModeled(json("\"variant\"")));
    assertTrue(VerifyTypes.isModeled(json("\"decimal(9, 2)\"")));
    assertFalse(VerifyTypes.isModeled(json("\"madeuptype\"")));
    assertTrue(VerifyTypes.isModeled(json("{\"type\":\"struct\"}")));
  }

  @Test
  void parseTypeReadsPrimitiveTypeNames() throws Exception {
    assertEquals("long", VerifyTypes.parseType(json("\"long\"")).toString());
  }

  @Test
  void gradeCoversEachDispatchState() throws Exception {
    assertEquals(
        "skip", VerifyTypes.grade(json("{\"id\":\"a\",\"input\":\"json\",\"valid\":true}"))[0]);
    assertEquals(
        "fail",
        VerifyTypes.grade(
            json(
                "{\"id\":\"b\",\"input\":\"int\",\"valid\":true,\"decoded\":{\"type\":\"long\"}}"))[
            0]);
    assertEquals(
        "fail", VerifyTypes.grade(json("{\"id\":\"c\",\"input\":\"int\",\"valid\":false}"))[0]);
    assertEquals(
        "pass",
        VerifyTypes.grade(
            json("{\"id\":\"d\",\"input\":\"int\",\"valid\":true,\"decoded\":{\"type\":\"int\"}}"))[
            0]);
    assertEquals(
        "advisory_fail",
        VerifyTypes.grade(
            json(
                "{\"id\":\"e\",\"input\":\"int\",\"valid\":true,\"normative_level\":\"should\","
                    + "\"decoded\":{\"type\":\"long\"}}"))[0]);
    assertEquals(
        "fail",
        VerifyTypes.grade(
            json(
                "{\"id\":\"f\",\"input\":\"decimal(9,2)\",\"valid\":true,"
                    + "\"decoded\":{\"type\":\"decimal\",\"precision\":9,\"scale\":2},"
                    + "\"canonical\":\"WRONG\"}"))[0]);
    assertEquals(
        "fail",
        VerifyTypes.grade(
            json(
                "{\"id\":\"g\",\"input\":\"decimal(39,0)\",\"valid\":true,"
                    + "\"decoded\":{\"type\":\"decimal\",\"precision\":39,\"scale\":0}}"))[0]);
    assertEquals(
        "pass",
        VerifyTypes.grade(json("{\"id\":\"h\",\"input\":\"notatype\",\"valid\":false}"))[0]);
  }

  // Nested arms (struct/list/map) and fixed; the geo arms are covered by the live fixtures.
  @Test
  void decodedMismatchCoversNestedArms() throws Exception {
    assertNull(
        VerifyTypes.decodedMismatch(
            Types.FixedType.ofLength(16), json("{\"type\":\"fixed\",\"length\":16}")));
    assertNotNull(
        VerifyTypes.decodedMismatch(
            Types.FixedType.ofLength(8), json("{\"type\":\"fixed\",\"length\":16}")));
    assertNull(
        VerifyTypes.decodedMismatch(
            Types.StructType.of(Types.NestedField.required(1, "a", Types.IntegerType.get())),
            json(
                "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"name\":\"a\",\"required\":true,"
                    + "\"type\":{\"type\":\"int\"}}]}")));
    assertNotNull(
        VerifyTypes.decodedMismatch(
            Types.StructType.of(Types.NestedField.required(2, "a", Types.IntegerType.get())),
            json(
                "{\"type\":\"struct\",\"fields\":[{\"id\":1,\"name\":\"a\",\"required\":true,"
                    + "\"type\":{\"type\":\"int\"}}]}")));
    assertNull(
        VerifyTypes.decodedMismatch(
            Types.ListType.ofRequired(3, Types.StringType.get()),
            json(
                "{\"type\":\"list\",\"element-id\":3,\"element-required\":true,"
                    + "\"element\":{\"type\":\"string\"}}")));
    assertNotNull(
        VerifyTypes.decodedMismatch(
            Types.ListType.ofRequired(9, Types.StringType.get()),
            json(
                "{\"type\":\"list\",\"element-id\":3,\"element-required\":true,"
                    + "\"element\":{\"type\":\"string\"}}")));
    assertNull(
        VerifyTypes.decodedMismatch(
            Types.MapType.ofRequired(4, 5, Types.StringType.get(), Types.LongType.get()),
            json(
                "{\"type\":\"map\",\"key-id\":4,\"value-id\":5,\"value-required\":true,"
                    + "\"key\":{\"type\":\"string\"},\"value\":{\"type\":\"long\"}}")));
    assertNotNull(
        VerifyTypes.decodedMismatch(
            Types.MapType.ofRequired(4, 9, Types.StringType.get(), Types.LongType.get()),
            json(
                "{\"type\":\"map\",\"key-id\":4,\"value-id\":5,\"value-required\":true,"
                    + "\"key\":{\"type\":\"string\"},\"value\":{\"type\":\"long\"}}")));
  }
}
