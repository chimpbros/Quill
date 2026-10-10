# Building Quill Personal

## Prerequisites

- JDK 21 for the Gradle daemon. The repository's pinned daemon configuration can provision its Java 21 runtime through the existing toolchain resolver.
- Android command-line tools or Android Studio, Android SDK 37, Build Tools 37.0.0, and platform tools.
- PowerShell 7 for the provided Windows build/signing script. The normal Gradle wrapper also works on other platforms.

The official Android CLI package names are:

```text
android --no-metrics --sdk=<SDK_DIR> sdk install platforms/android-37.0 build-tools/37.0.0 platform-tools
```

Set `JAVA_HOME` and create an ignored `local.properties` containing your SDK location. Windows example:

```properties
sdk.dir=D\:/Projects/ai-reader/.tools/android-sdk
```

This workspace's JDK/SDK/Gradle caches are under `.tools/`; those files are not committed. No `google-services.json`, Firebase CLI, Google account client ID, or build-time model API key is needed.

## Checks and APK builds

```powershell
./tools/build-personal.ps1
```

This runs the debug APK build, provider/grounding unit tests, and Android lint. For native tests, start a normal emulator or attach a test device:

```powershell
./gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

Android tests use synthetic credentials and an invented EPUB. Provider HTTP is mocked for protocol validation; native tests cover actual Keystore, loopback sockets, token signatures, Room migrations, archive restoration, Readium extraction, and ONNX retrieval.

ATD images are suitable for native logic tests but disable rendering and omit browsers. Use a standard image for screenshots and real OAuth browser testing.

## Stable personal signing

```powershell
./tools/build-personal.ps1 -SignedRelease
```

The script creates `.private/signing/quill-personal.p12` only if absent, then reuses it. Its generated password is stored in `.private/signing/password.clixml`, protected by the current Windows account's DPAPI. Neither file is tracked by Git. The APK is copied to `artifacts/quill-personal.apk`.

Back up the signing directory securely. The encrypted password file is tied to this Windows account; prepare a secure password transfer before migrating to another machine. Never overwrite the keystore when producing an update.

For another build environment, supply the existing keystore through environment variables:

```text
QUILL_SIGNING_STORE_FILE=<absolute keystore path>
QUILL_SIGNING_STORE_PASSWORD=<stored password>
QUILL_SIGNING_KEY_PASSWORD=<stored password>
```

The key alias is `personal`, and the store type is PKCS12. Without signing variables, `assembleRelease` produces an unsigned release artifact; the provided signing script supplies them.

## Updating and backups

The application ID is independent of the original Quill. Export/import your EPUBs manually for a fresh installation; the fork does not read the original app's private storage. Back up reading data before uninstalling. Normal updates signed with the same key preserve the personal app's data.

Room schema version 4 adds chapter provenance and removes the unused quota table. Destructive migration fallback is disabled, and the schema is exported under `database/schemas/`.
