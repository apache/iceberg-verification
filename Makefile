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

SHELL := bash
PYTHON ?= python3
PRETTIER := npx --no-install prettier
FIXTURES := "table-spec/**/cases.json"
VENV := .venv
VENV_PYTHON := $(VENV)/bin/python
VENV_STAMP := $(VENV)/.installed
NODE_STAMP := node_modules/.installed

.PHONY: help install format lint validate selftest license check clean

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "}; {printf "  %-10s %s\n", $$1, $$2}'

$(VENV_STAMP): dev/requirements.txt
	$(PYTHON) -m venv $(VENV)
	$(VENV_PYTHON) -m pip install --quiet -r dev/requirements.txt
	@touch $(VENV_STAMP)

$(NODE_STAMP): package.json package-lock.json
	npm ci --no-audit --no-fund
	@touch $(NODE_STAMP)

install: $(VENV_STAMP) $(NODE_STAMP) ## Install the Python and Node tooling dependencies

format: $(NODE_STAMP) ## Format the JSON fixtures in place
	$(PRETTIER) --log-level warn --write $(FIXTURES)

lint: $(NODE_STAMP) ## Fail if any JSON fixture is not formatted
	$(PRETTIER) --check $(FIXTURES)

validate: $(VENV_STAMP) ## Validate every cases.json against the JSON Schemas
	$(VENV_PYTHON) dev/validate-fixtures.py

# Each fixture is written into a scratch table-spec tree. The well-formed one must
# be accepted, so a validator that cannot run at all fails here; every malformed
# one must be rejected by the validator itself (exit 1 and a FAILED report), not
# by a crash.
selftest: $(VENV_STAMP) ## Check the validator accepts a good fixture and rejects malformed ones
	@set -e; \
	run_validator() { \
	  d="$$(mktemp -d)"; \
	  mkdir -p "$$d/table-spec/types/x"; \
	  printf '%s' "$$1" > "$$d/table-spec/types/x/cases.json"; \
	  rc=0; out="$$($(VENV_PYTHON) dev/validate-fixtures.py "$$d" 2>&1)" || rc=$$?; \
	  rm -rf "$$d"; \
	}; \
	assert_accept() { \
	  run_validator "$$2"; \
	  if [ "$$rc" -ne 0 ]; then \
	    echo "FAILED: validator rejected a well-formed fixture ($$1)"; echo "$$out"; exit 1; \
	  fi; \
	  echo "ok: accepted ($$1)"; \
	}; \
	assert_reject() { \
	  run_validator "$$2"; \
	  if [ "$$rc" -ne 1 ] || ! grep -q '^FAILED: ' <<< "$$out"; then \
	    echo "FAILED: validator did not reject a malformed fixture ($$1), exit code $$rc"; echo "$$out"; exit 1; \
	  fi; \
	  echo "ok: rejected ($$1)"; \
	}; \
	assert_accept "well-formed case" '{"cases":[{"id":"a","valid":true,"input":"int","decoded":{"type":"int"},"clause":"c","spec_ref":"s"}]}'; \
	assert_reject "valid:true with no decoded" '{"cases":[{"id":"a","valid":true,"input":"int","clause":"c","spec_ref":"s"}]}'; \
	assert_reject "valid:false carrying decoded" '{"cases":[{"id":"a","valid":false,"input":"bad","decoded":{"type":"int"},"clause":"c","spec_ref":"s"}]}'; \
	assert_reject "unknown property" '{"cases":[{"id":"a","valid":true,"input":"int","decoded":{"type":"int"},"clause":"c","spec_ref":"s","spec-ref":"typo"}]}'; \
	assert_reject "duplicate id in a surface" '{"cases":[{"id":"a","valid":true,"input":"int","decoded":{"type":"int"},"clause":"c","spec_ref":"s"},{"id":"a","valid":true,"input":"long","decoded":{"type":"long"},"clause":"c","spec_ref":"s"}]}'; \
	assert_reject "decoded decimal typo (precison)" '{"cases":[{"id":"a","valid":true,"input":"decimal(9,2)","decoded":{"type":"decimal","precison":9,"scale":2},"clause":"c","spec_ref":"s"}]}'; \
	assert_reject "decoded fixed carrying len not length" '{"cases":[{"id":"a","valid":true,"input":"fixed[16]","decoded":{"type":"fixed","len":16},"clause":"c","spec_ref":"s"}]}'; \
	assert_reject "decoded unknown type name" '{"cases":[{"id":"a","valid":true,"input":"x","decoded":{"type":"notatype"},"clause":"c","spec_ref":"s"}]}'; \
	assert_reject "decoded simple type with extra key" '{"cases":[{"id":"a","valid":true,"input":"int","decoded":{"type":"int","precision":9},"clause":"c","spec_ref":"s"}]}'; \
	run_partition_transform_validator() { \
	  d="$$(mktemp -d)"; \
	  mkdir -p "$$d/table-spec/partition-transforms/x"; \
	  printf '%s' "$$1" > "$$d/table-spec/partition-transforms/x/cases.json"; \
	  rc=0; out="$$($(VENV_PYTHON) dev/validate-fixtures.py "$$d" 2>&1)" || rc=$$?; \
	  rm -rf "$$d"; \
	}; \
	assert_partition_transform_accept() { \
	  run_partition_transform_validator "$$2"; \
	  if [ "$$rc" -ne 0 ]; then \
	    echo "FAILED: validator rejected a well-formed fixture ($$1)"; echo "$$out"; exit 1; \
	  fi; \
	  echo "ok: accepted ($$1)"; \
	}; \
	assert_partition_transform_reject() { \
	  run_partition_transform_validator "$$2"; \
	  if [ "$$rc" -ne 1 ] || ! grep -q '^FAILED: ' <<< "$$out"; then \
	    echo "FAILED: validator did not reject a malformed fixture ($$1), exit code $$rc"; echo "$$out"; exit 1; \
	  fi; \
	  echo "ok: rejected ($$1)"; \
	}; \
	assert_partition_transform_accept "well-formed partition transform" '{"cases":[{"id":"a","valid":true,"input":{"transform":"bucket[100]","source_type":"int","values":[34,null]},"decoded":{"result_types":["int"],"values":[79,null]},"clause":"c","spec_ref":"s"}]}'; \
	assert_partition_transform_reject "unknown partition transform" '{"cases":[{"id":"a","valid":true,"input":{"transform":"mystery","source_type":"int","values":[1,null]},"decoded":{"result_types":["int"],"values":[1,null]},"clause":"c","spec_ref":"s"}]}'; \
	assert_partition_transform_reject "unsupported transform source pair" '{"cases":[{"id":"a","valid":true,"input":{"transform":"bucket[10]","source_type":"boolean","values":[true,null]},"decoded":{"result_types":["int"],"values":[1,null]},"clause":"c","spec_ref":"s"}]}'; \
	assert_partition_transform_reject "unequal partition transform value lengths" '{"cases":[{"id":"a","valid":true,"input":{"transform":"identity","source_type":"int","values":[1,null]},"decoded":{"result_types":["int"],"values":[1]},"clause":"c","spec_ref":"s"}]}'; \
	assert_partition_transform_reject "partition transform without null input" '{"cases":[{"id":"a","valid":true,"input":{"transform":"identity","source_type":"int","values":[1]},"decoded":{"result_types":["int"],"values":[1]},"clause":"c","spec_ref":"s"}]}'; \
	assert_partition_transform_reject "partition transform null propagation mismatch" '{"cases":[{"id":"a","valid":true,"input":{"transform":"bucket[10]","source_type":"int","values":[null]},"decoded":{"result_types":["int"],"values":[0]},"clause":"c","spec_ref":"s"}]}'; \
	assert_partition_transform_reject "wrong bucket result type" '{"cases":[{"id":"a","valid":true,"input":{"transform":"bucket[10]","source_type":"long","values":["1",null]},"decoded":{"result_types":["long"],"values":[1,null]},"clause":"c","spec_ref":"s"}]}'; \
	assert_partition_transform_reject "wrong day result type" '{"cases":[{"id":"a","valid":true,"input":{"transform":"day","source_type":"date","values":["1970-01-01",null]},"decoded":{"result_types":["int"],"values":["1970-01-01",null]},"clause":"c","spec_ref":"s"}]}'; \
	assert_partition_transform_reject "identity value mismatch" '{"cases":[{"id":"a","valid":true,"input":{"transform":"identity","source_type":"string","values":["a",null]},"decoded":{"result_types":["string"],"values":["b",null]},"clause":"c","spec_ref":"s"}]}'; \
	assert_partition_transform_reject "void missing an allowed result type" '{"cases":[{"id":"a","valid":true,"input":{"transform":"void","source_type":"string","values":["a",null]},"decoded":{"result_types":["int"],"values":[null,null]},"clause":"c","spec_ref":"s"}]}'; \
	assert_partition_transform_reject "partition transform misspelled field" '{"cases":[{"id":"a","valid":true,"input":{"transform":"identity","source-type":"int","values":[1,null]},"decoded":{"result_types":["int"],"values":[1,null]},"clause":"c","spec_ref":"s"}]}'

license: ## Check ASF license headers with Apache RAT
	dev/check-license

check: lint validate selftest license ## Run all checks

clean: ## Remove .venv and node_modules
	rm -rf $(VENV) node_modules
