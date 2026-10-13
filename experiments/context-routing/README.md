# Context routing comparison

The 4.1.1 baseline is frozen in `baseline-4.1.1.json`, independently of production prompt changes. The owner approved integration of the final candidates on 11 October 2026 after reviewing the live comparison and focused retest.

`candidate-book.txt`, `candidate-general.txt`, and `candidate-classifier.txt` are the reviewed instructions. Production `ContextPrompts` adopts their text unchanged. The original approved Explain this candidate and wrapper remain unchanged and have separate regression fixtures.

## Live results

- Full comparison: `artifacts/context-eval/live-2026-10-11T05-17-51.685Z/review.md`, 80 completed GPT-6 Luna calls through a separately authorized ChatGPT subscription connection.
- Focused retest: `artifacts/context-eval/live-2026-10-11T05-26-52.793Z/review.md`, 16 completed calls after refining broad introductions and numeric chapter routing.
- Both connections were revoked successfully. Credentials are excluded from artifacts.
- The initial candidate incorrectly classified chapter 3 as unresolved and included an unsupported mission-leadership claim in one overview. The final classifier names chapter 3 for local resolution, and the final overview instruction focuses on premise and central ideas. Both numeric-chapter retests classified the target correctly; neither revised overview repeated the leadership claim.
- The final StarlAmp answer inferred low-light imaging from the supplied evidence and left its mechanism uncertain. The baseline refused to infer a function without an explicit definition.
- These are instruction comparisons with fixed evidence, not measurements of the complete production EPUB retrieval pipeline. Named-section boundaries, reading progress, and per-request spoiler permission are resolved and tested locally.
- Model output remains variable. One revised overview used Markdown italics despite the plain-text instruction; one ending request stayed unresolved because the omnibus target was ambiguous. An unresolved target prompts local clarification rather than wider access.

## Reproduction

Run `evaluate.mjs --prepare` to generate requests without network access, `--live` for the full authorized comparison, or `--retest` for the focused cases. Live runs use their own browser authorization, not the phone's credentials or the desktop Codex session. Original requests, outputs, and review text are retained under the dated artifact directories.
