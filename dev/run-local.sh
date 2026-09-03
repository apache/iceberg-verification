#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# Run one conformance runner locally against the latest apache/main of the
# implementation (CI tests the latest release). Clones apache/main under
# runners/<lang>/, wires the runner against that source the way a CI job would,
# runs it, and renders the report. Exits with the runner's own code
# (0 PASS / 1 FAIL / 2 ERROR). Progress is logged to stderr.
#
# Usage: dev/run-local.sh <go|rust|python|java>
#   ICEBERG_<GO|RUST|PYTHON|JAVA>_DIR=<path> uses an existing checkout instead of
#   cloning; PYTHON=<path> forces a Python interpreter.
set -uo pipefail

lang="${1:?usage: run-local.sh <go|rust|python|java>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
out="$(mktemp)"
GITHUB_STEP_SUMMARY="$(mktemp)"; export GITHUB_STEP_SUMMARY; : > "$GITHUB_STEP_SUMMARY"

log() { printf '>> %s\n' "$*" >&2; }

# Restore dep-file backups a run leaves, even on Ctrl-C, so the tree stays clean.
restore_backups() {
  local d f
  for d in runners/go runners/rust; do
    for f in go.mod go.sum Cargo.toml Cargo.lock; do
      [ -f "$root/$d/$f.bak" ] && mv "$root/$d/$f.bak" "$root/$d/$f" && log "restored $d/$f"
    done
  done
}
trap restore_backups EXIT
trap 'exit 130' INT TERM

# First Python >= 3.10 (pyiceberg needs it); honors $PYTHON.
pick_python() {
  local c
  for c in "${PYTHON:-}" python3.12 python3.11 python3.10 python3; do
    { [ -n "$c" ] && command -v "$c" >/dev/null 2>&1; } || continue
    "$c" -c 'import sys; raise SystemExit(0 if sys.version_info[:2] >= (3, 10) else 1)' 2>/dev/null \
      && { echo "$c"; return 0; }
  done
  return 1
}

# Echo an absolute path to apache/main HEAD (clone or fetch), or the override dir.
prepare() { # <dir> <url> <override>
  local dir="$1" url="$2" override="$3"
  if [ -n "$override" ]; then
    [ -d "$override" ] || { log "override dir not found: $override"; return 1; }
    ( cd "$override" && pwd ); return 0
  fi
  if [ -d "$dir/.git" ]; then
    log "updating $dir to apache/main HEAD"
    git -C "$dir" fetch --quiet origin main >&2 && git -C "$dir" checkout --quiet --detach FETCH_HEAD >&2 || return 1
  else
    log "cloning apache/main into $dir (first run; can take a minute)"
    rm -rf "$dir"; git clone --quiet --depth 1 "$url" "$dir" >&2 || return 1
  fi
  ( cd "$dir" && pwd )
}

headsha() { git -C "$1" rev-parse HEAD 2>/dev/null || echo unknown; }

log "target: $lang runner against apache/main"

case "$lang" in
  go)
    src="$(prepare runners/go/iceberg-go https://github.com/apache/iceberg-go "${ICEBERG_GO_DIR:-}")"
    if [ -z "$src" ]; then
      code=2
    else
      log "wiring go.mod -replace -> $src and building"
      ( cd runners/go
        cp go.mod go.mod.bak; cp go.sum go.sum.bak
        go mod edit -replace "github.com/apache/iceberg-go=$src"
        go mod tidy && go build -o /tmp/conformance-go . ) >&2
      if [ $? -eq 0 ]; then log "running"; /tmp/conformance-go > "$out" 2>&1; code=$?; else log "build failed"; code=2; fi
    fi
    impl="iceberg-go"; resolved="iceberg-go@$(headsha "${src:-/nonexistent}")"
    ;;
  rust)
    src="$(prepare runners/rust/iceberg-rust https://github.com/apache/iceberg-rust "${ICEBERG_RUST_DIR:-}")"
    if [ -z "$src" ]; then
      code=2
    else
      log "wiring Cargo [patch.crates-io] -> $src and building (cargo, slow first time)"
      ( cd runners/rust
        cp Cargo.toml Cargo.toml.bak; [ -f Cargo.lock ] && cp Cargo.lock Cargo.lock.bak
        printf '\n[patch.crates-io]\niceberg = { path = "%s/crates/iceberg" }\n' "$src" >> Cargo.toml
        cargo build ) >&2
      if [ $? -eq 0 ]; then log "running"; runners/rust/target/debug/conformance-rust > "$out" 2>&1; code=$?; else log "build failed"; code=2; fi
    fi
    impl="iceberg-rust"; resolved="iceberg-rust@$(headsha "${src:-/nonexistent}")"
    ;;
  python)
    if ! py="$(pick_python)"; then
      log "no Python >=3.10 found - pyiceberg main needs it. Install one or set PYTHON=/path/to/python3.12"
      code=2; impl="pyiceberg"; resolved="pyiceberg@unavailable"
    else
      log "using $py ($("$py" -V 2>&1))"
      src="$(prepare runners/python/iceberg-python https://github.com/apache/iceberg-python "${ICEBERG_PYTHON_DIR:-}")"
      if [ -z "$src" ]; then
        code=2
      else
        # Isolated venv so the install can't hit PEP 668 externally-managed errors.
        venv="$(mktemp -d)/venv"
        if command -v uv >/dev/null 2>&1; then
          log "creating venv (uv) and installing pyiceberg from source"
          uv venv --python "$py" "$venv" >&2 && uv pip install --python "$venv/bin/python" "$src" >&2
        else
          log "creating venv (python -m venv) and installing pyiceberg from source"
          "$py" -m venv "$venv" >&2 && "$venv/bin/python" -m pip install --quiet "$src" >&2
        fi
        if [ $? -eq 0 ]; then
          log "running"; ( cd runners/python && "$venv/bin/python" runner.py ) > "$out" 2>&1; code=$?
        else
          log "install failed"; code=2
        fi
      fi
      impl="pyiceberg"; resolved="pyiceberg@$(headsha "${src:-/nonexistent}")"
    fi
    ;;
  java)
    src="$(prepare runners/java/iceberg-src https://github.com/apache/iceberg "${ICEBERG_JAVA_DIR:-}")"
    if [ -z "$src" ]; then
      code=2
    else
      log "gradle installDist --include-build $src (heavy first time)"
      if ( cd runners/java && ./gradlew installDist --quiet --include-build "$src" ) >&2; then
        log "running"; runners/java/build/install/conformance-java/bin/conformance-java > "$out" 2>&1; code=$?
      else
        log "build failed"; code=2
      fi
    fi
    impl="iceberg-java"; resolved="iceberg-api@$(headsha "${src:-/nonexistent}")"
    ;;
  *)
    echo "unknown lang: $lang (go|rust|python|java)"; exit 2
    ;;
esac

log "done: $impl code=$code"
cat "$out"
bash "$root/dev/ci-report.sh" "$impl" "$out" main "$code" "$resolved"
echo
echo "----- rendered step summary ($GITHUB_STEP_SUMMARY) -----"
cat "$GITHUB_STEP_SUMMARY"
exit "$code"
