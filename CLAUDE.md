# CLAUDE.md

Guidance for AI assistants (and humans) working in this repo. Read this first — it
explains how the project fits together and the one gotcha that will otherwise block
your build.

## What this project is

HeyMate is an Android app that replaces the closed vendor app for a pair of BLE +
Wi-Fi camera glasses. It connects to the glasses directly, pulls full-resolution
photos and audio off them, and routes captures through an AI pipeline (Gemini, or a
custom agent endpoint) with live transcription and text-to-speech.

The interesting engineering is in `docs/`:
- `docs/PROTOCOL.md` — reverse-engineered BLE + Wi-Fi Direct transfer protocol.
- `docs/BLOG.md` — the story of how it was reverse-engineered.

## The one thing that will block your build

The glasses are driven by the **manufacturer's proprietary BLE SDK** (an `.aar`),
which is **not in this repo** — it isn't ours to redistribute. Without it, the
project does not compile.

Place the vendor SDK here:

```
glasses-capture/libs/glasses_sdk_20250723_v01.aar
```

⚠️ The filename is currently hardcoded in `glasses-capture/build.gradle`
(`api files('libs/glasses_sdk_20250723_v01.aar')`). If your SDK file has a
different name, either rename the file to match, or update that line in the
build script.

The SDK's Java package is `com.oudmon.*`. Those import statements appear throughout
`app/.../ble/` and `glasses-capture/` — **they cannot be renamed**; the code
compiles against the SDK's real package names. Leave them as-is.

## The two modules

| Module | Type | What it is |
|--------|------|-----------|
| `glasses-capture` | Android **library** (`com.fersaiyan.glassescapture`) | Low-level, UI-free glasses primitives. Owns the vendor `.aar`. Turns BLE button presses into photos, live voice capture/transcription, and TTS. Reusable on its own, independent of the app. |
| `app` | Android **application** (`com.harish.heymate`) | The full product built on top of `glasses-capture`: Compose UI, gallery, Wi-Fi Direct media transfer, and the capture → AI → TTS pipeline. |

**Dependency direction:** `app` depends on `glasses-capture` (never the reverse).
The vendor `.aar` is declared `api` in `glasses-capture` so `app` can drive
scan/connect directly.

### How they connect

The wiring point is `app/.../core/CaptureCoordinator.kt` — "the heart of HeyMate."
It listens to `glasses-capture` events and runs the pipeline:

```
glasses mic button   → live transcription → AI agent → TTS + notification + feed
glasses photo button → BLE photo pull → Wi-Fi Direct full-res download → feed
```

## Key files

| Concern | File |
|---------|------|
| Pipeline orchestration (glasses events → AI → output) | `app/.../core/CaptureCoordinator.kt` |
| BLE connect / scan / telemetry | `app/.../ble/GlassesBle.kt`, `GlassesInfo.kt` |
| Wi-Fi Direct connect + IP resolution | `app/.../wifitransfer/WifiDirectConnection.kt` |
| HTTP media list/download (+ quirk handling) | `app/.../wifitransfer/MediaTransferClient.kt` |
| AI agents | `app/.../agent/GeminiAgentClient.kt`, `HermesAgentClient.kt` |
| User settings (API key, endpoint) | `app/.../data/Prefs.kt`, `ui/screens/SettingsScreen.kt` |
| Standalone capture primitives | `glasses-capture/.../GlassesPhotoCapture.kt`, `GlassesVoiceCapture.kt`, `GlassesTts.kt` |

## Conventions & things to know

- **Language / UI:** Kotlin + Jetpack Compose (Material 3). Single-activity,
  Navigation Compose. JDK 17, `minSdk 26`, `compileSdk 35`.
- **Dependencies:** managed via the version catalog `gradle/libs.versions.toml`.
  Add libraries there, not with inline coordinates.
- **Secrets:** there are none in the source. The Gemini API key and the agent
  endpoint are **entered by the user at runtime** and stored on-device via
  DataStore (`Prefs.kt`). Never hardcode a key.
- **Hermes agent is a stub.** `HermesAgentClient` uses a *provisional* request/
  response format (see the `TODO(HERMES FORMAT)` markers). Only `buildRequestJson`
  and `parseReply` need to change when the real format is known — the rest of the
  pipeline is final.
- **Cleartext HTTP is intentional.** The glasses' media server speaks plain HTTP on
  their Wi-Fi Direct subnet (`192.168.49.x`); this is allowed via
  `res/xml/network_security_config.xml`. Don't "fix" it to HTTPS.
- **Vendor anonymity:** docs and comments intentionally say "the vendor" rather than
  naming the product. Keep it that way in new docs/comments.

## Build & run

```bash
# after placing the vendor .aar (see above)
./gradlew assembleDebug        # build the app
./gradlew installDebug         # install on a connected device
```

Or open in Android Studio and let Gradle sync, then Run.

Runtime setup: launch the app → **Settings** → add your Gemini API key (or a custom
agent endpoint). Real glasses hardware is required to exercise the BLE/Wi-Fi paths;
they can't be meaningfully tested in an emulator.
