SHELL := /bin/bash

ANDROID := android
VENV := tools/.venv
PY := $(VENV)/bin/python
PIP := $(VENV)/bin/pip
APK := $(ANDROID)/app/build/outputs/apk/debug/app-debug.apk
MERGED_MANIFEST := $(ANDROID)/app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml

# Prefer an explicitly exported JAVA_HOME; on macOS fall back to java_home 25.
ifeq ($(JAVA_HOME),)
JAVA_HOME := $(shell /usr/libexec/java_home -v 25 2>/dev/null)
endif
export JAVA_HOME

.PHONY: setup test build lint verify spec crosscheck clean screenshots

setup:
	python3 -m venv $(VENV)
	$(PIP) install -q -r tools/requirements.txt

# :core:check runs unit tests + the 90% line-coverage gate.
test:
	cd $(ANDROID) && ./gradlew :core:check :app:testDebugUnitTest

build:
	cd $(ANDROID) && ./gradlew :app:assembleDebug

lint:
	cd $(ANDROID) && ./gradlew :app:lintDebug

spec:
	$(PY) tools/reference_totp.py --selftest
	$(PY) tools/reference_backup.py --selftest

# The Kotlin test writes build/crosscheck/kotlin-backup.2fab, then Python
# decrypts it with the documented password and compares the JSON.
crosscheck: test
	$(PY) tools/crosscheck.py --spec spec --backup-dir $(ANDROID)/core/build/crosscheck

verify: crosscheck build lint spec
	$(PY) tools/check_no_internet.py --manifest $(MERGED_MANIFEST)
	$(PY) tools/check_design_tokens.py --root $(ANDROID)/app/src/main/kotlin
	@echo "verify: OK"

screenshots:
	cd $(ANDROID) && ./gradlew :app:recordRoborazziDebug

clean:
	cd $(ANDROID) && ./gradlew clean
	rm -rf tools/.venv
