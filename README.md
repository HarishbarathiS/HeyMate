<p align="center">
  <img src="docs/icon.png" alt="HeyMate icon" width="480" />
</p>

<h1 align="center">HeyMate</h1>

<p align="center"><b>A more capable, open alternative to the HeyCyan app for AI camera glasses.</b></p>

HeyMate does everything the HeyCyan app does — and more. Instead of routing
through the vendor's closed app, it connects to the glasses directly, downloads
your full-size photos and audio, and sends them to an AI you pick. Point the
glasses at something, capture it, and ask a question — you choose which AI answers.

**What HeyMate adds over the HeyCyan app:**
- **Your own AI model** — plug in Gemini (or your own AI server) instead of the frozen built-in one
- **Full-resolution import** — pull the real photos and videos off the glasses, not the tiny low-res versions
- **Live transcription + text-to-speech** — ask out loud, hear the answer back through the glasses
- **Room for your own automations** — the capture → AI → action pipeline is yours to extend

> HeyMate is an independent, community-built project. It is not affiliated with,
> authorized by, or endorsed by HeyCyan or any glasses manufacturer. Use it with
> your own hardware and at your own risk.

## What it does

**With the glasses**
- Connects to the glasses over Bluetooth when you press the button
- Downloads your full-size photos and videos over Wi-Fi (not the tiny low-quality ones)
- Saves them straight to your phone

**AI features**
- Send a photo or your voice to an AI and get an answer back
- Works with **Gemini** (add your own key), or your own AI server
- Reads answers out loud through the glasses
- Ask about what you're looking at — translate it, identify it, or just ask a question

**The app**
- Simple screens: Home, Devices, Gallery, Import, Settings, and more
- Play videos and see thumbnails inside the app
- Save your API key and settings on your phone

## Setup

You need [Android Studio](https://developer.android.com/studio) and an Android
device (API 26+).

1. Clone the repo.
2. **Add the vendor BLE SDK.** This app talks to the glasses through the
   manufacturer's proprietary Bluetooth SDK, which is **not included** in this
   repository (it isn't ours to redistribute). Place the SDK file here:

   ```
   glasses-capture/libs/glasses_sdk_20250723_v01.aar
   ```

   The filename must match the one referenced in `glasses-capture/build.gradle`.
   If your SDK file has a different name, either rename it to the above or update
   that line in the build script.

   Without it, the project will not build. See `docs/PROTOCOL.md` for how the SDK
   is used.
3. Open the project in Android Studio and let Gradle sync.
4. Build and run on your device.
5. In the app's **Settings**, add your Gemini API key (or your own AI server
   endpoint).

## How it works

The interesting part — how the glasses' Bluetooth + Wi-Fi transfer protocol was
reverse-engineered — is written up in:

- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — the technical protocol reference
- [`docs/BLOG.md`](docs/BLOG.md) — the story of building it

## Project layout

| Module | What it is |
|--------|-----------|
| `app` | The main Android app (UI, gallery, AI pipeline, Wi-Fi transfer) |
| `glasses-capture` | Standalone module: BLE button → photo / live transcription / TTS |

## Note

This is a personal project built by reverse-engineering a consumer device. It's
shared for learning and tinkering. It is not affiliated with or endorsed by any
glasses manufacturer. Use it with your own hardware and at your own risk.

## License

[MIT](LICENSE)
