"""Record the upstream voice subsystems in docs/01-product.md."""
import pathlib
import sys

P = pathlib.Path(r"E:\Projects\opencode portable\docs\01-product.md")
text = P.read_text(encoding="utf-8", newline="")

OLD_ROW = "| 3 | *(not in v1)* Cloud STT | Best accuracy, but a new provider to configure |"
NEW_ROW = (
    "| 3 | *(opt-in, not v1)* Cloud STT \u2014 **already implemented upstream** | "
    "Best accuracy; five providers are wired into `@opencode/ai` and the server already brokers "
    "realtime voice sessions. It costs one API key, so it cannot be the default |"
)

OLD_TAIL = (
    "**[I]** On a phone without a speech service, detection happens at runtime and the app falls "
    "back to the bundled model."
)
NEW_TAIL = """**What upstream already brings [V].** Two things this plan does not have to build:

- `packages/ai/src/transcription.ts` \u2014 a complete transcription subsystem (OpenAI, Google, Deepgram, AssemblyAI, ElevenLabs) with streaming, word and segment timestamps, and diarization. It is *not* exposed over HTTP yet, so reusing it means adding one protocol group.
- `packages/protocol/src/groups/voice.ts` \u2014 `POST /api/experimental/voice/session`, which brokers a realtime voice session server-side and hands back a WebRTC SDP answer or a websocket token, so the provider key never reaches the client.

**And the shipped product direction is the reverse of dictation.** The **iOS app already treats voice as delegation**: it sends the spoken request to the coding agent as a prompt headed `Voice delegation.`, and this very app already renders those messages specially (`packages/app/src/session/timeline/voice-delegation.ts`). That is a second voice mode worth offering *after* v1 \u2014 the display half already exists, so it is mostly wiring. It is not the v1 mode, because it needs a provider key and v1 promises zero config.

**[I]** On a phone without a speech service, detection happens at runtime and the app falls back to the bundled model."""

for old, new, label in ((OLD_ROW, NEW_ROW, "cloud row"), (OLD_TAIL, NEW_TAIL, "upstream note")):
    n = text.count(old)
    if n != 1:
        sys.exit(f"FAIL {label}: {n} occurrences")
    text = text.replace(old, new, 1)
    print(f"ok  {label}")

P.write_text(text, encoding="utf-8", newline="")
print("01-product.md ->", len(text.encode("utf-8")), "bytes")
