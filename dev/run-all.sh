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
# Run all four runners against apache/main, saving each implementation's output
# to <outdir>/<impl>.txt (default /tmp/conformance) while also streaming it live.
# The files are named so `dev/build-status-matrix.py <outdir>` can aggregate them.
# Exit non-zero if any runner reported a conformance FAIL.
#
# Usage: dev/run-all.sh [<outdir>]
set -uo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
outdir="${1:-/tmp/conformance}"
mkdir -p "$outdir"

rc=0
for l in go rust python java; do
  case "$l" in
    go)     impl=iceberg-go   ;;
    rust)   impl=iceberg-rust ;;
    python) impl=pyiceberg    ;;
    java)   impl=iceberg-java ;;
  esac
  f="$outdir/$impl.txt"
  echo ">>> $l -> $f"
  "$root/dev/run-local.sh" "$l" 2>&1 | tee "$f"
  # run-local exits 1 on a conformance FAIL, 2 on a runner/setup ERROR.
  [ "${PIPESTATUS[0]}" -eq 1 ] && rc=1
done

echo
echo "outputs written to $outdir:"
ls -1 "$outdir"
echo "aggregate with: python3 dev/build-status-matrix.py $outdir"
exit "$rc"
