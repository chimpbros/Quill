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

Text connections support model discovery, manual model IDs for API accounts, and a small connection test. Context capacity uses provider metadata, verified model defaults, or a fallback estimate, with an optional manual override. Use **Save context settings** to persist changes to the current text connection; updating an existing ChatGPT model does not require another model fetch. Old default settings migrate automatically, while custom values remain manual. The app estimates tokens to trim complete old conversation turns and always sends the current chapter or passage intact. The provider enforces its actual limit and oversized requests produce a provider error. No connection is selected automatically, and the app never switches to a different account or billing method after an error.

Aira distinguishes broad introductions, passage explanations, literal book terms, chapter recaps, named sections, and the story so far. A shared EPUB navigation structure resolves actual section boundaries, including chapters sharing one file and novels spanning several files. Literal lookup works without embeddings or completed indexing. Aira can explain a demonstrated function as a qualified inference; broad introductions use general knowledge and stay focused on the premise.

With spoiler lock enabled, evidence normally stops at the reading location. An explicit current-chapter recap includes that chapter. Later sections and complete works require permission for that answer unless spoilers were explicitly allowed. Broader answers are excluded from subsequent spoiler-limited request history. Legacy chats remain visible but require broader permission before being reused as evidence. Summaries send the complete ordered requested text; oversized requests report provider limits. A generative model cannot provide an absolute no-spoiler guarantee. The local ONNX indexing model remains available.

The reader toolbar now includes Chapters and Bookmarks, offline Search, a bookmark toggle, and an overflow menu for settings and ambient sounds. Search defaults to the whole book and offers a Read so far filter, exact result jumps, temporary match highlighting, and Return to reading. Bookmarks save stable locators with optional labels; their list supports jump, rename, and removal with Undo. Both reader navigation and Book Details indicate the current chapter.

**Explain this** uses a tested instruction that unpacks difficult wording, idioms, and imagery in everyday language. General knowledge can clarify the language, while story details stay grounded in the selected passage. See the [prompt comparison](experiments/explain-this/RESULTS.md) for the live GPT-6 Luna evaluation.

Aira remembers the current discussion separately for each book, including the selected passage, completed answer, and captured reading boundary. Closing the panel, changing pages, or reopening the app preserves it. **New topic** keeps the transcript while resetting this context. **Catch me up here** explains the visible scene; **Explain this conversation** adds earlier setup to a selection. **Include more context** adds another 24 preceding paragraphs without moving the endpoint. Nearby text spans EPUB files, and earlier references use literal search independently of embedding readiness. Supplied source IDs open their excerpts, with a book jump and **Return to reading**. Missing precise locations require a selection rather than a guess based on progress. Synthetic scene acceptance fixtures are documented in [scene-context review](experiments/scene-context/README.md); live scene-answer quality has not been evaluated.

For a long selection on mobile, select its opening words and choose **Start here**, turn pages or scroll, then select its final words and choose **End here**. Preview the complete range, adjust either endpoint, explain it, or save one highlight with a color and note. A saved range renders across resources but remains one list entry; tapping and deleting any segment affects the whole highlight. Drafts survive rotation and font changes and are canceled when leaving the book. Saved ranges persist across restarts.

## ChatGPT subscription access

**Continue with ChatGPT** uses the [official open-source OAuth flow](https://developers.openai.com/siwc/token-sharing-open-source/sign-in): a system browser, loopback callback, PKCE, verified identity tokens, protected token storage, and serialized refresh. Inference uses the public Responses API with `store:false`, streaming, and explicit conversation history. It does not use ChatGPT private backend endpoints or a desktop bridge.

Account eligibility, available models, and usage limits are controlled by OpenAI. API keys and subscription tokens are separate connections. Current subscription-route restrictions exclude image generation and speech; see the [official limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

Live inference and real account authorization must be confirmed with credentials entered by the app's owner. See [validation results](docs/VALIDATION.md) for the distinction between local protocol tests and live-provider validation.

## Backups

Export a complete ZIP using the Android file picker. It contains EPUBs, available local covers, categories, reading positions, bookmarks, highlights, chats, generated illustrations and passage links, reading sessions, profile, and settings. It excludes credentials, OAuth registrations, temporary audio, caches, and embedding indexes.

Backups are not password encrypted. Restore validates format, schema, paths, file sizes and SHA-256 hashes, then replaces the library after confirmation. Settings are restored through their storage APIs. A recovery journal protects the prior library if import fails or is interrupted. Indexes are rebuilt afterward; existing destination credentials remain untouched. The current backup format targets database version 7 and accepts version-4, version-5, and version-6 backups. Discussions, message associations, bookmarks, chat scope metadata, and segmented highlights are included. Earlier highlights retain one segment.

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
