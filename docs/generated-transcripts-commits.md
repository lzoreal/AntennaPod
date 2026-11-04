# Generated transcripts — commit index

Part of `develop`; the seven commits below are the generated-transcript (ASR) slice of the fork history.
Entry point for the feature itself: [transcript-handover.md](transcript-handover.md); decisions: [ADR 0001](adr/0001-generated-transcripts-decode-ahead-of-playhead.md), [ADR 0002](adr/0002-generated-transcripts-are-gap-aware-artifacts.md), [ADR 0003](adr/0003-generated-transcript-append-journal-and-session-lease.md).

The whole fork-local history was rewritten on 2026-11-05: upstream commits and the upstream sync merges were kept, and every local transcript / ASR / ad-detection commit was squashed into a thematic commit. Each commit below is a real snapshot that built and passed its tests at the time. The pre-rewrite commits are only reachable from the local `backup/pre-rewrite-*` branches and the reflog; they are not on `origin`.

## How the work is layered

1. **`asr` module + pre-read pipeline** — decode audio ahead of the playhead (MediaExtractor/MediaCodec, or Media3 `SimpleCache` for streams), 16 kHz mono chunks, coverage and lookahead planning.
2. **Recognition + timing** — sherpa-onnx streaming recognizer, token→word mapping, emission-lag correction, CJK handling.
3. **Model distribution** — one pinned bilingual punctuated model, SHA-256 pins, in-app download/install.
4. **Persistence + resume** — word-JSON with an embedded coverage checkpoint; atomic saves; append journal and session lease; finalize-from-gap.
5. **App session + UI** — process-level manager/controller, transcript page button and progress, background finalizer, runtime guards, word/sentence highlight and follow.

## Commits

| Commit | Area | What it established |
| --- | --- | --- |
| `7fc8f4556` | foundation | `:asr` module, pre-read decode pipeline, streaming recognizer wrapper, token→word mapping, word JSON with coverage checkpoint, first transcript-page wiring. |
| `8581ec813` | model + timing | x-asr 480 ms bilingual punctuated model; rule3 30 s and 40 s window; token quirks (stray `▁.`, standalone `▁`, punctuation attach); 400 ms emission lag; follow stabilised for long sentences. |
| `93ec49811` | polish | exact model size in the prompt, local transcript files count as available, high-frequency logs to debug, checkstyle. |
| `6431c5043` | streaming + lifecycle | streaming episodes via `SimpleCache`; SHA-256 per model file; casting/battery-saver/thermal stops; WorkManager finalizer after process death. |
| `27e837668` | tests + docs | scheduler/completeness/streaming-load/guard tests; per-language routing removed; handover anchors, device checklist and onboarding refreshed. |
| `8f335fd49` | device fixes | stop no longer reports completion; finalize resumes from the coverage end; atomic saves; checkpoint covers only finalized audio; CJK punctuation, spacing and word lookup; window-centre reading line and frame keep-alive; MIUI power saver; continue-generating; download install phase. Includes the device-run records. |
| `0bacc031b` | cancel + centring | a "cancel generation" chip in the top-right corner of the transcript list stops the current session (generated segments stay, no completion toast); follow stays centred across ASR segment writes: idempotent transcript layout, half-viewport `scrollBy` catch-up, stale-position adapter re-attach, ticker-independent 250 ms follow clock, adapter follow requester, stale-ticker watchdog; reading line moved to 40% of the window. |

## Where to look next

- Behaviour and code anchors: [transcript-handover.md](transcript-handover.md) §1 and §3.
- Controller split, shared `TranscriptSession` and the ASR session lease: `874c4ab02`, described in [ADR 0003](adr/0003-generated-transcript-append-journal-and-session-lease.md).
- Device evidence and known platform limits: handover §5.2 and §7.
- Open review findings: handover §7, ASR bullet.
