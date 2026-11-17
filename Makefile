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

.PHONY: setup test build lint verify spec crosscheck clean screenshots beta performance ci ci-gradle ci-python ci-quality

setup:
	python3 -m venv $(VENV)
	$(PIP) install -q -r tools/requirements.txt

# :core:check runs unit tests + the 90% line-coverage gate.
test:
	cd $(ANDROID) && ./gradlew :core:check :app:testDebugUnitTest :app:jacocoDebugReport
	PYTHONDONTWRITEBYTECODE=1 $(PY) -m unittest discover -s tools/tests -v

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
	$(PY) tools/reference_interchange.py --exports $(ANDROID)/core/build/interoperability

verify: crosscheck build lint spec
	$(PY) tools/check_no_internet.py --manifest $(MERGED_MANIFEST)
	$(PY) tools/check_design_tokens.py --root $(ANDROID)/app/src/main/kotlin
	$(PY) tools/check_android_storage.py
	@echo "verify: OK"

screenshots:
	cd $(ANDROID) && ./gradlew :app:recordRoborazziDebug

clean:
	cd $(ANDROID) && ./gradlew clean
	rm -rf tools/.venv

# Persistent local signer; private files are ignored and never distributed.
beta:
	python3 tools/build_local_beta.py

# Host-only trend data. Deterministic cache/lifecycle limits are checked in test.
performance:
	cd $(ANDROID) && ./gradlew :app:benchmarkProjection

# CI has no local signing identity. The release workflow signs this exact APK.
ci-gradle:
	@test ! -e $(ANDROID)/local-signing.properties || { echo "ci-gradle requires an unsigned checkout without local-signing.properties"; exit 1; }
	cd $(ANDROID) && ./gradlew :core:check :app:testDebugUnitTest :app:jacocoDebugReport :app:benchmarkProjection :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease --console=plain

ci-python: spec
	$(PY) tools/crosscheck.py --spec spec --backup-dir $(ANDROID)/core/build/crosscheck
	$(PY) tools/reference_interchange.py --exports $(ANDROID)/core/build/interoperability
	PYTHONDONTWRITEBYTECODE=1 $(PY) -m unittest discover -s tools/tests -v
	$(PY) tools/check_no_internet.py --manifest $(MERGED_MANIFEST)
	$(PY) tools/check_no_internet.py --manifest $(ANDROID)/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml
	$(PY) tools/check_design_tokens.py --root $(ANDROID)/app/src/main/kotlin
	$(PY) tools/check_android_storage.py
	$(PY) tools/check_android_storage.py --manifest $(ANDROID)/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml

ci-quality:
	$(PY) tools/quality_gate.py
	@test -x "$(ANDROID_HOME)/build-tools/36.0.0/zipalign" && test -x "$(ANDROID_HOME)/build-tools/36.0.0/apksigner"
	"$(ANDROID_HOME)/build-tools/36.0.0/zipalign" -c -P 16 -v 4 $(ANDROID)/app/build/outputs/apk/release/app-release-unsigned.apk
	@if "$(ANDROID_HOME)/build-tools/36.0.0/apksigner" verify $(ANDROID)/app/build/outputs/apk/release/app-release-unsigned.apk >/dev/null 2>&1; then echo "CI release APK must be unsigned"; exit 1; fi

ci:
	$(MAKE) ci-gradle
	$(MAKE) ci-python
	$(MAKE) ci-quality
