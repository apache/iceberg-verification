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
# Render a runner's result into per-run CI visibility: a Step Summary table,
# inline annotations, and a one-line verdict. Reporting only - it never changes
# the job status.
#
# Usage: ci-report.sh <impl-label> <runner-output-file> <lane> <exit-code> [<resolved-version>]
#   lane           a label for the run shown in the report (release, main, ...)
#   exit-code      the runner's exit: 0 PASS, 1 FAIL (divergence), 2 ERROR (setup)
#   resolved-ver   what was actually tested (jar version, commit SHA, ...); provenance
set -uo pipefail

impl="${1:?usage: ci-report.sh <impl> <out> <lane> <exit-code> [<resolved>]}"
out="${2:?usage: ci-report.sh <impl> <out> <lane> <exit-code> [<resolved>]}"
lane="${3:-release}"
# An empty/missing code means the run step was skipped (a setup step before it
# failed), so default to ERROR - never PASS.
code="${4:-2}"
resolved="${5:-unknown}"

totals="$(grep -m1 '^TOTALS:' "$out" 2>/dev/null || echo 'TOTALS: (runner produced no summary - see the step log)')"
fails="$(grep -m1 '^FAIL ids:' "$out" 2>/dev/null || true)"
unsup="$(grep -m1 '^UNSUPPORTED ids:' "$out" 2>/dev/null || true)"

case "$code" in
  0) verdict="PASS" ;;
  1) verdict="FAIL" ;;
  *) verdict="ERROR" ;;   # 2 = runner setup/parse error, or an unexpected code
esac

# A conformance FAIL is a real divergence (error, but still non-blocking via the
# job's continue-on-error); a runner ERROR is a setup failure, not a verdict.
if [ "$verdict" = "FAIL" ]; then level="error"; else level="warning"; fi

# Step Summary (rendered on the run page). No-op locally if the var is unset.
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
    [ -n "${unsup}" ] && echo "${unsup}"
    echo '```'
    echo
  } >> "${GITHUB_STEP_SUMMARY}"
fi

# Inline annotations (surface on the PR/commit without failing the build).
case "$verdict" in
  FAIL)  echo "::${level} title=${impl} ${lane} divergence::${fails:-conformance FAIL} (tested ${resolved})" ;;
  ERROR) echo "::warning title=${impl} ${lane} runner error::runner exited ${code} before a verdict - see the step log" ;;
esac
[ -n "${unsup}" ] && echo "::notice title=${impl} unsupported (skip-list candidate)::${unsup}"

# Always echo the one-line verdict to the step log.
echo "RESULT: ${impl} [${lane}] ${verdict} (${resolved}) - ${totals}"
