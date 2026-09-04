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

//! Reference runner that checks iceberg-rust against the type-surface conformance
//! fixtures. It reads every table-spec/**/cases.json (a JSON object with a top
//! level `cases` array), deserializes each `input` type string into iceberg-rust's
//! own `Type` via serde_json, and applies the surface's assertion contract:
//!
//!   valid=false             -> deserialization must return an error (PASS); Ok is FAIL
//!   valid=true              -> deserialization must succeed and the decoded shape
//!                              must equal `decoded`
//!
//! UNSUPPORTED is decided post-parse: if a valid case fails to deserialize AND the
//! expected `decoded` type is one iceberg-rust's `Type` enum cannot represent
//! (unknown / geometry / geography), the case is reported UNSUPPORTED, not FAIL.
//! The process exits 0 (no FAIL), 1 (any FAIL), or 2 (setup / fixture-load error).

use std::path::{Path, PathBuf};

use iceberg::spec::{PrimitiveType, Type};
use serde_json::{json, Value};

// The directory under the repo root that holds the fixture cases.
const SURFACE_ROOT: &str = "table-spec";

// Type names iceberg-rust's `Type` enum does not model. When a valid case fails
// to parse and its expected `decoded.type` is one of these, it is reported
// UNSUPPORTED (a skip-list candidate), not FAIL. iceberg-rust models struct /
// list / map / variant, but no unknown / geometry / geography.
fn is_unsupported_type(name: &str) -> bool {
    matches!(name, "unknown" | "geometry" | "geography")
}

// find_repo_root walks up from `start` until it finds a directory containing
// table-spec/, so the runner works from any working directory.
fn find_repo_root(start: &Path) -> Option<PathBuf> {
    let mut dir = start.to_path_buf();
    loop {
        if dir.join(SURFACE_ROOT).is_dir() {
            return Some(dir);
        }
        if !dir.pop() {
            return None;
        }
    }
}

// collect_case_files recursively finds every cases.json under `dir`.
fn collect_case_files(dir: &Path, out: &mut Vec<PathBuf>) -> std::io::Result<()> {
    for entry in std::fs::read_dir(dir)? {
        let path = entry?.path();
        if path.is_dir() {
            collect_case_files(&path, out)?;
        } else if path.file_name().and_then(|n| n.to_str()) == Some("cases.json") {
            out.push(path);
        }
    }
    Ok(())
}

// Map a decoded iceberg-rust Type to the surface's language-neutral `decoded` shape.
fn decoded_to_shape(ty: &Type) -> Value {
    match ty {
        Type::Primitive(p) => match p {
            PrimitiveType::Boolean => json!({"type": "boolean"}),
            PrimitiveType::Int => json!({"type": "int"}),
            PrimitiveType::Long => json!({"type": "long"}),
            PrimitiveType::Float => json!({"type": "float"}),
            PrimitiveType::Double => json!({"type": "double"}),
            PrimitiveType::Decimal { precision, scale } => {
                json!({"type": "decimal", "precision": precision, "scale": scale})
            }
            PrimitiveType::Date => json!({"type": "date"}),
            PrimitiveType::Time => json!({"type": "time"}),
            PrimitiveType::Timestamp => json!({"type": "timestamp"}),
            PrimitiveType::Timestamptz => json!({"type": "timestamptz"}),
            PrimitiveType::TimestampNs => json!({"type": "timestamp_ns"}),
            PrimitiveType::TimestamptzNs => json!({"type": "timestamptz_ns"}),
            PrimitiveType::String => json!({"type": "string"}),
            PrimitiveType::Uuid => json!({"type": "uuid"}),
            PrimitiveType::Fixed(l) => json!({"type": "fixed", "length": l}),
            PrimitiveType::Binary => json!({"type": "binary"}),
        },
        Type::Struct(s) => {
            let fields: Vec<Value> = s
                .fields()
                .iter()
                .map(|f| {
                    json!({
                        "id": f.id,
                        "name": f.name,
                        "required": f.required,
                        "type": decoded_to_shape(f.field_type.as_ref()),
                    })
                })
                .collect();
            json!({"type": "struct", "fields": fields})
        }
        Type::List(l) => json!({
            "type": "list",
            "element-id": l.element_field.id,
            "element-required": l.element_field.required,
            "element": decoded_to_shape(l.element_field.field_type.as_ref()),
        }),
        Type::Map(m) => json!({
            "type": "map",
            "key-id": m.key_field.id,
            "key": decoded_to_shape(m.key_field.field_type.as_ref()),
            "value-id": m.value_field.id,
            "value-required": m.value_field.required,
            "value": decoded_to_shape(m.value_field.field_type.as_ref()),
        }),
        Type::Variant(_) => json!({"type": "variant"}),
    }
}

// serialized_wire_form re-serializes a parsed Type back to its language-neutral
// wire string via serde (e.g. "fixed[16]"). iceberg-rust serializes a Type to a
// bare JSON string for primitives, so we take serde_json::to_value and read it as
// a str; Display is NOT used (it emits the wrong "fixed(16)" form).
fn serialized_wire_form(ty: &Type) -> Option<String> {
    serde_json::to_value(ty)
        .ok()?
        .as_str()
        .map(|s| s.to_string())
}

// load_cases reads every cases.json under the surface root, flattening the
// top level `cases` array of each file and tagging cases with their source
// path for diagnostics. Returns Err on any fixture-load / parse-of-file error.
fn load_cases(root: &Path) -> Result<Vec<(String, Value)>, String> {
    let mut files = Vec::new();
    collect_case_files(&root.join(SURFACE_ROOT), &mut files)
        .map_err(|e| format!("walk {SURFACE_ROOT}: {e}"))?;
    files.sort();

    let mut cases: Vec<(String, Value)> = Vec::new();
    for file in &files {
        let rel = file
            .strip_prefix(root)
            .unwrap_or(file)
            .display()
            .to_string();
        let raw = std::fs::read_to_string(file).map_err(|e| format!("{rel}: read: {e}"))?;
        let doc: Value = serde_json::from_str(&raw).map_err(|e| format!("{rel}: parse: {e}"))?;
        let arr = doc
            .get("cases")
            .and_then(|c| c.as_array())
            .ok_or_else(|| format!("{rel}: missing top-level \"cases\" array"))?;
        for case in arr {
            cases.push((rel.clone(), case.clone()));
        }
    }
    Ok(cases)
}

fn run() -> Result<i32, String> {
    let cwd = std::env::current_dir().map_err(|e| format!("getcwd: {e}"))?;
    let start = std::env::args().nth(1).map(PathBuf::from).unwrap_or(cwd);
    let root = find_repo_root(&start).ok_or_else(|| {
        format!("could not locate repo root (no {SURFACE_ROOT}/) above {start:?}")
    })?;

    let mut cases = load_cases(&root)?;
    cases.sort_by(|a, b| a.1["id"].as_str().cmp(&b.1["id"].as_str()));

    if cases.is_empty() {
        return Err(format!("no cases found under {}", root.display()));
    }

    let mut pass = 0usize;
    let mut fail = 0usize;
    let mut unsupported = 0usize;
    let mut fail_ids: Vec<String> = Vec::new();
    let mut unsupported_ids: Vec<String> = Vec::new();

    for (src, case) in &cases {
        let id = case["id"]
            .as_str()
            .ok_or_else(|| format!("{src}: case missing string \"id\""))?
            .to_string();
        let valid = case["valid"]
            .as_bool()
            .ok_or_else(|| format!("{src}: case {id}: missing bool \"valid\""))?;
        let input = case["input"].clone();

        let parsed: Result<Type, _> = serde_json::from_value::<Type>(input);

        if !valid {
            match parsed {
                Err(e) => {
                    println!("{id:<32} PASS (rejected: {e})");
                    pass += 1;
                }
                Ok(ty) => {
                    println!("{id:<32} FAIL (expected reject, parsed as {ty:?})");
                    fail += 1;
                    fail_ids.push(id);
                }
            }
            continue;
        }

        // valid == true: parse must succeed and the decoded shape must match.
        let expected = &case["decoded"];
        match parsed {
            Ok(ty) => {
                let actual = decoded_to_shape(&ty);
                if &actual == expected {
                    // Optional canonical (serialize) check: when the case carries a
                    // "canonical" wire string, re-serializing the parsed Type must
                    // reproduce it. Absent the field, decode-only PASS as before.
                    if let Some(expected_canon) = case["canonical"].as_str() {
                        let actual_canon = serialized_wire_form(&ty);
                        if actual_canon.as_deref() == Some(expected_canon) {
                            println!("{id:<32} PASS");
                            pass += 1;
                        } else {
                            println!(
                                "{id:<32} FAIL (canonical mismatch: expected {expected_canon:?}, actual {actual_canon:?})"
                            );
                            fail += 1;
                            fail_ids.push(id);
                        }
                    } else {
                        println!("{id:<32} PASS");
                        pass += 1;
                    }
                } else {
                    println!(
                        "{id:<32} FAIL (decoded mismatch: expected {expected}, actual {actual})"
                    );
                    fail += 1;
                    fail_ids.push(id);
                }
            }
            Err(e) => {
                // A parse failure on a valid case is UNSUPPORTED only when the
                // expected type is one iceberg-rust's Type enum cannot model.
                let name = expected["type"].as_str().unwrap_or("");
                if is_unsupported_type(name) {
                    println!("{id:<32} UNSUPPORTED (no iceberg-rust Type for {name:?})");
                    unsupported += 1;
                    unsupported_ids.push(id);
                } else {
                    println!("{id:<32} FAIL (expected accept, parse error: {e})");
                    fail += 1;
                    fail_ids.push(id);
                }
            }
        }
    }

    println!(
        "\nTOTALS: {} cases | PASS={pass} FAIL={fail} UNSUPPORTED={unsupported} SKIP=0",
        cases.len()
    );
    if !fail_ids.is_empty() {
        println!("FAIL ids: {fail_ids:?}");
    }
    if !unsupported_ids.is_empty() {
        println!("UNSUPPORTED ids: {unsupported_ids:?}");
    }

    Ok(if fail > 0 { 1 } else { 0 })
}

fn main() {
    match run() {
        Ok(code) => std::process::exit(code),
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(2);
        }
    }
}
