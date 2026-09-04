// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

// Command conformance-go is the reference runner that checks iceberg-go against
// the type-surface conformance fixtures. It reads every table-spec/**/cases.json,
// parses each `input` with iceberg-go's own type parser, and applies the case
// assertion contract:
//
//	valid=false -> the parser must return an error
//	valid=true  -> parse ok and the decoded shape == decoded; a type iceberg-go
//	               does not model is reported UNSUPPORTED (not a failure)
//
// The process exits non-zero if any case FAILs.
package main

import (
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"reflect"
	"sort"

	"github.com/apache/iceberg-go"
)

// surfaceRoots are the directories under the repo root that hold cases.
var surfaceRoots = []string{"table-spec"}

type testCase struct {
	ID        string          `json:"id"`
	Input     json.RawMessage `json:"input"`
	Valid     bool            `json:"valid"`
	Decoded   json.RawMessage `json:"decoded"`
	Canonical *string         `json:"canonical"`
	source    string
}

// caseFile is the on-disk shape of a cases.json file.
type caseFile struct {
	Cases []testCase `json:"cases"`
}

// findRepoRoot walks up from start until it finds a directory containing
// table-spec/, so the runner works from any working directory.
func findRepoRoot(start string) (string, error) {
	dir := start
	for {
		if fi, err := os.Stat(filepath.Join(dir, "table-spec")); err == nil && fi.IsDir() {
			return dir, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", fmt.Errorf("could not locate repo root (no table-spec/) above %s", start)
		}
		dir = parent
	}
}

// loadCases reads every cases.json under the surface roots.
func loadCases(root string) ([]testCase, error) {
	var cases []testCase
	for _, sr := range surfaceRoots {
		base := filepath.Join(root, sr)
		if _, err := os.Stat(base); os.IsNotExist(err) {
			continue
		}
		err := filepath.WalkDir(base, func(path string, d fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if d.IsDir() || d.Name() != "cases.json" {
				return nil
			}
			data, err := os.ReadFile(path)
			if err != nil {
				return err
			}
			var cf caseFile
			if err := json.Unmarshal(data, &cf); err != nil {
				return fmt.Errorf("%s: %w", path, err)
			}
			rel, _ := filepath.Rel(root, path)
			for _, c := range cf.Cases {
				c.source = rel
				cases = append(cases, c)
			}
			return nil
		})
		if err != nil {
			return nil, err
		}
	}
	sort.SliceStable(cases, func(i, j int) bool { return cases[i].ID < cases[j].ID })
	return cases, nil
}

// parseType routes an Appendix-C type value (a JSON string for a primitive/geo
// type, or a JSON object for a nested type) through iceberg-go's public JSON
// parse path (NestedField.UnmarshalJSON -> typeIFace.UnmarshalJSON) by embedding
// it as the `type` of a field.
func parseType(input json.RawMessage) (iceberg.Type, error) {
	fieldJSON := fmt.Sprintf(`{"id":1,"name":"f","required":true,"type":%s}`, string(input))
	var nf iceberg.NestedField
	if err := json.Unmarshal([]byte(fieldJSON), &nf); err != nil {
		return nil, err
	}
	return nf.Type, nil
}

// decodedShape maps an iceberg-go type to the fixture's language-neutral
// `decoded` shape. Numbers are float64 to match json-decoded expectations.
// supported=false means iceberg-go has no such type.
func decodedShape(t iceberg.Type) (map[string]any, bool) {
	switch v := t.(type) {
	case iceberg.BooleanType:
		return map[string]any{"type": "boolean"}, true
	case iceberg.Int32Type:
		return map[string]any{"type": "int"}, true
	case iceberg.Int64Type:
		return map[string]any{"type": "long"}, true
	case iceberg.Float32Type:
		return map[string]any{"type": "float"}, true
	case iceberg.Float64Type:
		return map[string]any{"type": "double"}, true
	case iceberg.DateType:
		return map[string]any{"type": "date"}, true
	case iceberg.TimeType:
		return map[string]any{"type": "time"}, true
	case iceberg.TimestampType:
		return map[string]any{"type": "timestamp"}, true
	case iceberg.TimestampTzType:
		return map[string]any{"type": "timestamptz"}, true
	case iceberg.TimestampNsType:
		return map[string]any{"type": "timestamp_ns"}, true
	case iceberg.TimestampTzNsType:
		return map[string]any{"type": "timestamptz_ns"}, true
	case iceberg.StringType:
		return map[string]any{"type": "string"}, true
	case iceberg.UUIDType:
		return map[string]any{"type": "uuid"}, true
	case iceberg.BinaryType:
		return map[string]any{"type": "binary"}, true
	case iceberg.UnknownType:
		return map[string]any{"type": "unknown"}, true
	case iceberg.VariantType:
		return map[string]any{"type": "variant"}, true
	case iceberg.FixedType:
		return map[string]any{"type": "fixed", "length": float64(v.Len())}, true
	case iceberg.DecimalType:
		return map[string]any{"type": "decimal", "precision": float64(v.Precision()), "scale": float64(v.Scale())}, true
	case iceberg.GeometryType:
		return map[string]any{"type": "geometry", "crs": v.CRS()}, true
	case iceberg.GeographyType:
		return map[string]any{"type": "geography", "crs": v.CRS(), "algorithm": v.Algorithm()}, true
	case *iceberg.StructType:
		fields := []any{}
		for _, f := range v.Fields() {
			child, ok := decodedShape(f.Type)
			if !ok {
				return nil, false
			}
			fields = append(fields, map[string]any{
				"id": float64(f.ID), "name": f.Name, "required": f.Required, "type": child,
			})
		}
		return map[string]any{"type": "struct", "fields": fields}, true
	case *iceberg.ListType:
		child, ok := decodedShape(v.Element)
		if !ok {
			return nil, false
		}
		return map[string]any{
			"type": "list", "element-id": float64(v.ElementID),
			"element-required": v.ElementRequired, "element": child,
		}, true
	case *iceberg.MapType:
		key, ok := decodedShape(v.KeyType)
		if !ok {
			return nil, false
		}
		val, ok := decodedShape(v.ValueType)
		if !ok {
			return nil, false
		}
		return map[string]any{
			"type": "map", "key-id": float64(v.KeyID), "key": key,
			"value-id": float64(v.ValueID), "value-required": v.ValueRequired, "value": val,
		}, true
	default:
		return nil, false
	}
}

func main() {
	cwd, err := os.Getwd()
	if err != nil {
		fmt.Fprintln(os.Stderr, "getwd:", err)
		os.Exit(2)
	}
	root := cwd
	if len(os.Args) > 1 {
		root = os.Args[1]
	}
	root, err = findRepoRoot(root)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}

	cases, err := loadCases(root)
	if err != nil {
		fmt.Fprintln(os.Stderr, "load cases:", err)
		os.Exit(2)
	}
	if len(cases) == 0 {
		fmt.Fprintln(os.Stderr, "no cases found under", root)
		os.Exit(2)
	}

	var pass, fail, unsupported int
	var failIDs, unsupportedIDs []string

	for _, c := range cases {
		typ, perr := parseType(c.Input)

		if !c.Valid {
			if perr != nil {
				fmt.Printf("%-32s PASS (rejected: %v)\n", c.ID, perr)
				pass++
			} else {
				fmt.Printf("%-32s FAIL (expected reject, parsed as %q)\n", c.ID, typ.Type())
				fail++
				failIDs = append(failIDs, c.ID)
			}
			continue
		}

		if perr != nil {
			fmt.Printf("%-32s FAIL (expected accept, parse error: %v)\n", c.ID, perr)
			fail++
			failIDs = append(failIDs, c.ID)
			continue
		}

		actual, supported := decodedShape(typ)
		if !supported {
			fmt.Printf("%-32s UNSUPPORTED (no iceberg-go mapping for %T)\n", c.ID, typ)
			unsupported++
			unsupportedIDs = append(unsupportedIDs, c.ID)
			continue
		}

		var expected map[string]any
		if err := json.Unmarshal(c.Decoded, &expected); err != nil {
			fmt.Printf("%-32s FAIL (bad 'decoded' in fixture: %v)\n", c.ID, err)
			fail++
			failIDs = append(failIDs, c.ID)
			continue
		}
		if !reflect.DeepEqual(actual, expected) {
			fmt.Printf("%-32s FAIL (decoded mismatch: expected %v, actual %v)\n", c.ID, expected, actual)
			fail++
			failIDs = append(failIDs, c.ID)
			continue
		}

		if c.Canonical != nil {
			if got := typ.Type(); got != *c.Canonical {
				fmt.Printf("%-32s FAIL (canonical mismatch: expected %q, actual %q)\n", c.ID, *c.Canonical, got)
				fail++
				failIDs = append(failIDs, c.ID)
				continue
			}
		}

		fmt.Printf("%-32s PASS\n", c.ID)
		pass++
	}

	fmt.Printf("\nTOTALS: %d cases | PASS=%d FAIL=%d UNSUPPORTED=%d SKIP=0\n",
		len(cases), pass, fail, unsupported)
	if len(failIDs) > 0 {
		fmt.Printf("FAIL ids: %v\n", failIDs)
	}
	if len(unsupportedIDs) > 0 {
		fmt.Printf("UNSUPPORTED ids: %v\n", unsupportedIDs)
	}
	if fail > 0 {
		os.Exit(1)
	}
}
