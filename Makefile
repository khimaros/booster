GRADLE := ./gradlew --console=plain
APK := app/build/outputs/apk/debug/app-debug.apk
TESTPACK_APK := testpack/build/outputs/apk/debug/testpack-debug.apk
TESTPIN_APK := testpin/build/outputs/apk/debug/testpin-debug.apk
E2E := e2e

.PHONY: build release release-setup test-e2e screenshots precommit lint format clean

build:
	$(GRADLE) :app:assembleDebug :testpack:assembleDebug :testpin:assembleDebug

# VERSION=1.2.3 sets the app version (the tag workflow passes it); BOOSTER_KEYSTORE and friends sign it
release:
	$(GRADLE) :app:assembleRelease $(if $(VERSION),-Pbooster.version=$(VERSION))

# boots (or reuses) this project's own emulator, installs both apks and runs the python suite
test-e2e: build
	BOOSTER_APK=$(APK) BOOSTER_TESTPACK_APK=$(TESTPACK_APK) BOOSTER_TESTPIN_APK=$(TESTPIN_APK) python3 -m unittest discover -s $(E2E) -t . -v

# one time: creates the release keystore (outside the repo) and stores the signing secrets on github
release-setup:
	sh scripts/release-setup.sh

# refreshes docs/screenshots from the real launcher on the project emulator
screenshots: build
	BOOSTER_APK=$(APK) python3 -m e2e.screenshots docs/screenshots

lint:
	$(GRADLE) :app:lintDebug
	ruff check $(E2E)
	ruff format --check $(E2E)

precommit: build lint

format:
	ruff format $(E2E)

clean:
	$(GRADLE) clean
