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
# Render a runner's exit code and output to the CI step summary and annotations.
# Usage: ci-report.sh <impl> <runner-output-file> <lane> <exit-code> [<resolved-version>]
set -uo pipefail

impl="${1:?usage: ci-report.sh <impl> <out> <lane> <exit-code> [<resolved>]}"
out="${2:?usage: ci-report.sh <impl> <out> <lane> <exit-code> [<resolved>]}"
lane="${3:-release}"
# Missing code = the run step was skipped; default to ERROR, never PASS.
code="${4:-2}"
resolved="${5:-unknown}"

totals="$(grep -m1 '^TOTALS:' "$out" 2>/dev/null || echo 'TOTALS: (runner produced no summary - see the step log)')"
fails="$(grep -m1 '^FAIL ids:' "$out" 2>/dev/null || true)"
advisory="$(grep -m1 '^ADVISORY_FAIL ids:' "$out" 2>/dev/null || true)"
skip="$(grep -m1 '^SKIP ids:' "$out" 2>/dev/null || true)"

case "$code" in
  0) verdict="PASS" ;;
  1) verdict="FAIL" ;;
  *) verdict="ERROR" ;;
esac

if [ "$verdict" = "FAIL" ]; then level="error"; else level="warning"; fi

# Step summary (no-op when not in CI).
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### ${impl} - types conformance (${lane} lane)"
    echo
    echo "- result: **${verdict}**"
    echo "- tested against: \`${resolved}\`"
    echo
    echo '```'
    echo "${totals}"
    [ -n "${fails}" ] && echo "${fails}"
    [ -n "${advisory}" ] && echo "${advisory}"
    [ -n "${skip}" ] && echo "${skip}"
    echo '```'
    echo
  } >> "${GITHUB_STEP_SUMMARY}"
fi

# Inline annotations.
case "$verdict" in
  FAIL)  echo "::${level} title=${impl} ${lane} divergence::${fails:-conformance FAIL} (tested ${resolved})" ;;
  ERROR) echo "::warning title=${impl} ${lane} runner error::runner exited ${code} before a verdict - see the step log" ;;
esac
[ -n "${advisory}" ] && echo "::notice title=${impl} advisory_fail (unmet SHOULD, non-blocking)::${advisory}"
[ -n "${skip}" ] && echo "::notice title=${impl} skip (skip-list candidate)::${skip}"

echo "RESULT: ${impl} [${lane}] ${verdict} (${resolved}) - ${totals}"
