# Generated transcripts — commit index

Branch: `transcript-asr-word-timestamps`, based on `develop`.
Entry point for the feature itself: [transcript-handover.md](transcript-handover.md); decisions: [ADR 0001](adr/0001-generated-transcripts-decode-ahead-of-playhead.md), [ADR 0002](adr/0002-generated-transcripts-are-gap-aware-artifacts.md).

Read this file first when you need the intent of an ASR commit. Each row is one commit on the branch; subjects are the original messages, notes say what the change actually established. Device-verified status lives in handover §5.2.

## How the work is layered

1. **`asr` module + pre-read pipeline** — decode audio ahead of the playhead (MediaExtractor/MediaCodec, or Media3 `SimpleCache` for streams), 16 kHz mono chunks, coverage and lookahead planning.
2. **Recognition + timing** — sherpa-onnx streaming recognizer, token→word mapping, emission-lag correction, CJK handling.
3. **Model distribution** — one pinned bilingual punctuated model, SHA-256 pins, in-app download/install.
4. **Persistence + resume** — word-JSON with an embedded coverage checkpoint; atomic saves; finalize-from-gap.
5. **App session + UI** — process-level manager/controller, transcript page button and progress, background finalizer, runtime guards, word/sentence highlight and follow.

## Commits

### `:asr` module, pipeline, persistence

| Commit | Subject | What it established |
| --- | --- | --- |
| `99e0b9c72` | word-level ASR with corrected token timing | Base commit: module, decode pipeline, recognizer wrapper, word JSON, session, first UI wiring. |
| `2ecd12677` | count local files as transcript availability | A local `.transcript` file alone is enough to show/enter the transcript. |
| `cbbf62661` | quiet high-frequency debug logs | `AsrSync`/`AsrFollow`/tick logs to debug level. |
| `8a918f855` | satisfy checkstyle in new ASR UI code | Style-only cleanup for the new app code. |
| `82c210810` | fix resume, stop reporting and atomic saves | Review batch: stopped sessions no longer emit COMPLETED; finalize advances past covered windows; checkpoints only cover finalized audio; unique temp file + rename; stops no longer cancel the finalizer; worker yields to a live session; download NPE. |
| `bf9175347` | resume finalization from the coverage end | A finalizer starting inside covered ranges stalled (window covered, no `decodedEnd` change, deduped callback). It now starts at `PreReadPcmPipeline.coverageEndMs()`. |

### Recognition, tokens, timing

| Commit | Subject | What it established |
| --- | --- | --- |
| `7d9bc6ea2` | adopt punctuated zh-en model, stabilise follow | Moved to the x-asr 480 ms bilingual model; rule3 30 s/window 40 s; token quirks (stray `▁.`, standalone `▁`, punctuation attach); follow fixes. |
| `29bb1d2c3` | drop spaces after CJK punctuation | The punctuated model separates pieces with spaces; CJK punctuation takes none. |
| `1a1324da3` | CJK word lookup and keep follow frames alive | CJK punctuation kept in the preceding word, parser tolerates unlisted CJK punctuation, mapper treats CJK chars as boundaries; follow frames kept alive on a static list; screen-centre reading line; follow starts on first load; stale `userScrolling` cleared on gap fills. |

### Model distribution

| Commit | Subject | What it established |
| --- | --- | --- |
| `771a48c0c` | pin SHA-256 for the model files | Per-file SHA-256 in `AsrModelCatalog`; downloader rejects mismatches. |
| `ea846ac56` | show the model download size | Prompt shows the exact 134 MB. |
| `1b27107cf` | drop the per-language model routing | `AsrModelLanguage` removed; one model everywhere. |
| `28f4c442f` | show the model install phase after the download | 100 % + extraction/SHA looked frozen; the button now says "installing". |

### Streaming, finalizer, guards

| Commit | Subject | What it established |
| --- | --- | --- |
| `e07989355` | generate for streaming episodes from the cache | `CacheAudioSource` over the player's `SimpleCache`; transcript under `files/asr-transcripts/<mediaId>.transcript`. |
| `4f32f4119` | finish in the background after process death | Unique +15 min WorkManager job, foreground notification, checkpoint continuation, 9-min runs, retry cap. |
| `c7518cd13` | stop when casting, battery saver or heat starts | Per-second guard; STOPPED carries the reason. |
| `c2e2c7d5f` | detect MIUI power saver in the run guard | MIUI keeps `isPowerSaveMode()` false; also read `POWER_SAVE_MODE_OPEN`. |

### Ad-skip interaction

| Commit | Subject | What it established |
| --- | --- | --- |
| `a77125fad` | log skip decisions; drop unused ASR model pins | `skip media/from/to/tier/seg` and `skip undo shift` logs used during device runs. |

### Tests and documentation

| Commit | Subject | What it established |
| --- | --- | --- |
| `a29e211c8` | cover the finalize scheduler with tests | Unique per-media work, cancellation, independence. |
| `2dd677ba1` | test streaming load and finalize completeness | Streaming transcript load; completeness by coverage end. |
| `ef23f0d17` | test the runtime guard decisions | Casting / power saver / normal. |
| `e9e3de80c`, `1abbaab6e`, `b7f257233`, `5ebb78938` | anchor refreshes | Code-map anchors after the ASR changes. |
| `b25b1fb78` | device checklist for ASR, streaming and guards | Handover §5.4. |
| `749d72cb3` | refresh the 30-second onboarding for ASR | Handover §0. |
| `c74967a5e`, `c22548761` | device-run records | Ad-skip regression, model download, finalizer findings. |

## Where to look next

- Behaviour and code anchors: [transcript-handover.md](transcript-handover.md) §1 and §3.
- Device evidence and known platform limits: handover §5.2 and §7.
- Open review findings: handover §7, ASR bullet.
