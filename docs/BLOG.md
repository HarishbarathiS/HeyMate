# Post 1 — How I reverse-engineered my smart glasses and built my own app

I bought a pair of AI camera glasses expecting a wearable assistant. What I got was a toy with a lobotomy. The built-in AI was weak and outdated, it couldn't touch anything real-time, and every "smart" feature routed through the vendor's slow, closed app. I'd point the glasses at something, ask a question, and get an answer that was either wrong or a year stale.

The annoying part was that the *hardware* was fine. A camera on your face, a mic, a button — that's a genuinely useful capture device. The problem was entirely the software wrapped around it. Once I saw it that way, the goal got clear: I didn't need to fix their app, I needed to get underneath it. If I could own the data layer — pull the photos and audio off the glasses myself — I could plug in whatever brain I wanted. A current model instead of their stale one. Live data. My own automations.

I'm a curious engineer, not a reverse-engineering specialist, and I'd never taken apart a closed device like this before. What made it feasible was working the whole thing alongside an AI — not to do it for me, but as a tireless pair-programmer that could read decompiled bytecode faster than I could, suggest the next tool when I got stuck, and decode byte layouts I'd have squinted at for an hour. I brought the intent and the judgment; it brought breadth and patience. That combination is really the story here.

## The SDK looked promising and wasn't

The glasses shipped with a Bluetooth SDK, so I started there. 368 classes — encouraging, until I read the names: heart rate, blood oxygen, ECG, sleep, menstrual cycle, prayer times. On a pair of glasses. Clearly a generic smartwatch SDK the vendor reused across their lineup, and most of it does nothing on this hardware.

One method looked like exactly what I needed — `writeIpToSoc`, which sounded like "send the glasses an IP and start transferring." I built around it. Nothing came back. So I had the AI help me sweep the whole library for an HTTP call, a socket, any URL at all. Between us we came up empty. There was no file-transfer code in the SDK — whatever moved the media lived in the vendor's app, not the library they handed developers. So the target shifted: take the app apart.

## Reading the decompiled app

I pulled the vendor APK off the phone and decompiled it with jadx. Decompiled Android is a wall of ugly, machine-generated code, and this is where having an AI in the loop earned its keep — I'd point it at a class and we'd work out together what it was doing, which threads to pull, which to ignore. The thing that cracked it open wasn't clever logic, it was the strings. Developers leave debug log lines right in the shipped app:

```
"[Music P2P] HTTP Server started at "
"[Music P2P] isGroupOwner localIp="
"[Music P2P] transfer complete"
```

That reframed everything. The heavy lifting wasn't Bluetooth — it was **Wi-Fi Direct**. The glasses stand up their own little network, run an HTTP server on it, and the phone pulls files over that. Bluetooth was just the doorbell that tells the glasses to bring the network up. Tracing the photo path, we found the method that starts it and the four-byte Bluetooth command it fires first:

```
[ 0x02, 0x01, 0x04, 0x01 ]   // "start the transfer network"
```

## The part where nothing worked

Knowing the shape of the answer and actually getting it are very different. I sent that command and waited. Silence. Sent variations, dozens of times. More silence.

Before anything worked, the tally of dead ends was:

- **11** Bluetooth commands, guessed and sent, all met with silence
- **4** HTTP paths I was sure were right, all returning `400 Invalid Request`
- **1** SDK bug that was actively lying to me about the IP

That last one was mean. The library's own function for reading the glasses' IP built the address from the wrong bytes — it read one byte twice and skipped the next entirely. It told me the glasses were at `192.168.1.1` when they were actually at `192.168.1.42`. Knocking on the wrong door, blaming the lock. We only caught it by decoding the raw frame by hand and comparing it against what the SDK claimed — the AI walked the byte offsets with me until the mismatch was obvious. Once I saw the double-read, I stopped trusting the SDK's parse and did it myself.

I also burned an evening on a rabbit hole that was nothing. I'd found HMAC signing code in the app — timestamps, a secret key, the works — and convinced myself the glasses' server needed a signature on every request. It didn't. That signing was for the app talking to the vendor's *cloud*, not the glasses on my desk. Good reminder that code existing in an app doesn't mean it's on the path you care about.

## Watching it on the wire

When guessing kept failing, the AI's suggestion was the one that broke the logjam: stop reading the code, watch the actual traffic. So I put a no-root packet capture on the phone, filtered to the vendor app, and ran a real transfer while recording every byte. And there it was, in plaintext — the exact request I'd been failing to reconstruct:

```
GET /files/media.config HTTP/1.1
Host: 192.168.49.43
Connection: Keep-Alive
User-Agent: okhttp/4.9.2

→ 200 OK
20260710200000875.mp4
```

Two things I'd had wrong jumped out. The path wasn't the deep `/storage/...` route I'd inferred from the decompiler — it was a clean `/files/media.config`. And the server was picky about `User-Agent`: leave it off and it returns `400`. My hand-typed requests had no User-Agent. The app's did. That one header was the whole difference between "invalid request" and "here are your files."

So the full protocol, finally:

1. Bluetooth trigger `[02 01 04 01]` → glasses bring up a Wi-Fi Direct network
2. `GET /files/media.config` → the file list
3. `GET /files/<name>` → each full-resolution file

Plain HTTP, no auth, the right path with the right header.

## The last mile is always the weird part

The first `200 OK` felt like the finish line. It wasn't — it was a second batch of problems, the kind you only hit on real hardware. And this is where the AI-as-pair-programmer really paid off, because each one was a specific, obscure failure and we could chew through them one at a time:

- **The server hangs up after every response.** It closes the socket each time, so any client that reuses the connection dies with `unexpected end of stream`. Force a fresh connection per request.
- **Checking it too gently breaks it.** Just opening a bare TCP connection to test reachability — no full request — jams the little server. You knock with a complete request or not at all.
- **Video needs a second.** Ask for the file list right after recording and the request hangs — the glasses are still encoding. That unexplained loading spinner in the vendor app is exactly what it's waiting on. Fix was patience and a retry.
- **The phone works against you.** Android quietly sends requests over mobile data instead of the glasses' Wi-Fi unless you pin them, and blocks plain HTTP until you whitelist the address.

Each was an hour of confusion followed by a small click of it making sense. Then it worked — a full-resolution photo, 773 KB, pulled straight off the glasses into my own app. The Bluetooth thumbnail the vendor path had been giving me was about 18 KB.

## The app

I wrapped all of it into my own Android app. The reverse-engineered transfer lives as a small, self-contained core — connect over Wi-Fi Direct, list the files, download them — with a gallery on top, and the whole capture-to-AI pipeline behind it. It does everything the vendor app did, minus the parts that annoyed me, plus the hooks I actually wanted: full-resolution import, my own model behind the camera, room to bolt on automations.

That last part is a whole post of its own.

## What actually surprised me

The thing I keep coming back to is how wrong the decompiler let me be. Reading the decompiled app *felt* like understanding the system, and I was maybe 90% right — the dangerous kind of wrong, because the missing 10% was invisible from the source and cost me days. The wire settled it in about thirty seconds. The lesson stuck: the code tells you the shape of a thing, the wire tells you what it actually does.

The other shift was how I started treating failures. Early on, every silent command and every `400` felt like a wall. Somewhere in the middle it flipped and I began reading them as the device telling me something specific — a `400` that never changes is "your request is malformed," not "no"; a timeout only after recording video is "I'm busy," not "I'm broken." Working with the AI reinforced that, honestly, because its instinct was always to form a hypothesis about *why* something failed rather than just retry it.

And I came away with a real sense of how this kind of work changes when you've got a capable AI beside you. I'm not a reverse-engineering expert. A few years ago this project would have been out of reach for me on a weekend budget — the decompiled-bytecode reading alone would have burned all my patience. Having something that could keep pace with the tedious parts, suggest the right tool at the right moment, and decode byte layouts on demand meant I got to stay in the part I'm actually good at: staying curious, forming the next question, deciding what mattered. The curiosity and the goal were mine. The reach was ours.

These glasses will never get official docs. But now I can build on them — and the assistant I actually wanted turned out to be a data layer, and a good pair-programmer, away.

---

# Post 2 — Turning dumb-smart glasses into my actual input device

The first post was about breaking in. This one is about what breaking in was *for*.

Once I owned the data layer — photos and audio flowing into my own app instead of the vendor's — the glasses stopped being a gadget and started being a sensor. A camera and a mic on my face, always ready, that I can point at the world and pull structured data out of. The intelligence isn't in the glasses anymore. I bring that.

That reframe is the whole thing. A "smart" device that locks you into its brain ages badly — its model gets stale, its features are frozen, and you're stuck with whatever the vendor decided last year. A dumb device with an open data layer ages *up*: swap in a better model whenever one ships, feed it live data, and wire it into whatever systems already run your life. The glasses become the least interesting part, which is exactly what you want from an input device.

## What "input device" actually means here

A keyboard is an input device. A mouse is an input device. They're good because they're fast, always there, and get out of the way. The glasses are the same idea pointed at the physical world: a zero-friction way to capture what I'm seeing and saying, as a first-class input to my own software.

The pattern behind every automation I care about is the same three steps:

1. **Capture** — press a button, take a photo or a short voice note. No app, no phone out of the pocket.
2. **Understand** — the capture goes to a current model that reads it: transcribes the audio, reads the text in the image, describes the scene.
3. **Route** — the structured result gets sent somewhere useful. That's the part the vendor never let me touch, and it's the whole point.

Everything below is just that pattern with a different destination on step 3.

## The automations I'm building

**Capture → notes.** Point at a whiteboard, a page of a book, a slide, a sign — and get clean text filed into my notes, not a photo I'll never look at again. The friction of "photograph it, then later transcribe it, then later file it" is exactly the friction that means it never happens. Collapsing all three into one button press is the difference between a habit and an intention.

**Voice thoughts → filed, not lost.** The classic problem: you have a good thought walking somewhere, and by the time you're at a keyboard it's gone. Speak it, and it gets transcribed, cleaned up into something coherent, and dropped where it belongs — a task if it's a task, a note if it's a note, a reminder if it's time-bound. The glasses are the fastest path from "thought" to "captured" I've found, because there's nothing to take out and unlock.

**Ask about what I'm seeing, with a model that actually knows things.** This is the one the vendor got most wrong. Point, ask, and get an answer from a current model with real-time data instead of the frozen thing they shipped. "What is this," "translate this," "is this the right part" — the questions are obvious; what was missing was a brain worth asking. Now I choose the brain.

**Log the boring stuff automatically.** Receipts, a shelf, a piece of equipment — captured and turned into a row in a sheet or a log entry, no typing. The automations that actually earn their keep are usually the unglamorous ones: the data entry you'd never do by hand but happily do if it's one button.

## Why glasses, specifically

I could do most of this with my phone. The reason I don't is friction, and friction is everything for a capture device. A phone is a multi-step ritual — take it out, unlock it, find the app, frame the shot, tap. Each step is a place the intention dies. Glasses are one button, hands-free, already pointed where I'm looking. That gap sounds small and isn't: it's the difference between a tool you *mean* to use and one you actually do.

The honest limits: the glasses are still a modest camera and mic, the battery is what it is, and everything downstream depends on connectivity to whatever model I'm routing to. This isn't a always-on ambient computer. It's a fast, reliable, hands-free *capture* device — and once I stopped expecting it to be smart on its own and started treating it as an input to systems that are, it became genuinely useful.

## Where this goes

The interesting future isn't smarter glasses. It's the opposite — dumber, cheaper, more reliable sensors, with the intelligence living somewhere you control and can upgrade freely. Owning the data layer is what makes that possible. The vendor sold me a closed appliance; a week of reverse engineering turned it into an open input to my own stack.

That's the version I actually wanted to buy. I just had to build it.
