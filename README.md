# Quill Personal

A personal Android edition of [Quill](https://github.com/MohammadAliUstad/Quill), originally created by Mohammad Ali Ustad / Yugen Tech. Based on upstream commit `585b8e868f1febb854bfb0beb1dd2780f98a08a5`. Distributed under the existing [Apache 2.0 license](LICENSE).

The app installs as **Quill Personal** (`com.yugentech.quill.personal`) alongside the original app. No Quill account, Firebase project, backend deployment, or Quill Pro subscription is required.

## Reading and AI

Readium EPUB reading, local imports, Gutenberg and Standard Ebooks discovery, highlights, categories, reading positions, themes, soundscapes, reminders, and insights are retained. Books, conversations, illustrations, and indexes stay in local app storage.

Configure connections in **More → AI Connections & Backups**. Text and images have independent provider/model selections.

| Connection | Text and quick actions | Scene images | Read-aloud |
|---|---|---|---|
| OpenRouter API key | Yes | Image-capable models | — |
| Gemini API key | Yes | Native image models | — |
| OpenAI API key | Yes | Image API models | Optional cloud speech |
| ChatGPT OAuth | Eligible account models | Unsupported by this route | Unsupported by this route |
| Android text-to-speech | — | — | Default |

API keys are entered in-app and encrypted using Android Keystore. Never add keys to source code or Gradle properties. Credentials and OAuth registration records are excluded from backups.

Text connections support model discovery, manual model IDs for API accounts, and a small connection test. Set the model's context window accurately; the app conservatively trims old conversation history and reports oversized chapter input instead of silently truncating the chapter. No connection is selected automatically, and the app never switches to a different account or billing method after an error.

The local `bge-small-en-v1.5` ONNX model and hybrid semantic/FTS retrieval are retained. Spoiler locking uses the current chapter, including the rest of that chapter, and excludes later-chapter conversations from requests. Whole-chapter summaries retain the original behavior. Prompt grounding also instructs models not to reveal information from their prior knowledge; a generative model cannot provide an absolute no-spoiler guarantee.

## ChatGPT subscription access

**Continue with ChatGPT** uses the [official open-source OAuth flow](https://developers.openai.com/siwc/token-sharing-open-source/sign-in): a system browser, loopback callback, PKCE, verified identity tokens, protected token storage, and serialized refresh. Inference uses the public Responses API with `store:false`, streaming, and explicit conversation history. It does not use ChatGPT private backend endpoints or a desktop bridge.

Account eligibility, available models, and usage limits are controlled by OpenAI. API keys and subscription tokens are separate connections. Current subscription-route restrictions exclude image generation and speech; see the [official limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

Live inference and real account authorization must be confirmed with credentials entered by the app's owner. See [validation results](docs/VALIDATION.md) for the distinction between local protocol tests and live-provider validation.

## Backups

Export a complete ZIP using the Android file picker. It contains EPUBs, available local covers, categories, reading positions, highlights, chats, generated illustrations and passage links, reading sessions, profile, and settings. It excludes credentials, OAuth registrations, temporary audio, caches, and embedding indexes.

Backups are not password encrypted. Restore validates format, schema, paths, file sizes and SHA-256 hashes, then replaces the library after confirmation. Settings are restored through their storage APIs. A recovery journal protects the prior library if import fails or is interrupted. Indexes are rebuilt afterward; existing destination credentials remain untouched. The first backup format targets database version 4.

Notifications are opt-in through More settings. Denying permission never blocks reading.

## Build and install

See [build instructions](docs/BUILD.md). The project uses Kotlin, Compose, Room, Ktor, Readium, and ONNX Runtime. The pinned Gradle daemon requires Java 21 and the project compiles against Android SDK 37. Android 8.0/API 26 remains the minimum.

```powershell
./tools/build-personal.ps1
./tools/build-personal.ps1 -SignedRelease
```

The signed build is copied to `artifacts/quill-personal.apk`. Copy it to your Android device and install it. Keep `.private/signing/` securely: the signing key is needed for future updates that preserve app data. Runtime downloads, signing material, APKs, and local configuration are ignored by Git.

## Attribution

Original project: **Quill**, Mohammad Ali Ustad, **Yugen Tech**. Original license and retained source attribution remain in the repository. This fork is a personal edition and is not an official release from the original developer.
