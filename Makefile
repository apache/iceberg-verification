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
PRETTIER := npx --yes prettier@3.9.9
FIXTURES := "table-spec/**/*.json"
VENV := .venv
VENV_PYTHON := $(VENV)/bin/python
VENV_STAMP := $(VENV)/.installed

.PHONY: help install format lint validate test license check clean

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "}; {printf "  %-10s %s\n", $$1, $$2}'

$(VENV_STAMP): dev/requirements.txt
	$(PYTHON) -m venv $(VENV)
	$(VENV_PYTHON) -m pip install --quiet -r dev/requirements.txt
	@touch $(VENV_STAMP)

install: $(VENV_STAMP) ## Create .venv with the dev/ tooling dependencies

format: ## Format the JSON fixtures in place
	$(PRETTIER) --log-level warn --write $(FIXTURES)

lint: ## Fail if any JSON fixture is not formatted
	$(PRETTIER) --check $(FIXTURES)

validate: $(VENV_STAMP) ## Validate every cases.json against the JSON Schemas
	$(VENV_PYTHON) dev/validate-fixtures.py

# Each malformed fixture is written into a scratch table-spec tree; the validator
# must reject every one.
test: $(VENV_STAMP) ## Check the validator rejects malformed fixtures
	@set -e; \
	assert_reject() { \
	  d="$$(mktemp -d)"; \
	  mkdir -p "$$d/table-spec/types/x"; \
	  printf '%s' "$$2" > "$$d/table-spec/types/x/cases.json"; \
	  if $(VENV_PYTHON) dev/validate-fixtures.py "$$d" >/dev/null 2>&1; then \
	    echo "FAILED: validator accepted a malformed fixture ($$1)"; rm -rf "$$d"; exit 1; \
	  fi; \
	  echo "ok: rejected ($$1)"; \
	  rm -rf "$$d"; \
	}; \
	assert_reject "valid:true with no decoded" '{"cases":[{"id":"a","valid":true,"input":"int","clause":"c","spec_ref":"s"}]}'; \
	assert_reject "valid:false carrying decoded" '{"cases":[{"id":"a","valid":false,"input":"bad","decoded":{"type":"int"},"clause":"c","spec_ref":"s"}]}'; \
	assert_reject "unknown property" '{"cases":[{"id":"a","valid":true,"input":"int","decoded":{"type":"int"},"clause":"c","spec_ref":"s","spec-ref":"typo"}]}'; \
	assert_reject "duplicate id in a surface" '{"cases":[{"id":"a","valid":true,"input":"int","decoded":{"type":"int"},"clause":"c","spec_ref":"s"},{"id":"a","valid":true,"input":"long","decoded":{"type":"long"},"clause":"c","spec_ref":"s"}]}'; \
	assert_reject "decoded decimal typo (precison)" '{"cases":[{"id":"a","valid":true,"input":"decimal(9,2)","decoded":{"type":"decimal","precison":9,"scale":2},"clause":"c","spec_ref":"s"}]}'; \
	assert_reject "decoded fixed carrying len not length" '{"cases":[{"id":"a","valid":true,"input":"fixed[16]","decoded":{"type":"fixed","len":16},"clause":"c","spec_ref":"s"}]}'; \
	assert_reject "decoded unknown type name" '{"cases":[{"id":"a","valid":true,"input":"x","decoded":{"type":"notatype"},"clause":"c","spec_ref":"s"}]}'; \
	assert_reject "decoded simple type with extra key" '{"cases":[{"id":"a","valid":true,"input":"int","decoded":{"type":"int","precision":9},"clause":"c","spec_ref":"s"}]}'

license: ## Check ASF license headers with Apache RAT
	dev/check-license

check: lint validate test license ## Run all checks

clean: ## Remove .venv
	rm -rf $(VENV)
