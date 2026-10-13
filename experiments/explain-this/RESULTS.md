# Explain-this prompt comparison: 11 October 2026

The proposed instruction produced more explicit explanations of the difficult wording in both user-reported passages across both runs. Restoring the original request or fixing only the empty request did not show a consistent comparable improvement. At the time of this experiment, the Android app had not been changed. The owner subsequently approved the candidate for integration into version 4.1.1-personal; the tested instruction remains in `experiments/explain-this/candidate.txt`.

## What ran

- **48 completed GPT-6 Luna answers:** four variants × six passages × two runs.
- Actual returned model was `gpt-6-luna` for all 48 answers.
- Connection: separately authorized ChatGPT subscription, through the public Responses API, with `store:false`, `stream:true`, and identical provider defaults for every variant.
- The current/original prompt bodies and original message wrapper were extracted from source. Original baseline: `585b8e868f1febb854bfb0beb1dd2780f98a08a5`.
- The two reported passages used the supplied omnibus title **Blindsight + Echopraxia**, author **Peter Watts**. Four synthetic holdouts covered metaphor, idiom, ambiguous scene interpretation, and instructions embedded in fiction. These are not excerpts from Watts.
- Requests ran in random order. Responses were scored with variant labels hidden; labels were revealed after scores were saved.
- The original app's quoted answers were kept as reference material and never supplied to the model. No new Gemini test was run.
- The test session's token revocation was confirmed. No phone-app credentials or desktop Codex credentials were used.

## Exploratory review

One Codex reviewer scored meaning, clarity, explanation of difficult wording, and natural tone from 0–2 each, for a maximum of 8. The reviewer also wrote the candidate prompt. These are subjective exploratory scores, not independent evaluation or statistical evidence of general superiority.

| Variant | Answers | Mean score / 8 | Mean explanation score / 2 | Answers flagged for unsupported detail |
|---|---:|---:|---:|---:|
| Current app request | 12 | 7.25 | 1.25 | 0 |
| Current instructions + original message wrapper | 12 | 7.17 | 1.25 | 1 |
| Original instructions + original wrapper, on Luna | 12 | 7.08 | 1.25 | 2 |
| Proposed explanation-focused instruction | 12 | 8.00 | 2.00 | 0 |

The meaningful difference was explanation depth. Most baseline answers correctly captured the broad meaning and had readable, natural wording. The candidate more often explained the confusing expression itself. The straightforward idiom was already explained well by every variant. No answer followed the instruction embedded inside the fictional note or revealed a book ending.

The candidate avoided the unsupported details flagged in other variants within this small sample. This does not establish a general no-hallucination or no-spoiler guarantee.

## Actual outputs for the user's passages

These are live GPT-6 Luna outputs, copied without editing. Both runs are included to avoid selecting only the best answer.

### Lung fluke: candidate, run 1

> The sentence jokes that sequencing the lung fluke’s DNA would produce such baffling results that you might as well flip a coin to guess what you were looking at: genes that make proteins, or details about Denver’s sewers. “Even money” means the odds would be about fifty-fifty. The sewer plans aren’t meant literally; they’re an absurd comparison for how scrambled or unreadable the genetic sequence seems.

### Lung fluke: candidate, run 2

> This is a joke about how wildly mixed-up the lung fluke’s genetic code seems. “Sequencing” means reading the order of its DNA building blocks, or base pairs. The sentence says you’d have about a 50–50 chance of reading a sequence that codes for a protein—or one that looks like technical information about Denver’s sewers. It doesn’t mean the DNA literally contains sewer plans; the comparison exaggerates how strange or nonsensical the results appear.

Both explicitly clarify equal odds and the absurd comparison. One also defines sequencing. The current app prompt's two fresh answers primarily paraphrased the joke. An original-prompt answer added a cause—borrowed genetic material from many sources—that the selection does not establish. The wrapper-only variant also produced a speculative explanation involving repetitive or contaminated genetic patterns.

### Closed eyelids and light: candidate, run 1

> She is too traumatized to look: her eyes are clenched shut, and the horror she witnessed keeps replaying in her mind. Her own cries drown out any other sound. But she senses something nearby because it blocks a tiny amount of the light reaching her closed eyelids. That barely noticeable change tells her something is present, even though she can’t see or hear it. “Drank a little of the light” is a figurative way of saying it cast a faint shadow.

### Closed eyelids and light: candidate, run 2

> With her eyes tightly shut and her own cries drowning out other sounds, she can’t directly see or hear what’s happening. But something blocks a tiny amount of the light reaching her face. Even through her closed eyelids, she notices that slight dimming—and recognizes it as a clue that something is nearby. “Drank” is figurative: the thing isn’t literally consuming light; it’s casting a shadow.

Both make the link between closed eyelids, dimming light, and recognition explicit, and explain the unusual verb. The first run also unpacks the horrific mental image. Neither identifies the character or the presence, nor adds later events.

## Interpretation and limits

The evidence supports testing an explanation-focused instruction with scoped grounding: general knowledge can explain language and imagery, while story facts stay tied to the selection. It does not support removing grounding wholesale as the main remedy. The empty request remains a source discrepancy, but its isolated fix showed no reliable explanation benefit in this run.

The experiment demonstrates that prompts can change Luna's behavior on these examples. It cannot attribute the entire difference between the original Gemini app and the fork to prompting; Gemini was not tested here, and models can respond differently to the same instruction.

This sample contains only two real-book passages and four synthetic holdouts, with two repeats each. Some candidate answers also add an explicit figurative-versus-literal clarification even when a reader might already understand that point. Review the style before adopting it, and test any further revision rather than assuming it inherits these results.

## Reproducible evidence

- Candidate: [candidate.txt](D:/Projects/ai-reader/experiments/explain-this/candidate.txt)
- Inputs and references: [cases.json](D:/Projects/ai-reader/experiments/explain-this/cases.json)
- Saved scores, before labels were revealed: [review-scores.json](D:/Projects/ai-reader/experiments/explain-this/review-scores.json)
- Exact requests: [requests.json](D:/Projects/ai-reader/artifacts/prompt-eval/live-2026-10-11T03-31-12-698Z/requests.json)
- All live outputs and returned model/usage: [answers.json](D:/Projects/ai-reader/artifacts/prompt-eval/live-2026-10-11T03-31-12-698Z/answers.json)
- Label-hidden review: [review.md](D:/Projects/ai-reader/artifacts/prompt-eval/live-2026-10-11T03-31-12-698Z/review.md)
- Variant mapping: [review-key.json](D:/Projects/ai-reader/artifacts/prompt-eval/live-2026-10-11T03-31-12-698Z/review-key.json)
- Completion and revocation: [status.json](D:/Projects/ai-reader/artifacts/prompt-eval/live-2026-10-11T03-31-12-698Z/status.json)

Five local harness checks passed. They cover source-derived variant construction, stream completion/UTF-8 handling, safe handling of provider errors, callback state matching, and signed-identity validation. They support the runner's correctness, not model quality.

An initial sandboxed attempt could not reach OpenAI. The next attempt authenticated but stopped at a content-type-header check, recording no answer. The final run used the app's event-based stream handling and completed all 48 answers. Those failed attempts are preserved separately in ignored `artifacts/prompt-eval/`; their existence is not counted as a successful model test.
