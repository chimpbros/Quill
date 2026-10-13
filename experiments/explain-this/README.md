# Explain-this prompt experiment

Running this experiment does not modify the Android app or its saved connections. Candidate status: **approved by the owner and integrated into version 4.1.1-personal**. See [RESULTS.md](D:/Projects/ai-reader/experiments/explain-this/RESULTS.md) for the original live comparison, exploratory scores, and limitations.

The pre-update fork prompt and wrapper are frozen in `historical-baseline.json`; the variant ID `current` refers to that historical 4.1.0 request, not the updated app. The original prompt/message wrapper are extracted from upstream commit `585b8e868f1febb854bfb0beb1dd2780f98a08a5`. The model stays `gpt-6-luna`; every variant uses the same provider defaults and the same public Responses API transport as the app's ChatGPT subscription connection. This compares prompts on Luna; it does not run a new Gemini baseline. The user's original-app answers are style references, not ground-truth scientific claims, and are never sent to the model.

Four variants isolate the changes:

| Variant | Instructions | Passage wrapper |
|---|---|---|
| current | App's existing instructions and grounding prefix | App's current wrapper, including empty REQUEST |
| wrapper-only | Same as current | Original wrapper with explicit fallback request |
| original | Original explanation instructions, without fork prefix | Original wrapper |
| candidate | Proposed explanation-focused instruction with scoped grounding | Original wrapper |

The two user-provided passages use the supplied omnibus title, **Blindsight + Echopraxia**, and author **Peter Watts**. Four synthetic holdouts check metaphor, idiom, uncertainty, and instructions appearing inside fiction. Synthetic texts are not attributed to Watts. Each of the six cases runs twice for each variant: 48 requests total, in randomized order.

Preparation and local harness checks:

```powershell
node experiments/explain-this/evaluate.mjs --prepare
node --test experiments/explain-this/harness.test.mjs
```

These are not model-quality tests. Prepared requests and statuses go to ignored `artifacts/prompt-eval/`.

Live subscription comparison:

```powershell
node experiments/explain-this/evaluate.mjs --live
```

Open the printed official sign-in link in your browser and complete Continue with ChatGPT. The standalone experiment registers as **Quill Prompt Comparison**, with a separate host/registration from the phone app. It uses the [official open-source sign-in flow](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), validates state, PKCE, the identity signature, issuer, audience, expiration, nonce, and granted plan-use scopes. It checks the live account catalog for GPT-6 Luna and does not substitute another model. Subscription eligibility remains provider-controlled.

Tokens are held only in process memory, never written to output files or read from desktop Codex credentials. Only non-token registration metadata is retained under ignored `.private/prompt-eval/`. The session token is revoked when the run finishes; status records whether revocation was confirmed. If it was not, disconnect **Quill Prompt Comparison** in ChatGPT settings. Test prompts and passages are sent to OpenAI using the authorized subscription connection, with `store:false` and `stream:true`.

Each live output is saved with case, variant, repeat, returned model, and usage when available. An interrupted or rejected stream never counts as a completed answer. Failure stops the run; it does not change connections or retry with another account. `review.md` randomizes answer labels for human evaluation; `review-key.json` reveals the variant mapping afterward.

Review meaning, clarity, explanation of difficult wording, and natural tone from 0–2 each. Flag unsupported story claims, spoilers, and literal readings of figurative language separately. Do not reward extra length, mandatory analogies, or copying the original app's wording. A revised prompt should improve explanation on the reported passages while preserving accuracy on holdouts across both repeats. Mixed results call for more representative passages before changing the app.

No live-quality result is implied by passing preparation, authentication mocks, or stream parser checks.

`node experiments/explain-this/long-chapter.mjs` runs a separate single-request smoke test using the app's summary instructions and a 72,802-byte synthetic chapter. It verifies completed inference and coverage of the ending. It does not send the user's book files or test a real chapter from Blindsight or Echopraxia.
