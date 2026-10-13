# Scene explanation acceptance fixtures

These are manual prompt/evidence review cases, not recorded live-provider outputs. Production evidence assembly, frozen endpoints, history routing, source locations, and restoration are exercised separately by `DiscussionContextTest` and `RecentSceneWindowTest`. HTTP answers in those tests are mocked.

| Case | Supplied evidence | Expected brief answer | Reject |
|---|---|---|---|
| Earlier promise | Earlier: Mara promised Ivo to repair the lamp before the boat returned. Current: Ivo says, “Keep your promise, Mara.” | Ivo is reminding Mara of her commitment to repair the lamp. Connect the current line to the earlier promise and cite that excerpt. | An invented argument, betrayal, or later outcome. |
| Indirect reference | Earlier: a cable broke and stopped the lamp. Current: “Did you take care of it?” | Identify “it” as the repair only when the exchange makes that connection clear; otherwise qualify the interpretation. | Treating an ambiguous pronoun as certain. |
| Unattributed dialogue | Two untagged speakers exchange “You promised” and “I know,” without identifying names. | Explain the reminder and acknowledgement while saying the speaker identities cannot be established from the supplied text. | Assigning names or motives absent from evidence. |
| Scene transition | A marked scene break separates a previous disagreement from a quiet harbor conversation. | Focus on the exchange nearest the marked endpoint; use the previous disagreement only if it helps explain a reference. | Recapping both scenes as one uninterrupted conversation. |
| Same-chapter spoiler | A later paragraph reveals the ending after the captured selected sentence. | Explain using only earlier material. | The later reveal in evidence, source excerpts, or ordinary follow-up history. |
| Larger context | The first 24-paragraph window lacks the setup, which exists in the additional preceding paragraphs. | After Include more context, explain the setup from the expanded ordered evidence. | Moving the endpoint forward or silently dropping the requested expansion. |

The new scene instruction requests compact answers and qualified interpretations. The existing approved passage-explanation instruction and wrapper remain unchanged. Model-specific prose quality and reliability still require a live evaluation through an authorized configured connection; no real credentials were needed for implementation or emulator QA.
