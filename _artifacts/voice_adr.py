"""Rewrite ADR-005 in docs/04-decisions.md to record the upstream voice subsystems.

edit-tool rejects the file (rewritten by an earlier script), so this does the
replacement at byte level with a per-replacement occurrence assertion.
"""
import pathlib
import sys

P = pathlib.Path(r"E:\Projects\opencode portable\docs\04-decisions.md")

OLD_STATUS = (
    "**Status:** Accepted \u2014 **amended.** The earlier version treated the bundled model "
    "as an optional fallback for the phone. That is no longer accurate."
)
NEW_STATUS = (
    "**Status:** Accepted \u2014 **amended twice.** The first amendment made the bundled model "
    "mandatory rather than optional. The second adds what upstream already ships, which changes "
    "the *shape of the work* rather than the decision."
)

OLD_DECISION = """**Decision.** Two paths, one bridge:

| Source | Engine |
|---|---|
| Phone microphone | System `SpeechRecognizer` first, bundled model as fallback |
| **Watch microphone** | **Bundled offline model, always** |

**Consequence.** The bundled model is no longer optional \u2014 it is a hard dependency of the watch feature, which means P2-3 is a prerequisite for P3, not a nicety, and the size budget must include its 30\u201380 MB from the start. In exchange the design stays self-contained: no cloud STT, no provider to configure, and voice keeps working with no signal."""

NEW_DECISION = """**What upstream already ships [V].** Less has to be invented here than the earlier drafts assumed:

| Capability | Where | What it means for us |
|---|---|---|
| Transcription, five providers | `packages/ai/src/transcription.ts`, `protocols/{openai,google,deepgram,assemblyai,elevenlabs}-*` | `generate`/`stream`/`start`/`resume`, word and segment timestamps, diarization |
| Real-time voice sessions | `packages/protocol/src/groups/voice.ts` \u2192 `POST /api/experimental/voice/session` | The server holds the provider key and returns a WebRTC SDP answer or a websocket token |
| A shipped voice *product* | `packages/app/src/session/timeline/voice-delegation.ts` | The **iOS app** sends a spoken request as a prompt headed `Voice delegation.`; this app already renders those |
| Microphone capture in the UI | nothing \u2014 only `camera.ts` calls `getUserMedia`, and only for QR pairing | The mic button is ours to build, in all three clients |
| Transcription over HTTP | nothing \u2014 only `voice.session` is exposed | Reusing cloud transcription means adding one protocol group |

So "voice" is not one thing upstream: **dictation into the composer does not exist, but server-brokered voice does**, with a client that already exists on iOS. The decision below is unchanged; the estimate behind P2-2 is smaller than it looked, and the realtime path is now an option we know exists rather than one we would have had to design.

**Decision.** Three tiers, one bridge:

| Source | Engine | Why |
|---|---|---|
| Phone microphone, offline | System `SpeechRecognizer` first, bundled model as fallback | 0 MB and instant when the device has a speech service |
| **Watch microphone** | **Bundled offline model, always** | The only path that needs no key and no buffer API |
| Optional cloud | The existing `voice.session` route, or a new transcription group | Better accuracy; free to skip |

**Consequence.** The bundled model is still a hard dependency of the watch feature, so P2-3 remains a prerequisite for P3 and the 30\u201380 MB belongs in the size budget from the start. What changes is the claim around it: cloud STT is no longer *excluded*, it is kept **out of the default path**. It costs one key, it is already implemented server-side, and the app is fully usable without it \u2014 which is the property that actually matters for a zero-config install."""

text = P.read_text(encoding="utf-8", newline="")

for old, new, label in (
    (OLD_STATUS, NEW_STATUS, "status"),
    (OLD_DECISION, NEW_DECISION, "decision+consequence"),
):
    n = text.count(old)
    if n != 1:
        sys.exit(f"FAIL {label}: found {n} occurrences, expected 1")
    text = text.replace(old, new, 1)
    print(f"ok  {label}")

P.write_text(text, encoding="utf-8", newline="")
print("written", P.name, len(text.encode("utf-8")), "bytes")
