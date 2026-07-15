# Glasses — Protocol Reference

Reference for a pair of BLE + Wi-Fi camera glasses. Builds on prior open-source
work by [FerSaiyan](https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK),
with the transfer protocol confirmed against real hardware here.

Everything here was recovered from three sources, marked inline:

- **[SDK]** — in the bundled vendor AAR, confirmed via `javap`/`dexdump`.
- **[RE]** — reverse-engineered from the vendor app (jadx) — **not in the SDK**.
- **[FW]** — firmware behavior / quirk observed on a live unit (packet capture + on-device testing).

> Values were confirmed on one device. Firmware updates could change endpoints or framing — version-gate and log raw frames.

---

## 1. Architecture: two transports, two jobs

The device separates control from bulk data across two independent transports:

| Transport | Role | Where it lives |
|-----------|------|----------------|
| **BLE** (vendor SDK) | Control plane — connect, telemetry, mode triggers | The bundled AAR |
| **Wi-Fi Direct + HTTP** | Data plane — full-resolution media transfer | **Only the vendor app** |

**Key finding:** the SDK's `writeIpToSoc` *looks* like it performs the transfer, but it only hands the glasses an IP and stops. No socket, no HTTP client, no URL exists anywhere in the 368-class AAR. The entire media-transfer stack was recovered from the vendor app.

The AAR is a **generic wearable SDK** reused across the vendor's watches and glasses. Roughly half its classes (heart rate, SpO₂, ECG, sleep, blood pressure, step/sport, watch faces, menstruation, prayer times, AGPS, e-book) are **watch-only and silently no-op on the glasses**. Trust the two runtime capability probes, not the class list:

- `wearFunctionSupport()` → `GlassesTouchSupportRsp` { glassesModel, translationSupport, wearCheckSupport, volumeControl } **[SDK]**
- `DeviceSupportReq` (via `CommandHandle`) → `DeviceSupportFunctionRsp` { supportGesture, supportRingCamera, supportRingMusic, supportRingVideo, supportBlePair, supportTouch, … } **[SDK]**

---

## 2. BLE control channel

All control runs through `LargeDataHandler`, keyed by a one-byte *action*.

### Action bytes [SDK]

| Byte | Action | Use |
|------|--------|-----|
| `0x41` (65) | GLASSES_CONTROL | Multiplexed control channel (see below) |
| `0x42` (66) | GLASSES_BATTERY | `syncBattery()` → percent + charging |
| `0x43` (67) | DEVICE_INFO | firmware / hardware / Wi-Fi-module versions |
| `0x44` (68) | DEVICE_AI_VOICE | wake-word enable |
| `0x46` (70) | DEVICE_WEAR | on-face detection |
| `0xFC` (-4) | OTA_SOC | `writeIpToSoc` — pushes phone's IP (handshake only) |
| `0x51` (81) | VOLUME_CONTROL | music / system / call volumes |

### The `glassesControl` multiplex [SDK structure, RE trigger]

Action `0x41` multiplexes several payloads. Command payloads are `[0x02, subCommand, …]`, and the device **echoes the sub-command back as the response's `dataType`** (read from byte 7 of the frame). Each `GlassModelControlResponse` only fills fields for its own tag — every other getter returns a stale zero.

| Send | `dataType` reply | Carries | Src |
|------|------------------|---------|-----|
| `[02 01 04 01]` | `1`, workType `4` | **Enter Wi-Fi transfer mode** (starts P2P group); `errorCode`≠0 = still processing | **[RE]** |
| `[02 03]` | `3` | P2P IP (bytes 10–13) | [SDK] |
| `[02 04]` | `4` | imageCount, videoCount, recordCount | [SDK] |
| — | `2` | videoAngle, videoDuration | [SDK] |
| — | `6` | recordAudioDuration | [SDK] |

### ⚠️ SDK bug — `getP2pIp()` truncates the last octet [SDK]

`GlassModelControlResponse.getP2pIp()` builds the address from `bytes[10].[11].[12].[12]` — it reads byte 12 **twice** and never reads byte 13. `192.168.1.42` is reported as `192.168.1.1`. Parse the raw frame yourself:

```
ip = "${b[10] & 0xFF}.${b[11] & 0xFF}.${b[12] & 0xFF}.${b[13] & 0xFF}"
```

---

## 3. Wi-Fi media transfer [RE]

**Not in the SDK.** Recovered from the vendor app and confirmed on the wire.

### Flow

1. **BLE trigger** — `glassesControl([02 01 04 01])`. Glasses reply `dataType=1, workType=4` and bring up a **Wi-Fi Direct group**. (This is what the vendor app's loading spinner waits on.)
2. **Join** — standard Android `WifiP2pManager` discovery + connect. Glasses take a DHCP lease on `192.168.49.x` (server observed at `192.168.49.43`).
3. **Find server** — if the phone became group owner, the glasses' client IP isn't exposed by the framework and `/proc/net/arp` is unreadable to sandboxed apps → probe port 80 across the small DHCP range.
4. **List** — `GET /files/media.config` → newline-separated filenames, one per line.
5. **Download** — `GET /files/<name>` → full-resolution bytes.

### Exact request (captured on the wire)

```http
GET /files/media.config HTTP/1.1
Host: 192.168.49.43
Connection: Keep-Alive
Accept-Encoding: gzip
User-Agent: okhttp/4.9.2
```
```http
HTTP/1.1 200 OK
Content-Type: text/plain
Content-Length: 22

20260710200000875.mp4
```

- **Endpoint:** `http://<ip>:80`, files under `/files/`. Plain HTTP.
- **Auth:** none. The `X-Signature`/`X-Timestamp` HMAC signing in the app is for its **cloud** API only — a red herring; the local server needs no auth.
- **Required header:** a recognizable `User-Agent: okhttp/…` — the server 400s requests without it.
- **Manifest:** photos `<ts>.jpg`, videos `<ts>.mp4`. Observed to hold the *latest* capture, not full history — enumerate as you go.

---

## 4. Wire-level gotchas [FW]

These cost real debugging time:

- **No connection reuse.** The server closes the socket after every response (`Connection: close`); any pooled connection dies with `unexpected end of stream`. Disable connection pooling and send `Connection: close`.
- **Bare TCP probes wedge the server.** It's effectively single-connection — opening/closing a socket without a full HTTP request can jam it. Probe reachability with a real `GET`, not a bare connect.
- **Video-finalize hang.** Right after recording, `media.config` **times out** while the device encodes (the vendor loader waits on exactly this). Photos are unaffected. Retry the manifest over a long window; a persistent `404` instead means the device is genuinely empty.
- **Cleartext + network binding.** Android blocks cleartext HTTP by default (needs `network-security-config`). With mobile data also up, sockets escape onto cellular unless bound to the Wi-Fi Direct `Network`.
- **Group-owner role is non-deterministic.** Prefer intent 0 (glasses as owner) but handle both; resolve the glasses' IP by port-80 probe when the phone is owner.

---

## 5. Other device quirks [FW]

- **BLE media count is flaky.** The `[02 04]` count often returns 0 or doesn't answer. Don't build "new items" logic on it alone — the authoritative source is the actual `media.config` scan.
- **Full-res over BLE is dead.** `AlbumHandle` (the SDK's BLE file browser) never answers on these glasses. BLE only yields low-res thumbnails via `getPictureThumbnails`. Wi-Fi is the only full-res path.
- **MIUI trashes new albums.** Saving to a fresh `DCIM/<app>/` album gets auto-trashed by MIUI's gallery cloud-sync. Save under `Pictures/` instead; the OEM app uses its own `DCIM/` subfolder.

---

## Implementation map

| Concern | File |
|---------|------|
| BLE trigger + Wi-Fi Direct connect + IP resolution | `app/.../wifitransfer/WifiDirectConnection.kt` |
| HTTP client (list/download, quirk handling) | `app/.../wifitransfer/MediaTransferClient.kt` |
| Orchestration | `app/.../wifitransfer/WifiImportCoordinator.kt` |
| BLE telemetry (battery, counts, versions) | `app/.../ble/GlassesInfo.kt` |
