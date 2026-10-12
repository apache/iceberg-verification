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
# Fail if a committed runner dependency file carries a local source override.
set -uo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
fail=0

check() { # <file> <extended-regex> <message>
  hits="$(grep -nE "$2" "$root/$1" 2>/dev/null)"
  if [ -n "$hits" ]; then
    echo "FAIL: $1 - $3"
    printf '%s\n' "$hits" | sed 's/^/    /'
    fail=1
  fi
}

check runners/java/build.gradle    'mavenLocal|includeBuild'             "committed local build reference - leaked"
check runners/java/settings.gradle  'includeBuild'                        "committed composite-build reference - leaked"

if [ "$fail" -eq 0 ]; then
  echo "runner deps are release-clean"
else
  exit 1
fi
