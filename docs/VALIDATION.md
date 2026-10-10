# Validation record

Validation date: 10 October 2026. Baseline: `585b8e868f1febb854bfb0beb1dd2780f98a08a5`.

## Build baseline

The upstream build reached Android compilation but failed because its required `google-services.json` was absent. The personal edition removes that dependency, all Firebase runtime libraries, Google account authentication, Play Billing, cloud-sync workers, and application-level quota accounting.

## Completed checks

- Provider and grounding unit tests: **17 passed**. All four text transports, native Gemini key placement, terminal completion, interrupted streams, subscription limits after partial output, rejected image use through a subscription token, account model visibility, image MIME types, classification fallback, and context limits.
- Native Android tests on API 37: **13 passed** (12 library/retrieval/OAuth tests and one notification test). Complete ZIP round-trip, corrupted input rejection, unsafe archive paths, failed-import preservation, interrupted-restore recovery, version-3-to-4 migration, loopback state/PKCE, signed identity verification, rejection of forged tokens, refresh rotation, session revocation, consent denial, cancellation, actual Readium extraction, and ONNX retrieval.
- Real ONNX/Readium tests reproduced an upstream off-by-one spoiler cutoff. Extraction and reader progress are zero-based; retrieval incorrectly added one. The cutoff is corrected across generic and character retrieval.
- Debug APK assembly, Android test APK assembly, and Android lint passed with the spoiler correction. Lint reports zero errors; the retained project has 404 warnings and one hint.
- A signed, optimized release APK is built with a stable private signing key stored outside Git.
- The resolved release dependency graph contains no Firebase, Google authentication, Billing, Analytics, or Crashlytics libraries. Cloud sync services and workers were removed. Android automatic backup is disabled; backups are explicit ZIP exports.
- Standard emulator visual checks covered login-free library launch, EPUB file-picker import, rendered Readium pages, persisted library/continue-reading after restart, and AI settings with unconfigured controls disabled. Screenshots are saved locally under `artifacts/qa/`.
- Chapter navigation and restart passed: chapter two remained at page 4 of 7 (42%), and Continue reopened the second chapter. A temporary reader ANR occurred while Gradle/R8 competed with the emulator on this 8 GB host; the reader rendered normally after the build completed. Device performance has not been profiled.
- The signed release installed successfully and opened directly into an empty library without login. Android package inspection confirms `com.yugentech.quill.personal`, version `4.1.0-personal` (14), minimum API 26, and target API 37.
- Reminder notification delivery passed after the owner explicitly authorized notification permission on the emulator. The test verifies that the app's notification appears; daily alarm timing and reboot rescheduling remain unverified.

Native OAuth tests mock the remote identity provider while exercising actual Android cryptography and loopback sockets. They do not establish account eligibility or live authorization.

## Pending owner-dependent checks

- Real API-key inference for OpenRouter, Gemini, and OpenAI.
- Real ChatGPT browser authorization, account model access, and subscription usage.
- Live image generation and paid cloud speech.
- Full manual regression of discovery downloads, every quick action, highlighting, gallery passage navigation, speech output, reminders, themes, sounds, categories, and insights remains unverified. Retaining these features and passing compilation does not establish complete end-to-end coverage.
- Backup crash recovery is tested through a persisted interrupted-operation journal; power loss during individual filesystem writes is not emulated.

Credentials must be entered in-app by the owner. No credentials were borrowed from this desktop's Codex session or embedded in the source.

## Delivered APK

File: `artifacts/quill-personal.apk` (149,051,222 bytes). APK signature verification passed using APK Signature Scheme v2, RSA 3072.

SHA-256: `5f657bf69ad54b37d03d81e074971191960d2c830e05a0d073c2a2b5c83319d0`.

Signing certificate SHA-256: `80054e1fbd0114e8aa308fc6e7b68620f94d938fdf86a96a2b85e4e465e621ac`. The private signing material remains in the ignored local `.private/signing/` directory for future updates.
