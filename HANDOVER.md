# Smile's Radio Mod - Handover Notes

**English** | [简体中文](HANDOVER.zh-CN.md)

> Last updated: 2026-11-26 21:10 (**Round 23** complete; includes the Round 19 **network-radio architecture overhaul: client-direct streaming**)
> Project path: `C:\Projects\Smile's Radio mod`
> Build artifact: `build/libs/radio-smile-1.0.0.jar` (about **355 KB**, BUILD SUCCESSFUL)
> Version control: git initialised, **one commit per round of fixes**
> User guide: the full documentation for server owners/players is in [README.md](README.md) (this file is the developer handover)

## Reading Guide (start here in a new session)

| What you want to do | Read first |
|---|---|
| Learn what the project is and how to configure it | [README.md](README.md) |
| Get up to speed on changing code quickly | Section 10 in this document (resuming work quickly) |
| **Must read before touching code** | "7. Critical Pitfalls" + section 12 (reminders for the next session) |
| Look up the historical root cause of a bug | Section 8 (Current State), ordered by round (**21 rounds, A to AE**; later is newer) |
| Find where config lives, directory structure | Section 3 in this document, or the README |

> ⚠️ **The easiest pitfall to fall into**: in this codebase **most serious bugs come from "one function/field carrying two meanings"**
> (see the table in section 12; it has already happened **10 times**, and the tenth mistook "gain 1.0 (a legal
> business value)" for "no gain (a cleanup signal)", wiping the local stations' `outputGain` to 100%).
> Before changing any function, ask: **how many callers with different semantics does it have?**

---

## 1. Project Overview

A Fabric 1.20.1 mod that adds a "radio" block which plays broadcasts. It supports a server-configured
station list, player-defined internet stations, synchronised playback for multiple players, and spatialized audio.

| Item | Value |
|---|---|
| mod id | `radio-smile` |
| block/item id | `radio-smile:radio` |
| Minecraft | 1.20.1 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.92.12+1.20.1 |
| Gradle | 9.8.2 (was once upgraded to 9.9.1 by mistake; reverted) |
| Prerequisite library | smile_libbox 1.0.26+1.20.1 (mavenLocal) |
| Optional dependency | ModMenu 7.2.2 |
| Bundled dependency | JLayer 1.0.1 (MP3 decoding, included in the jar) |
| External dependency | **ffmpeg** (optional, used to transcode OGG to MP3 during prewarm) |

---

## 2. Block Behaviour

`RadioBlock` extends `BaseEntityBlock`, and its construction follows the vanilla torch:

- `.noCollission()` - no collision box, casts no block shadow
- `.noOcclusion()` - does not block light (placing it on grass does not turn the grass into dirt)
- `.pushReaction(PushReaction.DESTROY)` - pistons break it instead of pushing it
- `canSurvive` / `updateShape` - requires a full solid face below for support; drops when it loses support
- Sand falling on top of it becomes an item drop (no full collision face, same as a torch)
- `SoundType.STONE`
- Horizontal facing state `facing` (north/east/south/west); faces the player when placed

`RadioBlockEntity` stores in NBT:
- `station` (current station id)
- `volume` (0.0~1.0, volume for this block alone)

---

## 3. Configuration (server side)

> **Path has moved** (round 10): old `<save>/serverconfig/radio-smile/` -> new **`<game directory>/config/radio-smile/`**
> The old location is migrated automatically (see section T). Reason for the move: the old path was isolated per save, so switching saves meant reconfiguring tens of MB of audio.

```
config/radio-smile/
├── stations.json        station list (+ top-level ffmpeg path)
├── audio/               source audio / folder playlists / .txt redirect lists
├── icons/               station icons
└── cache/               safe to delete, rebuilt automatically
    ├── manifest.json    audio hash manifest (quickHash: first 64KB + size)
    ├── baked/           transcoded MP3s (ffmpeg output, prewarmed on startup)
    └── remote/          internet audio cache + .proof checksum bundles
```

### stations.json format

```json
{
  "ffmpeg": "",
  "stations": [
    { "id": "tiehen", "name": "Tiehen Radio #02", "icon": "fm907.png",
      "file": "qtfm_tiehen.ogg", "mode": "block", "rate": "FM105.7" }
  ]
}
```

**Top-level fields** (outside the station list):

| Field | Description |
|---|---|
| `ffmpeg` | Path to the ffmpeg executable. Empty = use `ffmpeg` from PATH (section X) |

**Station fields**:

| Field | Description |
|---|---|
| `id` | Unique identifier. **`example` is reserved; the entry with that id is not loaded** |
| `name` | Display name |
| `icon` | Icon file name (place it in icons/) |
| `file` | Single file / folder / **`.txt` redirect list** / `http(s)` live stream |
| `mode` | `block`/`single`/`false`/`0` = repeat one track; `sequence`/`order`/`loop`/`true`/`1` = loop the list; `random`/`shuffle` = shuffle (relaxed in round 12) |
| `rate` | Frequency/preset shown on the LCD and at the right of the list (e.g. `FM90.7`). If omitted, `NETWORK` is shown (added in round 6) |
| `seed` | Optional, used by shuffle mode. If omitted it is derived from the id (so every client agrees) |

**A sample entry is generated automatically on first start** (id=example, not loaded), with `_note_*` fields explaining
each option. The server owner only has to change the id to enable it.

**Config validation** (round 6): stations whose audio is unavailable are **hidden from the list**, but if a player's
block still records one it shows `NOSIGNAL` plus the noise floor - see section P.

### txt redirect playlists (round 10)

Point `file` at a `.txt`/`.list`/`.m3u`/`.m3u8` file to mix local and internet sources:

```text
false                 # first line: shuffle switch (true/false, may be omitted)
song_a.mp3
folder/song_b.ogg
https://example.com/song.mp3
```

- **Playback order = top to bottom in the file**
- Internet addresses are downloaded to `cache/remote/`, transcoded to MP3, then played (see sections T/U)
- Live streams (no `Content-Length`) are **commented out automatically**, with the reason noted

---

## 4. GUI

`RadioScreen` (client):

| Region | Content |
|---|---|
| Top | Search box |
| Middle | Scrollable station list (with icons, selected entry highlighted) |
| Right | Volume slider (**5% per step**, round 13) |
| Bottom | Exit / Scan / Confirm |

Interaction:
- Single click selects; **clicking the selected entry again deselects it** (leaves it empty)
- The mouse wheel moves the selection, the up/down arrow keys move it, Enter confirms
- After confirming, the screen closes automatically and streaming starts
- When the screen opens, the block's own station is highlighted by default (nothing highlighted if it has none)

### ModMenu config screen

Built on smile_libbox's `ConfigSpec`; numeric options use `page.number()` (boxOnly, a plain input box):

| Option | Default | Description |
|---|---|---|
| `enableStreaming` | true | Whether to receive the audio stream |
| `maxBandwidthKbps` | 64 | Desired bandwidth |
| `maxChunkBytes` | 8192 | Maximum size of a single chunk |
| `bufferMillis` | 1000 | Buffer duration |
| `showNetworkStats` | false | Show network status |

Saved to `run/config/radio-smile-client.json`.

---

## 5. Network Protocol

Channels defined by `RadioNetworking`:

**C2S (client to server)**
| Channel | Payload |
|---|---|
| `c2s_request_stations` | empty |
| `c2s_select_station` | stationId + BlockPos |
| `c2s_request_icon` | stationId |
| `c2s_audio_subscribe` | BlockPos + stationId + bwKbps + maxChunk |
| `c2s_audio_unsubscribe` | BlockPos |
| `c2s_set_volume` | BlockPos + volume |

**S2C (server to client)**
| Channel | Payload |
|---|---|
| `s2c_stations` | epoch + JSON list |
| `s2c_select_ack` | stationId + hash + epoch + volume + formatId + skipMillis |
| `s2c_icon` | stationId + byte[] (downscaled to 32x32 server-side, section Z) |
| `s2c_audio_chunk` | BlockPos + stationId + trackIndex + sessionId + formatId + byte[] |
| `s2c_audio_status` | BlockPos + statusCode |
| `s2c_reloaded` | empty (config reload notice; client plays tuning noise + declares a transition silence window) |
| `s2c_audio_silence` | BlockPos + stationId + trackIndex + millis (**empty-volume packet**: declares a track-change preload silence window, see AC) |
| `s2c_output_gain` | BlockPos + stationId + gain (**gain with channel ownership**, see AE; sent with the subscription OK reply for internet radios) |

`AudioStatus`: 0=OK, 1=UNKNOWN_STATION, 2=NO_AUDIO, 3=TOO_FAR, 4=UNSUPPORTED, 5=STOPPED, 6=TIMEOUT (408, **determined locally by the client**; the server never announces it)

---

## 6. Audio Architecture

### Data flow (after the Round 19 overhaul)

```
Custom stations (server-pushed):
FileAudioSource  ──┐
  (local audio/MP3) │
                   ├→ AudioSubscription ──S2C_AUDIO_CHUNK──→ ClientAudioPlayer
PlaylistAudioSource┘   (reader thread+pacing)                 (AudioIngest thread)
                                                               │
Internet radio (client-direct, since Round 19):                ↓
Upstream HTTP stream ──→ client StreamFeed puller thread    Mp3StreamDecoder (JLayer)
                       (reuses StreamAudioSource:              │
                         ICY strip + exponential-backoff        ↓ PCM
                         reconnect)                     PcmSink (mixer)
                       ──→ handleUpstreamChunk ──────────────→ │
                                                               ↓
                                                    SourceDataLine 48kHz/2ch/16bit
```

> For internet radios the server only maintains a **subscription state machine**
> (`STREAMING_SUBS`: playing / walked away / block removed); the audio bytes never pass
> through the server (see AC, which supersedes the server-redistribution design in Z.3).

### Server-side core classes

| Class | Responsibility |
|---|---|
| `RadioStationRegistry` | Config loading, `isPlayable` validation, quickHash cache validation, **prewarm transcoding** (in the same pass), automatic migration from the old location |
| `AudioTranscoder` | Prewarms to MP3 by quickHash (128kbps); **the ffmpeg path is configurable** (section X) |
| `AudioSource` | Audio source interface (read/durationMillis/format/skipMillis/positionMillis/seekTo/totalBytes) |
| `FileAudioSource` | Local file; MP3 can be seeked by frame |
| `StreamAudioSource` | Internet live-stream client, including ICY metadata stripping; **automatic reconnect with exponential backoff 1s→2s→4s→8s cap, unlimited retries** (restored and improved in Round 19; the "no reconnect" policy in section O is superseded); **reused directly by the client StreamFeed** |
| `RemoteAudioCache` | Internet audio download, `.proof` checksum bundles, falls back to the cache when the link drops (sections T/U) |
| `TxtPlaylist` | Parsing of txt redirect lists, shuffle switch, tolerance for BOM/separators |
| `PlaylistTimeline` | Round-based playback timeline; **accepts MP3 only** (section V) |
| `AudioSubscription` | Subscription state + reader thread + **closed-loop pacing** (the anchor accumulates over real duration, section W); used by custom stations only |
| `AudioStreamManager` | Custom-station subscription management and push thread; for internet radios only registers the **subscription state machine** (sends **gain 1.0 along with the subscription OK reply**, section AD); per-tick checks for walked-away/removed; **the heartbeat mechanism has been removed entirely** (section AC) |

### Client-side core classes

| Class | Responsibility |
|---|---|
| `ClientAudioPlayer` | Maintains a playback stream per block, consumed by the AudioIngest thread; contains the `StreamFeed` puller thread (internet radios connect to the upstream directly; 4s with no data counts as stalled) |
| `Mp3StreamDecoder` | Streaming MP3 decoding with JLayer (**the only decode path**; OggDecoder has been deleted) |
| `PcmSink` | **Single sound-card mixer**, fixed 48kHz/2ch, device kept open; includes tuning noise, network noise floor, jitter buffer, transition silence window, fault latch |
| `TuningNoise` | Station-change noise (**triggered the instant you pick a station**, HOLD 0.25 at program level, follows the silence window; finalised in AD) |
| `NetworkNoise` | Weak-network hiss (PEAK 0.12 at program level, attack 250ms / release 1.5s, latched at full level during a fault) |
| `RadioBlockEntityRenderer` | LCD showing rate/NOSIGNAL, blinking red light (sections G/J) |
| `AutoSubscriber` | Scans a 52-block radius every 10 ticks, subscribes/unsubscribes automatically; starts the puller only after the subscription replies OK |
| `BgmSuppressor` | Suppresses the vanilla BGM while playing |
| `AudioLifecycleHooks` | Releases the sound card when the connection drops; requests the station list on JOIN |
| `VanillaVolumeTracker` | Tracks BLOCKS + MASTER volume on the main thread |

### Volume and spatialization

Final gain chain:
```
per-block volume × vanilla BLOCKS × vanilla MASTER × distance attenuation × stream gain × left/right pan
```

Spatialization:
1. Stereo is downmixed to a **mono point source**
2. **Distance attenuation**: full volume at 4 blocks or less, linear falloff from 4 to 48 blocks, silent at 48 blocks and beyond
3. **Constant-power panning**: mapped back to the left/right channels by azimuth (total power is always 1)

**Audible range is 48 blocks** (similar to a jukebox).

---

## 7. Critical Pitfalls (must be followed)

Ordered by importance; every one of these was actually hit:

### 1. OGG was removed from the mod entirely (do not add it back)
Vorbis MDCT overlap-add depends on continuous state from the preceding pages, and a jump in page sequence raises
`Failed to read Ogg file`. **Any scheme that "starts decoding OGG from the middle" is unworkable.**

The second point matters even more: the client ingest queue uses a **drop-from-the-head** policy
(`ClientAudioPlayer.enqueue` calls `poll()` to discard the oldest entry when full),
and with OGG **losing any single chunk means permanent loss of sync**; MP3 frames are self-contained, so JLayer can
rescan forward for the sync word.

-> So there is **no OGG decode path left anywhere in the mod** (removed 2026-11-23):
the three classes `OggDuration` / `OggHeader` / `OggDecoder` have been deleted,
`AudioFormat` has only `MP3` / `UNKNOWN`, and `AudioDuration` only does a frame-by-frame MP3 scan.

**This does not mean server owners cannot use .ogg**: every source file is transcoded to MP3 by ffmpeg in the prewarm
stage before it is streamed. The only criterion for "can I use it" is **whether ffmpeg can transcode it**, regardless
of extension (the whitelist is in `AudioTranscoder.SOURCE_EXTENSIONS`, 14 formats, shared by both the single-file and
folder paths).

### 2. MP3 frames can be decoded from any position
This is why internet radio has always worked. Measured: starting at frame 50, 100 frames decode in a row.

### 3. The format must be sent by the server
Early clients guessed the format from the first chunk's bytes; after transcoding/seeking the first chunk is not at the
file header, so **a wrong guess is guaranteed**.

### 4. JLayer's buffer is reused
`SampleBuffer.getBuffer()` returns the internal buffer, and decoding the next frame overwrites it.
**You must copy it** before handing it to the mixer, otherwise the audio order is completely scrambled.

### 5. Never seek during playback
Seeking **discards outright** all audio corresponding to the offset (measured: 13 seconds lost in one go).
The correct approach: position once at subscription time, then **play strictly sequentially**, never jump mid-way.

### 6. File I/O must be thread-safe
Both `FileAudioSource.read()` and `seekTo()` must be `synchronized`,
otherwise the reader thread and the main thread fight over the file pointer, resulting in no sound or noise.

### 7. Buffer overflow must not be cut in half
`compact(len/2)` cuts the audio off in the middle and scrambles it.
You must **drop from the head on whole-frame boundaries**.

### 8. MP3 duration must be parsed by us
Neither the JDK nor vanilla provides usable MP3 duration parsing, which makes the whole positioning logic fail.
`AudioDuration` solves it with a frame-by-frame scan (error <50ms, 48ms for 13.43 million bytes).

### 9. Pan right vector direction
`right = (-forwardZ, forwardX)`. Writing it as `(forwardZ, -forwardX)` flips left and right.
(In MC +X = east, +Z = south, yaw=0 faces south, and west is then on your right-hand side)

### 10. Packets must be sent back on the main thread
`ServerPlayNetworking.send()` called from a thread of your own making is dropped.
You must use `server.execute(() -> {...})`.

### 11. Keep the mixer lock scope small
Doing all the mixing inside the lock fights with the GUI thread and the decode thread and starves the sound card.
The lock should cover only the data copy and the mix; leave the smoothing outside the lock.

### 12. The texture directory must be textures/block/
The vanilla `blocks.json` atlas only scans `block/` and `item/`; the plural `blocks/` never makes it into the atlas.

### 13. One function must not carry two meanings (the culprit behind this round's repeated pitfalls)
Three major outages in this project all came from this; see **section 12**. Before changing any function, confirm:
**is it shared by callers with different semantics?** If so, split the entry points; do not add ifs inside the function.

### 14. Effect sounds must not go into the audio source buffer
We tried premix and hit three pitfalls in a row (reserve fade misjudged, `any` misjudged, `+=` accumulation causing
volume growth). See **section 8 B**. Effect sounds use a separate overlay layer, but **reuse the main chain's gain/pan
functions**.

---

## 8. Current State

### Round 1 verified passing
- ✅ Build succeeds, jar is about 303 KB (latest)
- ✅ Block registration, item, creative tab
- ✅ Config loading, hash validation, sample config generated automatically
- ✅ Natural-order playlist, fixed-seed shuffle
- ✅ Internet station (MP3 stream) playback
- ✅ Local audio transcode prewarm
- ✅ Left/right channel orientation correct (verified numerically)
- ✅ Volume under control (per-block volume)
- ✅ Playback stops after deselecting
- ✅ Playback stops when the block is broken
- ✅ BGM suppression
- ✅ Automatic resubscription (judged by the actual playback state)

### Fixes this round (2026-11-23)
Three problems the user hit in real testing; all root causes were located and reproduced with real audio data:

| Symptom | Root cause | Fix |
|---|---|---|
| When a station loads: "sound suddenly appears -> silence for a few seconds -> the main stream finally starts" | `AudioSubscription.markSent()` used `source.positionMillis()` (the reader thread's **read-ahead** position) as the pacing anchor. Before the first packet the reader thread had already filled the queue to 64KB, about 4 seconds, pushing the anchor straight to 4263ms, so nothing more was sent for 4 seconds after the first packet | The anchor now uses "milliseconds sent" derived from `sentBytesFromStart`, and the first packet backdates `streamStartAt`; also fixed `updateBitrateEstimate` overwriting the bitrate with the read-ahead rate (16000 -> 106666 B/s) (`bitrateFixed`) |
| Delay when the sound direction/pan switches automatically | The device buffer used `bufferMillis` (1000ms) for jitter resistance, so the sound card held audio that already had its pan applied; once audio is in the device the pan cannot be changed | Device buffer pinned to 150ms; the jitter buffer moved down into `SourceBuffer` **before** the mix (output starts only once `primed` has accumulated enough); `SMOOTH` 0.39 -> 0.55. Pan latency 1.05s -> about 0.22s |
| The playlist does not restart, or stops shuffling, at the end | `openSource()` always took `tracks.get(0)`; at EOF it only called `reopen()` (repositioning inside the same file) | Added `PlaylistTimeline` (the whole list is joined into one continuous timeline; `(now-epoch)%total duration` locates the track and offset) and `PlaylistAudioSource` (EOF moves on to the next track, wraps to 0 at the end); `trackIndex` is now genuinely sent |

Additional fixes (found during review):
- **trackIndex was about 4 seconds early**: queue backlog meant "reading the current index at send time" ran one queue
  depth ahead of the byte stream, so the client rebuilt its decoder and flushed the sound card early -> about 1 second
  of silence at the track change. Fixed by freezing the index at enqueue time (recorded in `Chunk`).
- `PlaylistAudioSource.skipMillis()` forwards the inner value (so the fallback path stays in sync).
- `PlaylistTimeline` caches results (key includes file size/modification time) to avoid scanning the whole file for its
  duration on the main thread on every subscription.
- Re-anchoring when behind now anchors to the end of the current packet (`now+interval`) instead of adding one more chunk.
- In `PcmSink`, clearing the audio sources in `stop()/releaseDevice()` moved inside the lock, for consistent locking discipline.

### OGG path removed entirely (user decision, 2026-11-23)
Origin: tiehen reported `Failed to read Ogg file 21` and produced no sound at all.

During the investigation a **refactoring regression** turned up: the code in
`AudioStreamManager.openTrack()` that looked up
`AudioTranscoder.mp3For()` had been deleted by mistake, so single-file stations pushed the raw OGG to the client.
The regression was fixed first; the user then decided that, since this path could never be reliable, it should be
removed outright - the right call.

Removals and changes:
- Deleted `OggDuration.java` / `OggHeader.java` / `client/audio/OggDecoder.java`
- The `AudioFormat` enum is down to `MP3(0)` / `UNKNOWN(1)`; `.ogg/.oga` -> `UNKNOWN`
- `AudioDuration` only does frame-by-frame MP3 scanning; `estimateOgg` removed from `AudioDurationEstimator`
- The client `ClientAudioPlayer` only creates `Mp3StreamDecoder`; a new `looksLikeOgg()` checks the byte magic,
  rejects the data explicitly and names the reason (otherwise OGG data fed to JLayer shows up as "no sound at all and no message whatsoever")

**Switched to "no silent fallback"** (key design change):
- Transcode output missing -> `openTrack()` returns null -> sends `NO_AUDIO` and refuses to play; it **no longer**
  falls back to pushing the original file
- Internet stream is not MP3 -> `openSource()` refuses with ERROR (internet streams pass through untouched and are not
  transcoded; an .ogg URL was never supposed to be supported anyway)
- Rationale: silent fallback produces audio that "seems to play but is in fact out of sync and crashes at any moment",
  which is far harder to diagnose than an explicit failure

**Latency fix for switching to an internet station** (user feedback: "switching to an internet station has a slight delay"):
The upstream handshake measures **1013ms**, and this block used to happen on the **server main thread**
(`subscribe()` is scheduled by `server.execute()`, and the constructor opened the connection synchronously) - not only
slow, it also stalled the whole world. Two fixes:
- `StreamAudioSource` now connects **asynchronously**: the constructor returns immediately (measured 1013ms -> 10ms),
  and while the connection is being made `read()` returns an **empty array** meaning "no data yet".
  The reader thread was fixed to match: it used to treat the empty array as EOF too, which invalidated the subscription
  before the connection had finished (this was also the source of "sometimes there is simply no sound").
- The jitter buffer gained a **start threshold** `startupTargetFrames()` (about 300ms), separate from the steady-state
  target (`bufferMillis`, default 1000ms): make sound first, then fill up to steady state. Steady-state jitter
  resistance is unchanged. First-sound latency 1000ms -> 300ms.

**Two masking effect sounds** (user request):
- `TuningNoise`, the "tuning noise": on a station change / track change / reload / closing the last station it plays
  about 220ms of tuning noise, covering the jump when the stream reconnects. It is **synthesised in the mixer** rather
  than played as a vanilla sound - vanilla sounds go through OpenAL and run on a clock independent of this mod's
  `SourceDataLine`, so they cannot mask accurately.
  Sound design: white noise -> low-pass -> high-pass -> 5ms fade-in + squared decay envelope (pure white noise sounds like a pop).
- `NetworkNoise`, the "bad-network noise floor": a faint hiss driven by the **remaining jitter buffer depth**.
  The signal is "the buffer is thinning (trouble is coming)" rather than "it has already stalled", so players can notice
  in advance. It has hysteresis: attack 1 chunk (~20ms), release 34 chunks (~680ms), avoiding "pop-pop" flapping around
  the threshold. **Completely silent during normal playback** - it is a fault indicator, not an effect sound.

**Playlist switched to "round-based playback"** (user request):
- Each round is a permutation of the track pool, and **a new round is generated once the whole round has played**;
  shuffle mode reshuffles every round, sequential mode keeps natural order every round (equivalent to the old loop)
- **Cross-round cooldown**: when shuffle mode generates a new round it avoids the **last 5 tracks** of the previous
  round, so the boundary between two rounds does not "play a track again right after it finished".
  With a small pool (`g ≤ n/3`) the window narrows automatically - "the ones that do not fit are left out"
- **No memory leak**: the round order is a **pure function** of `(seed, round)` and is computed on demand;
  only the last 4 rounds go into a small LRU. Measured: visiting 200,000 rounds grew the heap by only 63 KB
- Key implementation constraint (pitfall log): the cooldown correction only swaps between "the first g positions of the
  new round" and "`[g, n-g)`", and requires `n ≥ 3g`. An earlier version used `g ≤ (n-1)/2`, which left only 2 partner
  slots when n=10 and produced 19 repeats over 40 rounds; also, partner slots **must not be reused**, otherwise a swap
  puts the track straight back

**"If ffmpeg can transcode it, it is usable"** (a principle proposed by the user, now implemented):
- `AudioTranscoder.SOURCE_EXTENSIONS` (14 formats) is the single whitelist for the whole mod
- `RadioPlaylist` references it directly and no longer keeps its own copy
- Fixed the inconsistency where "a single-file station can play .flac, but put the file in a folder and it silently disappears"

### Round 2 fixes (same day, from the user's continuous testing)

This round was almost entirely about two threads, **"effect sounds"** and **"reconnecting"**. The pitfall log is below -
**all of these are already fixed and verified, but they are easily broken by later changes, so read through before touching anything**.

#### A. Internet station reconnect (user feedback: crash on network loss / extremely slow recovery / sound "snaps back")

What the log showed: **57 subscriptions and 44 connections within 4 seconds**, while the **session id stayed at 1** -
meaning every attempt was a brand-new instance and the reconnect mechanism never had a chance to work.
Three bugs amplified each other:

| # | Defect | Fix |
|---|---|---|
| 1 | The client timeout of **2.5 seconds** was less than 1 second of connect + buffer, so it was bound to misfire | `STALL_TIMEOUT_MILLIS` -> **8 seconds** |
| 2 | Server `subscribe()` **unsubscribed unconditionally first**, killing the instance that was still connecting | Made **idempotent**: the same station on the same block reuses the existing subscription |
| 3 | The client had **no backoff** and resent the subscription every 0.5 seconds | `RESUBSCRIBE_INTERVAL_MS = 6 seconds` |

Supporting change: `StreamAudioSource` gained a **`sessionId`** (incremented on every successful connection, sent down
with the audio chunks). When the client sees the session id change it **rebuilds the decoder** - the bytes after a
reconnect are **not continuous** with those before the drop, but MP3 frames are self-contained, so the old decoder would
keep decoding them as the same stream, which sounds like the audio suddenly **"snapping back"**.

#### B. Tuning noise (station-change noise) - kept going wrong, finally settled

Evolution: fixed 220ms (could not cover a 600ms gap) -> follow the gap + consecutive-stable detection ->
discovered the fade-out is **one-way and cannot be undone** -> **finally moved to an independent overlay layer**.

> ⚠️ **Never premix effect sounds into `SourceBuffer`**
> Tried it, hit three pitfalls in a row (all reproduced in testing):
> 1. `reserveFadeGain` misjudged a 20ms empty chunk as "buffer empty" and pulled it down to **2.8% volume**
> 2. A pure noise chunk was judged by `any` as "the programme is back", so the noise faded itself out
> 3. `premix` used `+=` while clearing only set `length=0`, so old samples remained and the volume **grew chunk by chunk** (0.05 -> 0.64, clipping soon after)
>
> Root cause: effect sounds **have no real data-stream lifecycle** but were forced to obey the buffer's priming/discard/clear rules.
>
> Current design: `overlayEffects()` overlays independently, but **reuses the main chain's `sourceGain()` and `panForPosition()`**,
> so volume/distance/pan are naturally consistent.

Current behaviour: on a station change the old audio **fades out over 120ms** and the noise **fades in**, the two
overlapping with no break. Trigger points: `S2C_SELECT_ACK` (carries the block position), the station-change branch of
`handleChunk`, `S2C_RELOADED`. **Dedupe window 400ms** - a single station change triggers from several places, and
without deduping it **plays twice**.

#### C. Bad-network noise floor - semantics changed three times, finally settled

| Version | Criterion | Problem |
|---|---|---|
| v1 | Remaining jitter buffer depth | The live-stream buffer sits permanently at the start level -> **stuck at 40%, never goes away** |
| v2 | Sounds on any single underrun | Normal jitter on live streams caused constant false alarms -> **noise cuts in all the time** |
| v3 | 8 consecutive chunks with no data | The silence in the stall gaps **went unfilled**, and the rest of the time had no indicator |
| **v4 (current)** | **Two levels + stickiness** | - |

Current logic (`PcmSink.updateNetworkNoise()`):
- **Stalled** (this chunk has no data at all) -> **full strength**, filling that dead air
- **Not stalled but the buffer has not recovered** -> a **0.4 weak noise floor** keeps indicating it
- Stickiness: it holds until the buffer has been **healthy for 25 consecutive chunks** (about 0.5 seconds)
- Stops on: network recovery / manual station change / deselection

**Three traps that made "the noise floor disappeared again"**:
1. **Do not clear the noise floor on every chunk received** - chunks arrive about every 250ms and stalls come in bursts,
   "stall -> noise rises -> one chunk arrives -> cleared -> stall again", so the noise floor never gets going
2. **"No audio source" must not count as healthy** - early on `isJitterBufferThinned()` returned "healthy" when sources
   was empty, so after `stop()` cleared the sources, 25 consecutive chunks counted as healthy -> the noise floor was cancelled
3. **"The user turned the station off" is not "a playback fault"** - see D below

#### D. Splitting the semantics of stop() (the most insidious contamination in this session)

`ClientAudioPlayer.stop()` was originally shared by two situations:

| Situation | Player intent | Should the noise floor play |
|---|---|---|
| User deselects / breaks the block / walks away | Does not want to listen | **No** |
| Server reports a fault (no audio / timeout) | **Still wants to listen**, failing for now | **Yes** |

A fault borrowing `stop()` cancelled the playback intent -> the noise floor stayed silent. Fix: split into two entry
points sharing `stopInternal()` (which only tears down playback and does not touch intent):
- `stop(pos)` -> clears `playbackWanted`
- `stopForFailure(pos)` -> **keeps** `playbackWanted`
- Added `PcmSink.playbackWanted` to tell "abnormal stream loss" apart from "normal deselection"

#### E. The refresh button no longer resubscribes

Reconnecting was once folded into scanning, on the grounds that "both ask for the state again" - **that judgement was wrong**.
Every reconnect of an internet live stream is a **brand-new HTTP connection**, and upstream starts sending from its own
encoder buffer, so after a reconnect you are playing **content from several seconds ago** -> **disjointed + stuttering**.

Now `onScan()` only does `requestStations()`.
Resubscription is triggered by `AutoSubscriber` only when a failure is **confirmed**.

#### F. Volume-related (repeated user feedback)

| Symptom | Root cause | Fix |
|---|---|---|
| Volume is out of control unless the UI is open | `applyBlockVolume()` was only called once, at subscription | `AutoSubscriber` **syncs on every scan** (0.5 seconds) |
| Effect sounds ignore the volume | Did not multiply the per-block volume | All go through `sourceGain()` now |
| Effect sounds have no distance attenuation | Deliberately left out (wrong call) | Added `distanceGainAt()` |
| **The volume bar jumps when Scan is pressed** | `clearVolumeFor()` in `stop()` deleted the record, so the GUI's lookup of `volumeFor()` fell back to 1.0 | The GUI uses its own `currentVolume` and restores it after scanning |

#### G. Fault display rendering (`RadioBlockEntityRenderer`)

User's reference: the radio in Arknights, where **the red light is the POWER indicator**, at the top right of the LCD.

**Design point: the red light at maximum brightness, the LCD and error code following ambient light**
- Red light `LightTexture.FULL_BRIGHT` - a self-illuminated indicator that must be visible in the dark
- LCD background + error code -> `packedLight` - a lit display surface, not a light source

**Four pitfalls already hit (all measured)**:
1. `IllegalStateException: Not filled all elements of the vertex` -
   the `RenderType.translucent()` format includes **normal**, and omitting it always crashes.
   This renderer normally draws nothing at all, so it only surfaced the first time a network failure triggered a fault
2. **Drawn on the back face** - the model's main box is `from [3,0,7] to [13,6,9]`,
   and the `north` face uv `[0,0,5,3]` is the front artwork; in the MC convention the north face is at
   **`z = from[2] = 7`, facing -Z**. Using `to[2]=9` by mistake and drawing along +Z -> everything landed on the back
3. **Text upside down and mirrored left to right** - `pxToY` converts "texture down" to "block up", and
   `yTop`/`yBottom` were passed swapped; also the font faces +Z by default, so it needs a 180 degree rotation around Y
   before it faces a -Z viewer
4. Normal written as `(0,0,1)` with +Z winding -> from the front the quad faces away from the viewer and is
   **swallowed by back-face culling** (the light does not show)

Error codes use the format `ERRnnn`, borrowing HTTP semantics: 403 too far away / 404 station does not exist /
408 data timeout / 410 push already stopped / 415 format not supported / 422 no audio available / 500 unknown.
**Shown on the LCD only, never printed to the chat bar**.

#### H. Reload and commands

- `/radio reload` (permission level 2): reloads the config + **rebuilds every subscription** + tells clients to play the tuning noise
- Vanilla `/reload` works too (`RadioReloadListener` hooks the resource reload)
- **Changing `startEpochMillis` alone is not enough**: established subscriptions hold a fixed file pointer,
  so `AudioStreamManager.rebuildAll()` is required to really start over from the beginning

#### I. HLS / HE-AAC technical evaluation (evaluated; the user decided **not to do it**)

The user wanted Himalaya HLS streams supported, and it **had to be done entirely on the client** (not every player has ffmpeg installed).

| Item | Result |
|---|---|
| Stream format | HLS, one `.aac` segment every 7 seconds, **HE-AAC** (SBR+PS), 44.1kHz/2ch/65kbps |
| `de.sfuhrm:jaad 0.8.7` | **0% success rate** (incomplete SBR, all 151 frames failed) |
| `com.tianscar.javasound:jaad 0.9.4` | **100% success** (151/151, 44100Hz/2ch/16bit), jar 643 KB |
| JAAD performance | 7 seconds of audio in **101.9 ms** (69x realtime, 1.45% of one core) |
| JLayer performance | 7 seconds of audio in **36.2 ms** (193x realtime, 0.52% of one core) |

**Conclusion: the user chose MP3 and is not switching to HE-AAC** - decoding is 2.8x slower, and the only advantage is
halved bandwidth, which is not worth it. The server-side ffmpeg version of `HlsAudioSource.java` has been deleted.
If this is ever done later: jaad 0.9.4 as an **optional dependency**, routed by `.m3u8` suffix to a separate client
playback path (fetch the list -> take the newest segment -> JAAD decode -> feed PcmSink), taking care with live latency
control and segment prefetching.

### Round 3 fixes (same day, 17:40, user's measured screenshots + offline feedback)

#### J. Fault display rendering: positions were all wrong (the biggest item this round)

Evidence from the user's screenshot: **the red lamp and the LCD background color were completely invisible**, while the `ERR408` text was **upside down and mirrored left-to-right**, floating in the keyboard area above the screen. Three independent defects were stacked on top of each other, and all of them were pinpointed and confirmed against decompiled vanilla bytecode:

| # | Defect | Root cause | Fix |
|---|---|---|---|
| 1 | The red lamp / background quad was **completely invisible** | The orientation rotation was written as `-facing.toYRot()`, which is off by **180 degrees** from the correct value, dropping the whole overlay onto the **back** of the block; the normal / winding faced away from the viewer, so backface culling swallowed it | `180.0F - facing.toYRot()` |
| 2 | Even with the right orientation the pattern was still **mirrored left-to-right** | `pxToX` was written as `FACE_X_LOW + ...`. In decompiled `FaceInfo.NORTH` the vertices are (MAX_X,MAX_Y),(MAX_X,MIN_Y),(MIN_X,MIN_Y),(MIN_X,MAX_Y), and combined with `BlockFaceUV#getU`'s index rule (vertices 0/1 use uvs[0], 2/3 use uvs[2]) that means **smaller X gives larger u**, so u=0 sits on the +X side | `FACE_X_HIGH - ...` |
| 3 | The text was **upside down** | `pose.scale(s, s, s)` was missing the negative sign on y. Font space has +y pointing down, block space has +Y pointing up | `pose.scale(s, -s, s)` (same as vanilla `SignRenderer#translateSignText`) |

Follow-up corrections (**all found during review**; the first two are the hidden pitfalls behind "still invisible after the fix"):

- **Mirroring and vertex winding have to be handled as a pair** - once `pxToX` mirrors, `pxToX(x0) > pxToX(x1)`,
  while `drawQuad`'s vertex order `(x0,yB),(x0,yT),(x1,yT),(x1,yB)` only produces -Z (toward the viewer)
  from the cross product when **x0 < x1**. Passing the mirrored values straight through flips the normal to +Z,
  so it gets culled and is **still completely invisible**.
  Both "invisible" symptoms look identical and are extremely hard to tell apart, so `drawPixelQuad()`
  normalizes with `Math.min/Math.max` internally and **callers are not allowed** to hand unsorted x values to `drawQuad`.
- **A solid-color quad must not use `uv(0,0)..(1,1)`** - the texture state of `RenderType.translucent()` is
  `BLOCK_SHEET_MIPPED`, i.e. **the entire block atlas**; `uv 0..1` in GL means "the whole texture",
  so the quad would stretch and tile the whole atlas, and vertex color only **multiplies** on top of it, which cannot smooth that out.
  Instead we sample a uniformly white sprite from the atlas (chose `minecraft:block/snow`, measured mean (249,254,254),
  closer to pure white than white_wool's (234,236,237)) and sample only its central 16% region.
  When the atlas is not ready we **skip drawing** rather than falling back to uv 0..1.
- Text display mode `SEE_THROUGH` -> **`POLYGON_OFFSET`** (vanilla signs use this too). SEE_THROUGH does not test depth, which is exactly where "the text shows through the whole block from the back" came from.
- Added `drawPixelQuad()`: callers only supply a **texture pixel rectangle**, and winding plus y-flip are handled uniformly inside the function, so yTop/yBottom can never be passed backwards again.
- Font size 0.0085 -> 0.009, and the width is limited to the LCD's **green area** (not including the black bezel) - black glyphs that spill onto the bezel disappear.

> ⚠️ **The LCD does not repaint its background** (explicit user requirement)
> Early on, a fault would paint the screen a dark red (`LCD_BG_FAULT`). The user rejected that: **the display background stays exactly as the texture, only the text turns black**.
> Now `LED_COLOR_FAULT` is the only quad that gets drawn, and the text color is `0xFF000000`.
> This also matches real hardware - the LCD background is physically fixed by the polarizer; only the strokes change.

**How this was verified** (reproducible): the four orientations together with the texture UVs and the text transform were all reduced to numbers at once,
confirming that "u=0 -> left side of the screen, LED in the top right, text readable upright, normal facing the viewer" holds for all four orientations;
and cross-checked against the user's screenshot: the black panel on the left of the texture should land on the viewer's left hand side at facing=south, matching the actual screenshot.

#### K. "No noise when the network drops"

Two **complementary** paths, either one of which results in complete silence:

1. **Pure pipeline break (the server sends no status packets)**: the `if (anchor < 0) return;` in `overlayEffects()` -
   tuning noise and network noise floor **share the same `tuningNoiseBlock` anchor**, and that anchor is only set by the retune path.
   A network drop with no retune means the anchor stays at -1, so the whole layer returns immediately and the noise floor is not even generated.
2. **The server reports a fault**: `stopForFailure()` still ends up calling `PcmSink.stop()` -> `silent = true` ->
   the mixer thread takes the silent branch, while `updateNetworkNoise()`/`overlayEffects()` both sit after that `continue`,
   and that branch calls `networkNoise.reset()` every 20ms -> the noise floor can never sound.

> This also explains the "sometimes there, sometimes not" behavior: in scenarios where **a retune happened** the anchor exists and it can sound.

Fixes (following "one function must not carry two meanings"):
- Added a separate `networkNoiseBlock` anchor: `tuningNoiseBlock` = **event** ownership (retune foley),
  `networkNoiseBlock` = **state** ownership (the link is broken). They sit side by side and are not reused.
- Extracted `overlayNetworkNoise()` so that the silent branch can produce **only** the noise floor.
  **Tuning noise must never enter the silent branch** - its duration cap is advanced by `followSilence()` on the normal path,
  which the silent branch does not run, so it would get stuck active and ring forever.
- **Do not return when there is no anchor**: tuning noise is an effect sound (no position, no sound - reasonable),
  but the network noise floor is a fault indication (if it cannot be localized, degrade to centered + multiplied by vanilla volume only - **sacrifice directionality, keep the indication**).
- New `PcmSink.silenceForFailure()`: silence + flush, but does **not** clear the anchor and does **not** reset the noise floor.
  This is exactly the unfinished second half of the semantic split in section 8, item D - `ClientAudioPlayer` already split
  `stop()`/`stopForFailure()`, but both ended up calling `PcmSink.stop()`, merging them back into one function at the mixer layer.
- The silent branch now splits on `playbackWanted`: true -> run `updateNetworkNoise()` + `overlayNetworkNoise()`;
  false -> reset + pure silence (an empty selection by the user must be absolutely quiet).
- Added a "this stream has produced sound" gate (`streamFlowing`) and an "explicitly reported fault" gate (`faultReported`),
  both judged by `faultSignalAllowed()`. **Without that gate, a normal start of playback would first hiss a fake noise floor for about one second.**

#### L. "ERR does not clear even after recovery"

Primary cause: **the local TIMEOUT only lights the lamp, there is no turn-off edge**.
`AutoSubscriber` only calls `markLocalFault(TIMEOUT)` while `stale`, and `stale` relies on
`isStalled()` reading `lastChunkAt` (refreshed on every chunk). As soon as data resumes, `stale` turns false immediately,
and from then on **no code clears `clientFaultStatus` at all**. The comment claiming "markFault(OK) automatically clears after recovery"
simply does not exist in the code (the only place in the whole project that sends OK is `AudioStreamManager:136`).

Secondary cause: the **idempotent early return** in `AudioStreamManager.subscribe()` (same channel, same block -> reuse) **does not send `sendStatus(OK)`**.
And it is precisely after a fault is confirmed that the client re-subscribes - reusing silently means ERR408 stays on screen forever.

Fixes:
- The idempotent early-return branch now sends `sendStatus(player, pos, AudioStatus.OK)`.
- Once `handleChunk` (**the AudioIngest thread**) detects that data has resumed, it hops **back to the main thread** via
  `Minecraft.getInstance().execute(...)` to clear the display.
  Writing `setClientFaultStatus` directly would touch `client.level` from the ingest thread - the thread hop is mandatory.
  It carries a **generation check** (`FAULT_GENERATION`): if a new fault arrives while the clear is queued, this clear is voided,
  preventing a "fault that flashes and goes out".
- `AutoSubscriber` stays **light-only**; the sole responsibility for turning the lamp off is handed to the recovery edge above
  (so that "this tick did not judge a timeout" cannot make the display flicker back and forth).
- User tuning-off (`stop`) also clears the display, leaving no orphan ERR.

#### M. Semantic splits added this round (same shape as section 12, fourth occurrence)

| Function | Confused meanings | Consequence |
|---|---|---|
| The anchor in `overlayEffects()` | "retune foley ownership" vs "link fault ownership" | no noise when offline |
| `PcmSink.stop()` | "the user no longer wants it" vs "fault mute" | noise floor treated as the user switching the station off (the unfinished second half of item D) |
| `networkNoise.reset()` in the silent branch | "empty selection, idle" vs "fault, no data available" | noise floor never comes up |
| `STREAMS` in `ClientAudioPlayer` | "actually receiving a stream" vs "the player pressed confirm and has not revoked it" | no sound on failure before the first chunk / late packets light it up again after the station is switched off |

**The conclusion is the same as section 8, item D**: `STREAMS` (fact) and `REQUESTED` (intent) must be separate;
intent can have only one predicate, otherwise both opposite misjudgments necessarily exist at the same time.

#### N. Round 4 fine-tuning (after the user tested and reported "rendering is fine")

Rendering placement is now confirmed correct; this round only adjusts feel and log noise:

| Item | Change |
|---|---|
| Red lamp | **Fast blink**, half period 250ms (2Hz, one bright/dark cycle every 0.5s). During the off phase **the entire quad is not drawn**, revealing the texture's original dark red "unlit" state. Uses wall clock rather than `getGameTime()` - the latter freezes the lamp half-lit while paused |
| Screen text | `ERRnnn` -> **`NOSIGNAL`**, and **always shown, never blinking** (text must be readable; the lamp is what blinks - different responsibilities) |
| Volume slider | Travel **10 steps -> 5 steps** (0/25/50/75/100%). On an 18px-wide slider, 10 steps means under 2 pixels per step - impossible to stop accurately |
| Auto retry | Server reconnection **no longer gives up** (`MAX_RECONNECT_ATTEMPTS` -> unlimited). It used to give up completely after 120 attempts, so a long outage required the client to re-subscribe and rebuild the whole subscription, losing the jitter buffer |
| **Log spam** | See below |

##### The four sources of log spam (all fixed)

The user reported: "a failure only needs a log line at the start and one at the end, right now it spams".
Each source was found and compressed into **two lines per fault** (start / end):

| Location | Old rate | Now |
|---|---|---|
| `StreamAudioSource.scheduleReconnect()` | WARN on every retry (one every 3s while offline) | New `episodeLogged`: **one** "lost contact" report per fault, plus one "recovered (N retries, lost for X ms)" on recovery. Per-attempt logs demoted to `debug` |
| `StreamAudioSource.connect()` | INFO "connected" on every (re)connect | INFO only on the **first** connection (`sessionId==1`); successful reconnects are reported by the "recovered" line above |
| `AutoSubscriber` | INFO "data timed out, re-pulling stream" on every re-subscribe (one every 6s) | New `retryLogged` set: one "starting automatic retry" per fault. Removed once streaming recovers, so the next fault can report again |
| `ClientRadioNetwork` status packets | WARN on every non-OK packet | New `ClientAudioPlayer.logFaultOnce()`: no repeat while **block + status code** are unchanged; `resetFaultLog()` on recovery |

> Design intent: **one line at the start, one at the end, completely silent in between**. Bump the log level to DEBUG to see retry details.
> This does not conflict with "no silent degradation" in section 8 - what goes silent is the **repetitive retry process**,
> not the fact that something went wrong.

#### O. Round 5: responsibilities redrawn (explicit architectural requirement from the user)

The user's exact words:
> Reconnecting is the client's business. When the network drops the server just marks it offline (including quitting the game), and reconnecting counts as coming back online.
> The server does not care whether a network station lives or dies, it only manages custom stations and block data. When a player disconnects or loses the network, clean up the connection state.
> While the connection is unhealthy the client does not fully disconnect from the server, it just has a louder noise floor and does not reconnect directly,
> but once the server decides the player has disconnected it resets that player's subscriptions.

##### New responsibility boundary (the most important design constraint this round)

| Layer | Responsible for | Explicitly <b>not</b> responsible for |
|---|---|---|
| **Server** | Custom stations (local files / playlists), block data, connection <b>lifecycle</b> | Whether a network station lives or dies (if upstream dies, <b>no repair, no retry</b>) |
| **Client** | Displaying status, noise floor indication | Rebuilding subscriptions / reconnecting on a weak network (only fills in a subscription when there is <b>no local subscription</b>) |

##### Implementation changes

1. **Removed the server-side network stream reconnect state machine** (`StreamAudioSource`)
   - Removed `scheduleReconnect` / `maintainReconnect` / backoff / `MAX_RECONNECT_ATTEMPTS`
   - Connection failure, EOF, read exception -> **mark `finished` and stop there**, with a single log line
   - `read()` returns an <b>empty array</b> when finished or while connecting (it must not return null, which would make the read thread exit and invalidate the subscription)
   - Kept the `sessionId` protocol field (the client still needs to judge byte-stream continuity), but a single instance is always 1

2. **The read loop must be able to exit** (`AudioSubscription.readLoop`)
   - Added: exit the loop and `notifyAll` when `source.isFinished()`
   - <b>Why it is mandatory</b>: now that the server no longer reconnects, a dead stream would return an empty array forever;
     without this check the read thread would spin every 50ms and the subscription would hang around occupying a slot.
   - After exiting, the subscription stays active (the server does not kick it), it just stops producing data - consistent with "does not care whether it lives or dies"

3. **Connection lifecycle made explicit** (`SmileSRadio`)
   - `DISCONNECT` -> `unsubscribeAll` (clear all of that player's subscriptions, including closing HTTP connections)
   - `JOIN` -> also `unsubscribeAll` (idempotent safety net: no leftover subscriptions after a crash recovery / exception)
   - Cleaning on JOIN avoids a spurious "too many subscriptions" and sending packets to dead connections

4. **The client no longer re-subscribes on a weak network** (`AutoSubscriber`)
   - The semantics split into three states, <b>only the first two subscribe</b>:

     | State | Predicate | Action |
     |---|---|---|
     | New / retuned | `isNew` | subscribe |
     | **No local subscription object** | `!isStreaming()` | subscribe (with a 6s backoff) |
     | Subscription exists but no data | `isStalled()` | **show the fault only, never subscribe** |

   - This is yet another instance of the pattern from section 12: merging "should repair" and "should display" into one predicate
     means the subscription gets rebuilt repeatedly during an outage, shaking audio that was merely stuttering into something worse.

5. **The client clears its local stream table on JOIN** (`AudioLifecycleHooks`)
   - The server already clears subscriptions when the player goes offline; if the client does not clear `STREAMS`,
     `isStreaming()` stays true, AutoSubscriber thinks "already playing", and it stays <b>permanently silent after reconnecting</b>

##### Narrowing the no-signal display (additional user request)

> But the client's no-signal is about network stations, not custom stations.

`NOSIGNAL` only means "the upstream network is down", so both entry points now check the station type:

- **Local judgement** (`AutoSubscriber`): lights up only when `stale && isStreamingStation(stationId)`
- **Server status** (`ClientRadioNetwork.displayStatusFor`): a new translation layer was added that
  maps transport-layer status codes to "should this go on screen":

  | Status code | On screen | Reason |
  |---|---|---|
  | `TIMEOUT` + network station | ✅ NOSIGNAL | a genuine "no signal" |
  | `TIMEOUT` + local station | ❌ | local files have no such thing as a "signal" |
  | `TOO_FAR` | ❌ | the player walked away; it comes back on entering range |
  | `STOPPED` / `NO_AUDIO` / `UNSUPPORTED_FORMAT` / `UNKNOWN_STATION` | ❌ | server-owner configuration or transcoding problems, unrelated to signal |

> These statuses <b>are still logged as usual</b> (needed for troubleshooting), they just do not go on screen.
> That way `NOSIGNAL` has a single, accurate meaning: **the upstream of this network station is down**.

#### P. Round 6: config validation / unknown channel alarm / rate field

##### 1. Channels that fail config validation are <b>not shown</b>

Added `RadioStationRegistry#isPlayable()`, validating each entry in `load()`:

- <b>Network stations</b>: a valid http(s) URL counts as valid. Whether upstream is reachable right now <b>is not a config error</b> -
  a dead stream shows NOSIGNAL instead of being deleted from the list.
- <b>Local stations</b>: `resolveTracks()` is non-empty, and at least one track is MP3 or a transcodable format.

Invalid channels <b>do not enter `stations`</b> (invisible in the client list),
and their ids are recorded in `invalidStationIds()` for the case where "a block still remembers it" needs to raise an alarm.

##### 2. Configured but broken by reload / unknown channel -> <b>continuous noise + NOSIGNAL</b>

The key is to distinguish the two kinds of "cannot play":

| Case | List | Block behavior |
|---|---|---|
| Misconfigured from the start (file does not exist) | <b>hidden</b> | - |
| Was fine, became invalid / was deleted after reload | hidden | <b>continuous noise + NOSIGNAL</b> |
| Block remembers a completely nonexistent id | - | <b>continuous noise + NOSIGNAL</b> |

From the client's point of view the last two are the same thing: <b>the id in the block NBT cannot be found in `ClientRadioData`</b>.

Implementation notes:
- `ClientRadioData.findStation()`: answers "does this channel exist in the config at all"
- `ClientRadioNetwork.displayStatusFor()`: `UNKNOWN_STATION` <b>always</b> goes on screen;
  on `TIMEOUT`, if the block's id is no longer in the config, it is also upgraded to `UNKNOWN_STATION`
- `AutoSubscriber`: the `unknownStation` branch lights up <b>without waiting for the 8-second timeout window</b>
  (it is never going to get better, waiting is pointless), and it <b>does not initiate a subscription</b>
- `ClientAudioPlayer.markUnknownStationFailure()`: <b>a new entry point, not reusing stopForFailure</b>.
  Reason: `stopForFailure` depends on `REQUESTED` (the player pressed confirm),
  while an unknown channel typically comes from "the block is already broken by the time you join the server"; this machine has never sent a subscription request and `REQUESTED` is empty,
  so reusing it would leave the noise floor silent, exactly violating the requirement.

> Note the word "continuous": the noise floor is maintained by the mixer according to buffer state and is not wiped by a single status packet;
> NOSIGNAL is maintained by the block entity state until the data really comes back or the player deals with it.

##### 3. The `rate` field (right side of the list)

`RadioStation` gained `rate`; `displayRate()` returns <b>`NETWORK`</b> when unset.

UI: `RadioScreen.renderList()` first computes the rate width and right-aligns it,
<b>then derives the available width for the name</b> (`nameBudget`) from it - otherwise a long channel name covers the rate and neither is readable.
The rate uses a dimmer color so it does not compete with the channel name for attention.

The example config and the auto-generated `stations.json` both include the `rate` key (empty string = NETWORK).

##### 4. Test config updated

`run/saves/New World/serverconfig/radio-smile/stations.json` and `run/world/...`:

| id | Content | rate |
|---|---|---|
| `tiehen` | local OGG | FM105.7 |
| `testaudio` | local OGG | (empty -> NETWORK) |
| `qtfm` | Qingting FM network station | (empty -> NETWORK) |
| **`fm907`** | **Hangzhou Pop Music Radio**, `https://lhttp-hw.qtfm.cn/live/15318146/64k.mp3`, icon `fm907.png` | **FM90.7** |
| `broken` | deliberately points at a nonexistent file -> should be hidden | - |

The new stream has been measured working: HTTP 200, `audio/mpeg`, `icy-metaint=16000`.

#### Q. Round 7: root cause of text flicker / rate hidden by the scrollbar / right-click interaction

##### 1. NOSIGNAL text flicker (**root cause found, not intermittent**)

The user reported "the text still flickers". Checking `run/logs/latest.log` gave decisive evidence:
**"audio recovered" was printed 119 times, while "audio subscription failed" was 0** - the fault was being lit and cleared repeatedly on the <b>local</b> side.

Two independent defects stacked together:

| # | Defect | Consequence |
|---|---|---|
| 1 | `clearClientFaultOnResume` **does not set `faultShown` back to false** after clearing the fault | that "should I queue" gate stays open forever -> **every incoming audio chunk clears again and logs another line** (measured: one line every 0.25s) |
| 2 | The client **never requests the station list** when entering a world (it only requests it when the GUI opens) | a player who never touches the UI has a permanently empty `ClientRadioData.stations()` -> the "unknown channel" check I added last round classifies <b>every</b> channel as unknown -> lit every 0.5s, cleared again when data arrives -> flicker |

Fixes:
- `clearClientFaultOnResume` explicitly sets `faultShown = false`
- `AudioLifecycleHooks` actively calls `requestStations()` on JOIN
- Two safety nets: both `AutoSubscriber` and `ClientRadioNetwork.displayStatusFor` get a
  **`ClientRadioData.isLoaded()` precondition**, so "unknown" is never judged before the list arrives

> Lesson, same shape as section 12: **"unknown" and "not known yet" are two different things**.
> Treating "the list has not loaded" as "the channel does not exist" manufactures a fake fault on every poll.

##### 2. rate text not displayed

The right edge was written as `listRight - 8`, while the scrollbar occupies `[listRight-5, listRight]` -
so the right end of short text (e.g. `NETWORK`) sat right under the scrollbar.
Changed to `listRight - SCROLLBAR_WIDTH - 4`, and the rate is drawn even when the name budget is squeezed to 0.

##### 3. Right-click interaction: does not trigger while holding another item

`RadioBlock#use` gained `isInteractionItem()`: the UI opens only with an <b>empty hand</b> or when <b>holding the radio item</b>;
everything else returns `PASS` - **right-clicking with a block still places it normally**, tools/food work normally, nothing is stolen by the radio.

It also fixed a hidden bug that only occurred on the server: the original implementation returned `PASS` on the server,
so the server continued into `ItemStack#useOn`, and **right-clicking the radio while holding the radio item would conjure a block on the server**
while the client had already opened the UI. Now the server returns `SUCCESS` and consumes the interaction (the UI still only opens on the client).

> Interaction matrix:
>
> | Held item | Client | Server | Result |
> |---|---|---|---|
> | Empty hand | SUCCESS | SUCCESS | opens the UI |
> | Block / tool / food | PASS | PASS | **falls through to vanilla** |
> | Radio item | SUCCESS | SUCCESS | opens the UI and does not secretly place a block |

#### R. Round 8: LCD shows rate / block-break noise / thin-buffer warning

##### 1. `rate` shown on the <b>block's LCD</b> (the position had been misunderstood all along)

Earlier rounds put `rate` on the <b>right side of the tuning GUI list</b>, and the user said "it doesn't show" -
in fact the **position was misunderstood**: the requirement was to show it on the **block's LCD**.

- Normal playback: the LCD writes that channel's `displayRate()` (e.g. `FM90.7`, or `NETWORK` if unset)
- Fault: still writes `NOSIGNAL`
- Both share the newly extracted `drawLcdText()`, so we do not write two copies and fix only one side
- `shouldRender()` used to `return fault != OK`, which <b>draws nothing at all</b> during normal playback -
  it had to become `return true`, otherwise the frequency on screen would never be visible
- Font size now **adapts to width**: short strings grow, long strings shrink, clamped at both ends

##### 2. Noise when breaking the block

Two overlapping defects:

| # | Defect | Fix |
|---|---|---|
| 1 | `silenceIfNoSources()` only clears `tuningNoiseBlock`, **missing the network noise floor's anchor and gain**. When breaking a block with `STREAMS` already empty this path runs -> the block is gone but the noise floor keeps sounding | clear `networkNoiseBlock` as well, reset `networkNoise` and all counters |
| 2 | During the few ticks it takes to break the block, the server stops pushing first -> `stale` becomes true, but the block entity is not removed yet -> a link fault is misjudged and the noise floor lights up | the fault check gains a "does the block entity still exist" precondition |

##### 3. Noise floor when the buffer runs thin (user requirement: including station switching and network recovery)

Added `isBufferThinning()`. **The key is not to repeat the v1 mistake from section 8, item C**:
v1 judged by "water level relative to the configured target", but a live stream settles at the startup water level (300ms vs a 1000ms target),
so it sat at a permanent 40% that never went away.

Current predicates (either one triggers):

| Predicate | Meaning | Threshold |
|---|---|---|
| **Thinner relative to its own steady state** | the definition of "the network is getting worse", independent of the absolute level | below 55% of steady state |
| **Absolutely too thin** | a thin buffer that can break at any moment | below 70% of the startup threshold |

Steady-state baseline update rule: **follow immediately when it grows thicker, drift down slowly when it gets thinner (1/1024)**.
The decay must be much slower than a real drop, otherwise the baseline would "absorb" the drop and never alarm
(measured: a 1/256 decay has a 3.5s half-life, faster than a real drain, so it was changed to about 14s).

Simulation verification (600 chunks):

| Scenario | Chunks triggered |
|---|---|
| Live steady state 300ms | **0** |
| Live 300ms + jitter +-25% | **0** |
| Local steady state 1000ms | **0** |
| Local 1000ms drops abruptly to 400ms | 329 ✅ |
| Constant rate 300ms drops to 120ms | 455 ✅ |

> Zero false positives in all normal scenarios, and every abnormal scenario is detected.

#### S. Round 9: selection box (not collision box)

The user asked to "adjust the collision box"; after clarification it was confirmed to be the **selection box** (the ray-traced highlight outline), **not entity collision**,
and **the antenna may be left out**.

In 1.20.1 the two are independent:

| Method | Purpose | This round |
|---|---|---|
| `getShape` | **selection box**: the outline when hovering, the ray-hit range | **added**, using the body geometry |
| `getCollisionShape` | entity collision: whether players/mobs/dropped items can pass through | **kept empty** (same category as torches, passable) |

- Shape = the model's main box `x=3..13, y=0..6, z=7..9`, **excluding the antenna**
- Rotated by `180 - toYRot()` (the same angle set as the render layer, otherwise you can "see it but cannot click it")
- **`getCollisionShape = Shapes.empty()` must be kept explicitly**:
  the 1.20.1 default implementation falls back to `getShape`, and if you omit it, players will collide with a thin sheet and cannot walk through

> Pitfall: 1.20.1's `Shapes` has **no** public rotation method (only box/or/join/getFaceShape),
> and `VoxelShape` only has `move`. So the axis swap for 90-degree multiples around Y is written by hand: `(x,z) -> (z, 1-x)`.

### Where the config lives (frequently asked)

| File | Path | Notes |
|---|---|---|
| **Station config** | `<world save>/serverconfig/radio-smile/stations.json` | dev environment: `run/saves/<save name>/serverconfig/radio-smile/` |
| Audio files | `audio/` in the same place | single file or a directory (playlist) |
| Station icons | `icons/` in the same place | |
| Transcode cache | `cache/baked/` in the same place | ffmpeg output, safe to delete |
| **Client config** | `run/config/radio-smile-client.json` | client only; can also be edited in the ModMenu UI |

> Note that `stations.json` is stored **per save** (not globally in `run/config/`),
> so switching saves means reconfiguring under the new save's `serverconfig`.

#### T. Round 10: config moved to config / txt redirect playlists / lightweight hash

##### 1. Station config moved to the global `config/`

| | Old path | New path |
|---|---|---|
| Station config | `<save>/serverconfig/radio-smile/` | **`config/radio-smile/`** |

Reason: the old path was **isolated per save**, so switching saves or starting a new world meant reconfiguring tens of MB of audio.
The new location is globally unique and sits next to `config/radio-smile-client.json`.

**Automatic migration from the old location**: if the new location has no `stations.json` yet and the old one does,
the whole directory tree is copied over on first load (including audio/ and icons/, because the config uses relative paths throughout).

##### 2. txt redirect playlists

The `file` field may point at a `.txt` (also `.list` / `.m3u` / `.m3u8`):

```
# first line is the mode (optional, defaults to sequence)
sequence
song_a.mp3
folder/song_b.ogg
https://example.com/song.mp3
```

- Modes: `sequence`/`order`/`loop` (in order), `random`/`shuffle` (shuffled with a fixed seed)
- **Play order = txt lines top to bottom** (explicit user requirement)
- Blank lines and lines starting with `#` are ignored; trailing comments are supported (only when there is whitespace before the `#`, so a `#` inside a URL is not harmed)

##### 3. Play order = txt lines top to bottom (**do not bucket or sort**)

Uncached network entries are in a **pending** state: they **do not enter** the playable list,
so "skip automatically when playback reaches that line" holds naturally, and the playback layer needs no extra check.
Once background downloads finish, they merge into the playable list on their own.

> ⚠️ **Do not reintroduce "priority bucketing"**. A three-tier priority of "local > cached network > uncached network" was implemented once,
> with the intent of "play what is playable first", but it **scrambles the line order** -
> and the user explicitly requires "sequential playback means top to bottom of the txt lines", which must not be disturbed especially in mixed playlists (local/network alternating).
> Pending-skip already solves "uncached entries must not block playback"; sorting is redundant and harmful.
>
> Measured (mixed A/network/B/network/C): playable entries = A, B, C, order preserved.

##### 3b. Network addresses: cache locally + transcode to MP3

`RemoteAudioCache` handles: download -> store in `cache/remote/` -> hand to `AudioTranscoder` to convert to MP3.
After that they participate in the timeline **exactly like** ordinary local audio - so the architecture needs no second playback path
(live streams cannot seek and have no duration; mixing them into the list would break multiplayer synchronization).

**Proof file**: also stores `<id>.proof`, containing the **first 8KB + total file size**.
The next validation only needs a Range request for the first few KB to compare; no need to re-download the whole file.

##### 4. Pending mechanism + automatic streaming annotation

- **Uncached network entry -> pending**: not in the playable list; playback **jumps straight to the next track**
- Background downloads run serially on a single thread; when done the parse cache is cleared and the entry merges in naturally on the next round
- **Automatic annotation of streaming (infinite) transfers**: when `Content-Length` is missing (or exceeds the 512MB cap),
  that line in the txt is rewritten in place to
  `# [disabled: streaming transfer, cannot be cached] https://...`
  - the server owner opens the file and sees the reason without digging through logs

> ⚠️ Live streams (e.g. `lhttp-hw.qtfm.cn/live/...`) **must not** be written into a txt:
> they have no file length and never finish downloading. A live stream should be configured directly as a channel's `file` (going through `StreamAudioSource`).

##### 5. Lightweight hash (saves CPU)

Cache validation changed from "whole-file SHA-256" to `quickHash()` = **first 64KB + file size**:

- A 20MB file goes from ~50ms to ~0.1ms
- Every `/reload` recomputes it for all files, and that adds up
- `AudioTranscoder.cacheKey()` **now uses the same function** - a mismatch between the two produces
  "validation thinks nothing changed, but the cache key changed", which shows up as re-transcoding on every startup with a cache that never hits

#### U. Round 11: adapting to real network links (Bilibili .m4s / NetEase .mp3)

Tested with real links supplied by the user; 5 defects were found and fixed:

| # | Defect | Fix |
|---|---|---|
| 1 | `.m4s` was not on the transcode whitelist -> Bilibili links were skipped outright | added `m4s/mp4/mkv/webm` to the whitelist. **Measured: .m4s is an MP4/ISO-BMFF container** (magic `ftypiso5`, AAC 48kHz stereo audio track), and ffmpeg extracts it fine |
| 2 | Content-Type is untrustworthy: Bilibili's proxy labels .m4s as `audio/mpeg` | do not judge format from response headers, use the extension only and let ffmpeg decide |
| 3 | An extension-less URL downloads as `.bin` and can never be transcoded | added **magic-number sniffing** `sniffAndRename`: identify mp3/ogg/flac/wav/m4a from the file header and rename |
| 4 | **The cache was wrongly discarded after a temporary signed link expired** | key trade-off: **no proof available != the file changed**. On request failure / no 206, **keep trusting the existing cache** (the local file is complete); only "got a new header and it does not match" counts as invalid |
| 5 | The parse stage waited 1 second per network address for a Range check | parsing only checks local existence (`verify=false`); validity checks carry a **10-minute TTL cache**. Measured **4477ms -> 216ms (20x)** |

##### Measured results (real links)

| Link | Result |
|---|---|
| NetEase .mp3 (6.8MB) | downloaded in 1.8s, native MP3 needs no transcode, second hit **24ms** |
| Bilibili .m4s (4.5MB) | downloaded in 12.4s, identified as `.m4s`, produced a **3:11** MP3 |
| Proof file | 8200 bytes each (8KB header + 8 bytes length) |

##### Test config

`run/config/radio-smile/audio/realtest.txt`:

```
sequence
test_station.ogg          # local
https://.../xxx.mp3       # NetEase
https://.../xxx.m4s       # Bilibili (m4s)
https://.../yyy.mp3
https://.../yyy.m4s
```

Switch to the `realtest` channel to verify: the first 4 are cached and play immediately, uncached ones are pending and download in the background.

> ⚠️ **Bilibili links carry a `deadline` parameter and expire after about 2 hours**. After expiry, validation goes down the "no proof -> use the cache" branch,
> and already-downloaded files still play fine. Re-downloading, however, requires a fresh link.

#### V. Round 12: fixing "the timeline is completely scrambled" + looser mode syntax

##### 1. Root cause of the scrambled timeline (**the most important item this round**)

Symptom: after hooking up network links, playlist switching times no longer matched the UI/timeline at all.

The root cause is a **three-stage chain**, and each stage looks "reasonable" on its own:

| Stage | What happened |
|---|---|
| (1) | At `load()` the network entries in the txt are **pending** (not downloaded yet); preheating only handled local files |
| (2) | When the background download finished it **only cleared the txt parse cache and did not trigger transcoding** - nothing would ever run preheating again |
| (3) | So `resolveTracks()` got untranscoded `.m4s`. And `AudioDuration` **only recognizes MP3 frames** -> duration -1 -> falls back to **1ms** |

**A 1ms track pulls the start point of every following track forward**, so "round N, track M, millisecond K" positioning completely diverges from real playback.
At the same time `PlaylistAudioSource.openTrack()` rejects non-MP3 and skips tracks repeatedly at runtime - the two errors compound, which is exactly the "completely scrambled timeline".

Fixes (three places, all required):

1. **Transcode immediately after download** (`RemoteAudioCache`): `AudioTranscoder.preheat(List.of(local))`
2. **Return the transcoded artifact first when parsing the txt** (`TxtPlaylist`): `mp3For(cached)`
3. **The timeline only accepts MP3** (`PlaylistTimeline.of`): drop non-MP3 with a warning, **never let it into the timeline**.
   This is the last line of defense - even if the first two fail, a misaligned timeline can no longer be produced.

> `durationOf()`'s warning text also changed from "will be treated as 1ms" to an explicit error,
> because this fallback is itself the source of the scrambling and should not be treated as a normal path.

##### Measured verification

| Item | Before | After |
|---|---|---|
| `.m4s` transcode | not transcoded, swallowed by the timeline | both transcoded to MP3 |
| Per-track duration | `.m4s` = **1ms** | 139920 / 191088 / 839664 / 1272744 ms |
| Timeline | All start points misaligned | 5 tracks / **1527.1 seconds** per round |
| Spot-check seek | Does not line up | Time 1526149ms → track 5 at 138920ms, **exactly that track's real duration** |

##### 2. Looser mode syntax (user request)

`mode` now accepts several groups of synonymous spellings:

| Meaning | Accepted spellings |
|---|---|
| **Single-track loop** | `block` / `single` / `false` / `0` |
| **Playlist loop** | `sequence` / `order` / `loop` / `true` / `1` |
| **Shuffle** | `random` / `shuffle` |

Case-insensitive, leading/trailing whitespace ignored; **an empty value is the same as unconfigured** (single-track loop).

> `true`/`1` maps to **playlist loop**, not shuffle — the most natural reading of `true` is "loop the list".
> Shuffle is not a guessable meaning, so it must be written as `random`.

##### 3. Rewritten default example config

The generated `stations.json` now ships with built-in note fields (play mode / rate / txt / example),
and the 5 example entries are each annotated: single-track loop, playlist loop, shuffle, txt playlist, live network stream.
It also generates `example/mylist.txt` as a txt format reference.

Gson's HTML escaping was also turned off (`disableHtmlEscaping`):
otherwise the config file would contain escapes like `mode\u003dblock`, which are impossible to read at a glance.

#### W. Round 13: root cause of the pacing bug (dropping half the packets) + volume step + txt boolean switch

##### 1. "Half the packets dropped, the rest is noise" — root cause in the pacing conversion

The user described it as "a long stretch of audio regularly missing half, with the rest spliced on" and "the dropped half turns into noise".
Quantifying the spectrogram of a screen recording produced decisive evidence:

| Measurement | Value | Corresponds to |
|---|---|---|
| Periodic dropout | **3.01 seconds** | Client buffer cap of **3.000 seconds** |
| High-frequency burst duty cycle | **49.6%** | About half the data is dropped |
| Waveform autocorrelation | ~0.003 | Spliced, not replayed |
| All-zero digital segments | **0** | Not a stream break; `dropFront` is dropping the oldest data |

**Root cause**: `AudioSubscription.markSent()` uses
`audioSentMillis = sentBytesFromStart * 1000 / bytesPerSecond` as the pacing anchor,
but the playlist's `bytesPerSecond()` returns the **average bitrate of the whole list** (172.8 kbps),
while the list mixes 128 kbps and 320 kbps tracks:

| Track type | Pacing error | Consequence |
|---|---|---|
| 320 kbps (NetEase Cloud Music) | **+85%** | Sends ~1.85x real time → buffer piles up to the 3-second cap → **oldest audio dropped** |
| 128 kbps (locally transcoded / Bilibili m4s) | **-26%** | Only 74% of the data is sent → buffer starves → **nothing but noise during that stretch** |

Fix (three places):
1. **Anchor changed to accumulate each chunk's real duration**: `audioSentMillis += intervalMs`.
   `intervalMs` comes from `AudioDurationEstimator` parsing MP3 frame headers frame by frame,
   and is **completely independent** of whether the bitrate field is accurate. Measured on 4 mp3 files, the estimate deviates from the real duration by **< 0.1%**.
2. The first-packet anchor also switched to the accumulated value, no longer using byte conversion.
3. `PlaylistAudioSource.bytesPerSecond()` now returns the **current track's** bitrate
   (the old comment's worry about "a jump when the track changes" was right, but the solution was wrong: averaging away the jump
   meant every track got the wrong pacing).
4. **Log a warning** when the client buffer overflows (rate-limited to one per 5 seconds) —
   historically this symptom had no logging at all, which is why it kept being misdiagnosed as a decoding or network problem.

##### 2. Volume step changed to 5%

`VOLUME_STEP_PERCENT = 5` (20 steps), instead of the previous `VOLUME_STEPS = 5` (5 steps = 20% per step).
The two values mean completely different things, so the constant was renamed to prevent another misunderstanding.

##### 3. First line of txt changed to a boolean switch ("shuffle order")

```
# First line: shuffle order
false          # do not shuffle, strictly top-to-bottom below (default, the whole line may be omitted)
true           # shuffle order, same order for every player
```

- Accepts `true/false`, `1/0`, case-insensitive
- **Backward compatible with the old style**: `random/shuffle` → shuffle, `sequence/order/loop` → no shuffle
- If the first line is not a switch, it is treated as a normal entry

> The user's wording: call it "**shuffle order**" in the docs, not "random playback / playlist playback".

#### X. Round 14: configurable ffmpeg path

A `ffmpeg` field was added at the **top level** of `stations.json` (outside the station list):

```json
{
  "ffmpeg": "C:\\tools\\ffmpeg\\bin\\ffmpeg.exe",
  "stations": [ ... ]
}
```

For servers where ffmpeg is not on PATH (self-hosted containers, stripped-down systems, or several versions coexisting where one specific build must be picked).

| Configured value | Behavior |
|---|---|
| Field missing / empty string | Use the `ffmpeg` on PATH (default, leaves existing deployments untouched) |
| Valid path | Adopted, and the log confirms "ffmpeg specified by config" |
| Path does not exist | WARN and fall back to PATH, **explicitly stating** that if PATH has none either, transcoding is impossible |

> Key implementation constraint: probing (`ffmpegAvailable`) and the actual transcode (`runFfmpeg`) **must go through the same
> `ffmpegCommand()`**. Otherwise you get the self-contradictory state where "probing says available, transcoding says not found" —
> exactly the old "one meaning, two implementations" problem from Section 12 of the HANDOVER.

All four configuration combinations tested correctly; and a real .ogg confirmed **transcoding via the configured path succeeds**.

#### Y. Round 15: Linux compatibility audit (measured in WSL)

Verified for real on WSL2 Ubuntu (static audit plus behavior tests under Linux semantics).

##### Two real defects found and fixed

| # | Defect | Consequence | Fix |
|---|---|---|---|
| 1 | **`\\` paths in the config break on Linux** | Configure on Windows, then upload to a Linux server: `audio\\song.mp3` looks for a single flat file literally named `audio\song.mp3` → always "station has no usable audio", even though the directory clearly contains files | Added `normalizeSeparators()` to turn `\\` into `/`. Covers audio paths, icon paths, and txt entries |
| 2 | **A UTF-8 BOM disables the txt first-line switch** | Windows Notepad's "Save as UTF-8" writes a BOM, so the first line becomes `"\uFEFFfalse"`; `trim()` does not strip U+FEFF → the switch silently fails | Added `stripBom()` |

Item 2 is especially sneaky: the BOM is an invisible character, so the server owner is left wondering "I clearly wrote true, why did it not shuffle".

##### WSL test results

```
=== Backslash paths (Linux semantics) ===
Before fix: audio\song.mp3 -> exists=False
After fix:  audio\song.mp3 -> exists=True
            sub\b.mp3     -> exists=True
=== txt parsing ===
t_bom        shuffle=true   entries=1   ← switch correctly applied with BOM
t_backslash  shuffle=false  entries=1
t_crlf       shuffle=true   entries=1   ← CRLF works
```

##### Audit items that passed (no changes needed)

| Item | Conclusion |
|---|---|
| Resource path casing | 11 resource files, **0 casing conflicts**, 0 missing references in code |
| Paths handed to ffmpeg | All derived from `getConfigDir()`, **all absolute**, unaffected by cwd |
| `ffmpeg` executable name | Bare name via PATH; a separate `ffmpeg` config field can specify an absolute path |
| CRLF / LF line endings | `readAllLines` + `trim()` handle both |
| File content encoding | All reads and writes use an explicit `StandardCharsets.UTF_8` |
| `RandomAccessFile` / `Path` / `ProcessBuilder` | All cross-platform APIs |

> **Known limitation** (outside this project's control): parsing **Chinese file names** depends on the JVM's
> `sun.jnu.encoding`. Linux defaults to UTF-8 and works; on a Chinese Windows install running GBK,
> a save path containing Chinese is a JVM-level problem.

#### Z. Round 16: heartbeat on track change / automatic icon downscaling / architecture Q&A

##### 1. False alarm in the gap between tracks (send empty heartbeat packets)

**Root cause**: `AudioStreamManager.pump()` **just returns and sends nothing** when `pollChunk() == null`.
On a track change, a playlist round wrap, or a brief upstream stall, the read thread goes a few hundred milliseconds without data,
and from the client's side that looks like "the server went quiet" — yet the client decides both "the stream is stuck" and "whether to play the noise floor" from `lastChunkAt`,
so after 8 seconds it declares a timeout → **NOSIGNAL lights up + the noise floor plays**, when the server is actually fine and merely changed song.

**Design choice**: the user offered two paths — "make it seamless" or "send empty packets". **Empty packets** was chosen:
- Seamless needs the client to pre-decode the next track and splice PCM: a large change surface, and prone to pops at the splice point;
- An empty packet says exactly "I am still alive, this stretch just has no data", in a dozen-odd bytes.

Implementation points:

| Location | Purpose |
|---|---|
| `sendHeartbeat()` | Reuses the `S2C_AUDIO_CHUNK` channel with an **empty payload**; field order must match an audio chunk exactly |
| `pump()` no-data branch | `isDue()==true` but no data → send a heartbeat (not sent when `isFinished()`) |
| `pumpAll()` not-yet-due branch | Must also be able to send when `isDue()==false` — **the easiest one to miss**, since pacing can push `nextSendAt` far into the future |
| `ClientAudioPlayer.handleChunk` | Empty array → **refresh `lastChunkAt` and return**, without feeding the decoder or clearing the fault display |

Key invariants:
1. The heartbeat **does not call `markSent()`** → it does not participate in pacing and does not disturb the audio rhythm
2. The heartbeat **immediately calls `markHeartbeatSent()`** → otherwise the push thread would spam on every 5ms pass
3. 1000ms interval: during normal playback (chunk interval ~250ms) **zero heartbeats are sent**; far below the client's 8-second timeout
4. **Not sent** when `source.isFinished()` → a genuinely dead upstream can still report a fault normally

##### 2. Server-side automatic icon downscaling

Server owners often drop in a 500x500 PNG, while the UI only draws **16x16**. Before sending, the server shrinks it with the JDK's built-in `ImageIO`
to **32x32** (2x headroom against blur on high DPI):

| Item | Value |
|---|---|
| fm907.png | 500x500 / 36639 B → **32x32 / 566 B** (saves **98%**) |

- Uses `ImageIO` + `Graphics2D` high-quality interpolation, **with no new dependency**
- If scaling fails (headless / rare format) → **fall back to the original image**, so the icon never disappears
- **Must set `java.awt.headless=true`**: dedicated servers have no graphics environment
- Cached (the key includes the file modification time); cleared on `/radio reload`

##### 3. Architecture Q&A: network radio is server-side redistribution

The user asked "why does it feel like network radio is server-side redistribution rather than the client connecting directly" — **the feeling is correct**.

```
upstream HTTP stream → server StreamAudioSource (read thread) → AudioSubscription (pacing)
                     → S2C_AUDIO_CHUNK → client Mp3StreamDecoder → PcmSink → sound card
```

Three hard reasons:
1. **Multi-player sync**: each client's stream buffer depth differs → content drifts by seconds, and a live stream cannot seek, so it cannot be re-aligned afterwards
2. **ICY stripping**: the upstream injects metadata every N bytes; the server strips it once
3. **No internet needed on the client**: in restricted networks, a player connecting straight to the upstream fails

Cost: server upstream = bitrate x listener count; if the upstream dies, every player loses sound at once (this is the division of responsibility set out in Section O).

> ⚠️ **This subsection was superseded wholesale by section AC (Round 19, 2026-11-24)**:
> after the user clarified the intended design, the architecture became **client-direct** — audio bytes never pass
> through the server, which only maintains a subscription state machine. The three "hard reasons" held within the
> old architecture, and "no internet needed on the client" became an explicit precondition the user accepted
> (players must be able to reach the upstream). Kept for historical reference only — **do not build on it**.

#### AA. Round 17: shuffle mode "the first track is always the same"

**Symptom**: the user reported "with shuffle playback, the first track is always the same audio".

**Root cause**: round = `elapsed / roundTotalMillis`, and `elapsed` counts from the moment the server starts or reloads
→ **every session begins at round 0**. And round 0's order is uniquely determined by `mixSeed(seed, 0)`
(when seed is not set it is derived from the station id, which is even more fixed), so the first track is always the same one.

> This is not "shuffle not working" — **each round really is shuffled**, it just always starts from the same round.

**Fix**: added `roundOffset` (a random offset on the starting round), active only in `random` mode:

```java
this.roundOffset = random
    ? (int) Math.floorMod((System.currentTimeMillis() / 3_600_000L) * 2_654_435_761L, 1_000_003L)
    : 0;
```

- Multiplying by a large odd number spreads it out: with plain `now/3600000`, adjacent hours differ by only 1, and across 12 server starts only 4 distinct first tracks appeared;
  after multiplying, **all 5 in the pool are covered**
- **Stable within a single process** (computed once at construction, a `final` field), so it does not jump songs on every subscription
- **Synchronization is unaffected**: the timeline is only constructed on the **server** (zero client references),
  and all players share the same instance and the same `startEpochMillis`, so the round/position computed at any instant is still identical

**Companion fix**: `PlaylistTimeline.clearCache()`, called on config reload.
The timeline instance bakes in `roundOffset`, so reusing the old instance would produce "reloaded but the first track is still that one".

> **Follow-up fix (same day)**: the hour-based offset only has hour resolution, so two `/radio reload` commands
> **inside the same hour** landed on the *same* starting round — the shuffle did not change and playback resumed
> with what was already playing. `roundOffset` is now drawn per construction (`pickFreshRoundOffset`), and the
> candidate is rejected unless its first track differs from the previous build's first track, so every reload
> restarts a visibly different shuffled round. Sync is unaffected: the timeline still only exists on the server
> and the offset is frozen into the instance.

##### Architecture question verified along the way

The user asked "why does network radio feel like server-side redistribution rather than a direct client connection" — **the feeling was correct at the time**,
see Section Z part 3 (**superseded by section AC**: the architecture is now client-direct).

#### AB. Round 18: volume slider occluded at the bottom

**Symptom**: the user reported "the lower part of the volume slider is a bit occluded toward the top".

**Root cause**: `volumeBottom = height - 50`, while the bottom buttons start at `height - 30` and are 20 tall.
The slider panel background also expands 2px outward, so the panel's bottom edge reaches `height - 48`
and overlaps the button area (`height - 30`) by about 18px —
**the percentage text is drawn at `volumeBottom - 10`, exactly where the buttons cover it**.

**Fix**: `volumeBottom` changed to `height - 38`, derived from the bottom-up layout requirements:

```
button bottom edge      = height - 10
button top edge         = height - 30
leave a 6px gap         -> panel bottom edge limit = height - 36
panel background +2px   -> volumeBottom limit      = height - 38
```

Tested at four window heights (240 / 300 / 480 / 720), all satisfied:
- The gap between panel and buttons is always **6px** (no overlap)
- The percentage text's bottom is **2px** above the panel bottom (not flush)
- The slider track bottom is always above the text

The click/drag hit area (`mouseY <= volumeBottom`) and the volume mapping (`volumeBottom - 12`)
use the same field, so they stay consistent automatically with no separate change needed.

#### AC. Round 19: network-radio architecture overhaul — client-direct streaming (2026-11-24, the most important change this day)

##### The user's clarified end-to-end design (the basis for every change in this round)

> Join game → connect to the server → switch to an internet station → **lose the internet**
> (the upstream internet drops; the connection to the server stays fine the whole time) →
> the client buffer thins while the noise floor rises → after 4 seconds NOSIGNAL + blinking light
> + sustained noise floor → automatic reconnect to the upstream stream → network returns →
> the client reconnects the audio stream → the noise floor fades as the buffer refills → everything is normal again.

Key constraints (corrected by the user one by one; must be followed):
- **The server neither knows nor controls the client's light/NOSIGNAL** — it does not check the network and never announces TIMEOUT
- **To the server, an internet radio is just a subscription state machine** — audio bytes never pass through it
- **There are no heartbeat packets**; **the buffer lives on the client**
- The empty-volume packet (S2C_AUDIO_SILENCE) exists **to keep the client from misreading the preload gap as a fault**;
  it must **never trigger tuning noise** (an early change mistakenly wired it there; it was reverted)

##### Changes landed

| Item | Change |
|---|---|
| Server | Internet-radio subscribe only registers `STREAMING_SUBS` and replies OK (idempotent reuse keeps the same semantics); per-tick checks mark walked-away→TOO_FAR, removed→STOPPED; **the heartbeat mechanism was removed entirely** (Z.1's empty heartbeat chunks were superseded by S2C_AUDIO_SILENCE empty-volume packets, throttled at 200ms) |
| Client puller | New `ClientAudioPlayer.StreamFeed` puller thread: starts on subscription OK, connects to the upstream directly, **reusing `StreamAudioSource`** (ICY strip + exponential-backoff reconnect 1s→2s→4s→**8s cap**, unlimited retries, `sessionId++`) |
| Decoder rebuild | A session-id change = the upstream dropped and came back: the byte stream is not continuous, so the decoder must be rebuilt; the recovery path calls `onLinkRecovered()` (the noise floor fades out at its release rate) and **no longer** `beginTransitionSilence()` (otherwise: "noise cuts hard → wait through the silence window → sound returns") |
| Timeout | Client stall detection `STALL_TIMEOUT_MILLIS` 8s → **4s** (the user's chosen value) |
| Buffer depth | `DEPTH_SMOOTH_ALPHA` 0.02→**0.1** (TC 1s→200ms; otherwise the drain is only detected after silence has started); `BUFFER_THIN_PERMILLE` 550→**650** |
| Weak noise | **The sticky-trio was removed wholesale** (weakNoiseHeld etc.) + a **hunger gate**: the weak tier is only allowed "a real starvation in the last 3 seconds, or the buffer is absolutely bottomed out" — root cause below, "the eighth two-meanings bug" |
| Fault latch | `faultLatched`: still no data after the grace period → the level **locks at 1.0** (fixes "0.4-0.6-1.0-0.3 wandering during NOSIGNAL"); unlock = 25 consecutive healthy blocks or an explicit recovery entry |
| Starvation grace | Changed from "reset to zero" to "**freeze at the current gain**" (0.4 → hold → smooth climb to 1.0, no more 0.4→0→1.0 jump) |
| Light clearing | `clearClientFaultOnResume` also hooked into `handleUpstreamChunk` (internet stations never cleared the on-screen fault before); AutoSubscriber's stale lighting path now registers the PcmSink fault |
| Noise level | `NetworkNoise` PEAK **0.12** (the user rejected 0.25: "raising it would drown other stations; at most down to the stations' own tier") |

##### The eighth "one meaning, two implementations": the sticky weak noise floor

The user's measurement: "after 1.0 it just stays at 0.3 — you fixed the wrong thing entirely". The real root cause was the
**steady-state baseline ratchet misfiring forever**: a client-direct stream is 1:1 real-time; at connect time TCP dumps its
backlog at once, the ratcheted baseline jumps to that peak, and from then on real-time pacing keeps the buffer far below
that peak → the "relatively thinned" test is **permanently true**, and stickiness pins 0.4 forever.
The fix: relative thinning alone does not alarm — **it only alarms once it has actually caused starvation**.

#### AD. Round 20: gain delivered with the subscription + tuning-noise / fault-noise timing and loudness (evening of 2026-11-24)

##### 1. Internet radio too quiet → residual streamGains entry (the ninth two-meanings bug)

**Symptom**: internet radio plays far too quietly (both system volume and radio volume maxed).
Investigation: the `outputGain` config does not apply to internet radios; `convert()` has no attenuation — the gain chain is clean.
**The culprit**: while the block played a local station, the server had sent `outputGain=0.25`, which the client stored in
`streamGains`; after switching to an internet station **the server never sends another gain packet** (an internet station has
no audio chunks, so the pump's "send only on change" path can never fire for it) → 0.25 remains forever and the whole
signal chain is crushed to 25%.

Fix (two layers):
- **The server delivers the gain with the subscription reply** (the user's proposal; protocol-level root fix): in
  `subscribe()`, the internet-radio branch (both the new registration and the idempotent-reuse paths) sends
  `S2C_OUTPUT_GAIN` right after OK (`STREAM_GAIN=1.0`; a pass-through stream is never compressed)
- Client-side backstop: on stream creation in `handleChunk`/`handleUpstreamChunk` and on teardown in `stopInternal`,
  call `setStreamGain(key, 1.0)` to clear the residue (full gain triggers removal)

> **Follow-up fix (same session, Round 21)**: the two layers above cover the _static_ residue case, but a **race
> condition** made internet-radio volume still land at `outputGain` (e.g. 0.025) even after the fix.
> Root cause: the pump thread queues a `server.execute(λ)` that includes `writeOutputGain(0.025)`; between the
> moment it is queued and the moment the main thread executes it, the player switches to an internet station and the
> server sends `S2C_OUTPUT_GAIN(1.0)`; the queued λ then fires _after_ the 1.0, overwriting the client's gain map
> entry back to 0.025 — permanently, because the internet-radio path never sends another gain packet.
>
> Two-pronged fix (Round 21):
> - **Server**: inside the pump's `server.execute` lambda, skip `writeOutputGain` when the subscription is no longer
>   in `SUBSCRIPTIONS` (i.e. the player has already switched away). Guard: `if (SUBSCRIPTIONS.containsValue(sub))`
>   wraps the gain-send block.
> - **Client**: in `handleUpstreamChunk`, move `setStreamGain(key, 1.0F)` **outside** the `if (stream == null)`
>   block so it fires on _every_ upstream chunk, not just the first. Any stale 0.025 packet that arrives after the
>   first chunk is overwritten on the next chunk. For internet stations the gain is always 1.0, so this is idempotent.

##### 2. Tuning noise finalised (user measurements: "the noise only starts after the sound is gone", "peak loudness too low", "the fade-in is still too late")

| Item | Change |
|---|---|
| Trigger point | Moved from `S2C_SELECT_ACK` to **the instant the player picks a station** (before `selectStation` sends its packet): the static starts the moment you turn the knob, overlapping the still-playing old station; if the selection fails, the old audio keeps feeding and the noise fades naturally via the "stably connected" test |
| Loudness | `TuningNoise.HOLD_LEVEL` 0.075 → **0.25** (program tier): the old value was a tier below the program and could only fill silence, masking nothing |
| Deduplication | Repeat triggers inside the window changed from "ignore" to **`retrigger()`** (clears the stable counter and observation window, refreshes startedAt, **does not reset gain/filters**): under high ping the ACK can arrive later than the stable-connection window (~300ms), and the old station's residual audio fills the "connected" counter, retiring the noise early — the other half of "the noise only starts after the sound is gone" |

##### 3. Fault noise starts sooner (user measurement: "the disconnection noise also starts too late")

Old sequence: program stops → 600ms grace freeze → latch to full → 600ms attack —
nearly 1.2s of silence after the program stops before the noise is in place.

- `STARVE_GRACE_MILLIS` 600 → **300**: normal startup/track-change gaps are already covered by the explicit transition
  silence window and never reach the grace; whatever genuine starvation still lands here should not cost the player
  another 600ms of silence
- `NetworkNoise.ATTACK` 0.033 → **0.08** (0→full in about 250ms): still a perceivable ramp (not an instant jump);
  the total delay from silence to a full-level warning is pressed to about half a second; the release (1.5s fade on
  recovery) is untouched
- In the last ~125ms before the buffer bottoms out (the absolute-thin line) the weak noise already starts seeping in;
  with the faster attack, the noise is already rising before the program has fully stopped

#### AE. Round 21: stream-gain isolated by channel ownership (2026-11-25)

##### The user's question and what the review found

The user asked: "the local station's gain seems to pollute the internet station's gain". **After reviewing the whole
chain, the direction was reversed — and the real problem was worse**: the local station's own `outputGain` was being
**erased by the client**.

The full evidence chain:
1. The server's `pump()` sends the local gain `0.25` under a "send only on change" rule (`sub.noteSentGain(gain)`);
2. The client stores it in `streamGains`;
3. But the client called `setStreamGain(key, 1.0F)` on stream creation in `handleChunk`, on **every chunk** in
   `handleUpstreamChunk`, and on teardown in `stopInternal`, intending to "clear the residual stream gain";
4. Inside `setStreamGain`, `1.0` hits the "full gain needs no table entry" branch → the entry is `remove`d;
5. The server's `lastSentGain` is already 0.25, so it concludes "unchanged" and never resends;
6. Result: **the local station plays at 100% level for its whole lifetime — `outputGain` is entirely ineffective.**

##### Root cause (the tenth "one value, two meanings")

`1.0` was used simultaneously as a **legal business value** ("no attenuation") and as a **cleanup signal**
("no gain record"). The two meanings are unrelated, so the cleanup action was equivalent to cancelling the attenuation.
A second defect compounded it: the `S2C_OUTPUT_GAIN` payload was only `(BlockPos, float)` — **no stationId** — so the
client had no way to tell which channel a gain packet belonged to and could only blind-write per block. That is the
origin of every station-switch race.

##### Fix

| Layer | Change |
|---|---|
| Protocol | The `S2C_OUTPUT_GAIN` payload changed from `(BlockPos, float)` to `(BlockPos, stationId, float)`, carrying channel ownership |
| Version compatibility | **No compatibility layer is needed**: mismatched client/server builds cannot be used together anyway (Fabric's channel registration and registry handshake reject the mismatched side), so the payload can be evolved directly without a legacy-format fallback |
| Data structure | `PcmSink.streamGains` changed from `Map<Long, Float>` to `Map<Long, StreamGain>` (`record(stationId, gain)`) |
| Validation | `streamGainFor(blockPos, stationId)` compares channels and returns `1.0` when the record belongs to a different channel (a late packet from the previous station is discarded automatically) |
| Ownership | Added a `blockStations` map (each block's current channel), written by `ClientAudioPlayer` on stream creation / station switch |
| Cleanup | **All `setStreamGain(key, 1.0F)` calls removed**; invalidation now happens via channel comparison. Added `clearStreamGain(key)` so "remove the record" and "gain is 1.0" are strictly separate operations |

> ⚠️ **Never use `setStreamGain(key, 1.0F)` to mean "clean up"**. Call `clearStreamGain(key)` instead. This is the
> central lesson of this round.

> ⚠️ **Do not clear gain records in `handleChunk` / `stopInternal`**: the server's send order is "gain packet, then audio
> chunk", so by the time those branches run the new station's gain packet has **already landed** — clearing the table
> would wipe the gain that just arrived. Let the channel comparison invalidate the old record instead.

#### AF. Round 22: calibrating `outputGain` against a measured reference (2026-11-26)

##### What the user asked

Two pieces of material: a 34 s gameplay recording from *Arknights: Endfield* and a 254 s 320 kbps MP3.
The request: "make the radio at 100% sound like the BGM in this video", explicitly **by comparing spectra**.

##### Measurement method (reproducible)

1. Decode both to 48 kHz / stereo / s16le PCM;
2. Split into 8 bands with ffmpeg `highpass + lowpass` (poles=2):
   60-120 / 120-250 / 250-500 / 500-1k / 1k-2k / 2k-4k / 4k-8k / 8k-16k Hz;
3. Compute 100 ms frame RMS (dB) per band; on the video side take the median of the
   **lowest 25% of frames by overall level** — those have the least SFX, approximating "BGM only";
4. Take the whole-file median per band on the source side, then subtract band by band.

**Important**: high bands must not be downsampled to 8 kHz — aliasing pollutes the 4k-8k / 8k-16k bands.
The first pass hit exactly that, and the 8k-16k delta came out artificially high (-14.8 dB).

##### Results

| Band | 60-120 | 120-250 | 250-500 | 500-1k | 1k-2k | 2k-4k | 4k-8k | 8k-16k |
|---|---|---|---|---|---|---|---|---|
| source dBFS | -19.4 | -20.1 | -19.5 | -19.8 | -22.2 | -26.6 | -31.1 | -34.0 |
| video BGM dBFS | -41.6 | -39.7 | -39.1 | -41.5 | -43.4 | -45.9 | -51.4 | -48.8 |
| delta dB | -22.3 | -19.7 | -19.6 | -21.7 | -21.2 | -19.3 | -20.3 | -14.8 |

- Source integrated loudness **-9.9 LUFS** (true peak **+0.9 dBFS**, already clipping); video -25.4 LUFS (peak -11.0).
- All eight bands sit near -20 dB in parallel; tightening the "quiet frame" threshold from the 25th to the
  10th / 5th percentile moves the delta from -20.1 / -21.1 to **-21.8 dB** (less SFX contamination, closer
  to the true BGM level).
- Conclusion: **the game mix attenuates its BGM by roughly 20 dB relative to the master** — not a per-band trick.

##### One false premise, ruled out

The video's BGM is **not the same recording** as this MP3: three-band (150-600 / 600-1500 / 1500-3200 Hz)
envelope cross-correlation peaks at only **0.17 / 0.26 / 0.28** (identical audio would be above 0.9).
So only **level alignment** is possible, not sample-level subtraction. The conclusion is unaffected —
the user wanted a loudness reference, not content matching.

##### What was changed

| Item | Value |
|---|---|
| `DEFAULT_OUTPUT_GAIN` | `0.25` → **`0.10`** (-20 dB) |
| Target level | **-30 LUFS** at 100% volume |
| Verification | source at 0.10 gain measures **-29.9 LUFS**, true peak -19.1 dBFS (19 dB headroom, noise layers will not clip) |
| Config files | `run/config/radio-smile/stations.json` and the `serverconfig/…` sample both set to 0.10; README (both languages) sample and table updated |

##### Trade-off the user accepted

The user chose to **calibrate local stations only** (keeping the "internet radio is passed through untouched" convention),
target -30 LUFS. The resulting gap is documented in `RadioStationRegistry#outputGain`:

> With local at 0.10 (~-30 LUFS) and internet streams typically at ~-15 LUFS, **switching from a local station to a
> network station jumps about 15 dB louder**.

Two ways to remove the jump, both requiring a separate decision:
1. **Master gain** — make `outputGainFor` apply to `isStreaming()` too; one line, but it overturns "don't touch someone else's stream";
2. **Per-track loudness normalisation** — measure each file's integrated loudness during prewarm and derive the gain from
   "target − measured"; removes both master variance and the network gap, but changes the cache format and the gain path.

> ⚠️ **A fixed gain cannot adapt to master variance**: a -9.9 LUFS track attenuated by 20 dB lands exactly at -30, while a
> -15 LUFS track under the same 20 dB lands at -35. Loudness across a mixed playlist will still drift — an inherent limit of the 0.10 constant.

#### AG. Round 23: per-track loudness normalisation (evening of 2026-11-26)

##### What the user asked

"Write each track's attenuation into the cache metadata and normalise accordingly", and explicitly
**rejected the alternative**: having the client detect the current channel's level and normalise itself.

So: **the server measures, stores, computes and sends; the client only multiplies.**

##### Data flow

```
prewarm (background thread, once)
  source file --ffmpeg loudnorm--> input_i (integrated LUFS) + input_tp (true peak)
        └─→ cache/loudness/<first 24 chars of quickHash>.lufs   (lufs=… / tp=…)

playback (per audio chunk, O(1))
  current track --measured LUFS--> gain = 10^((target − lufs)/20) --S2C_OUTPUT_GAIN--> client
                                                                        (multiplied into the mix)
```

##### Key implementation points

| Location | Role |
|---|---|
| `LoudnessNormalizer` (new) | Measurement, metadata I/O, gain maths. **Attenuation only, never boosts** |
| `AudioSource.currentFile()` (new) | The source itself answers "which file is playing", instead of looking it up by `trackIndex` |
| `AudioTranscoder.sourceOf()` (new) | Reverse lookup: baked artefact → source file |
| `AudioStreamManager.outputGainFor()` | Asks the normaliser first; falls back to `outputGain` only when unmeasured |
| `RadioStationRegistry.targetLoudness` | Config key, default -30; `0` disables normalisation |
| `S2C_OUTPUT_GAIN` | **Protocol unchanged**, still `(BlockPos, stationId, float)`; resent when the track changes |

##### Two pitfalls worth remembering

> ⚠️ **A non-MP3 source plays the transcoded artefact, not the source file.**
> `PlaylistTimeline.streamFileOf()` returns `<base>-<hash12>.mp3` when the artefact exists, while the metadata
> is keyed by the **source** file's hash — hashing the artefact path never matches, and the failure is silent
> (the gain just stays at the fallback). `AudioTranscoder.BAKED_SOURCE` now records the mapping and
> `sourceOf()` resolves it. **Any code that looks up per-file metadata must go through `sourceOf()` first.**

> ⚠️ **The gain is clamped to 0.00~1.00, so do not try to exceed 1.**
> The client's `PcmSink.setStreamGain` clamps to `0~1` and treats "full gain" as "no attenuation" (it drops the
> table entry). A server value of 1.7 is silently clamped to 1.0 — normalisation looks like it works but nothing
> is raised. The user has confirmed "quiet material doesn't need boosting", so this class attenuates only.
> True peak is still stored, so a future boost decision would not require re-decoding the whole library.

##### End-to-end verification (2026-11-26 21:04, real server)

`gradlew runServer` → config loaded → background measurement → 5/5 succeeded (84.8 s, one-off).
Four metadata files (two tracks had identical content and share one, as expected from hash naming):

| Metadata | measured LUFS | normalised gain | output at the old fixed 0.10 | deviation |
|---|---|---|---|---|
| `17cffbb8…` | -12.98 | 0.141 | -32.98 | -3.0 |
| `616410df…` | -11.37 | 0.117 | -31.37 | -1.4 |
| `c8e22d78…` | -6.64 | 0.068 | -26.64 | +3.4 |
| `e388c53e…` | -6.31 (true peak +2.24) | 0.065 | -26.31 | +3.7 |

Under the fixed gain these four spanned **6.7 dB** (-33.0 to -26.3); after normalisation all land on -30.
The source of `e388c53e` has a true peak of +2.24 dBFS and is already clipping — which is exactly why
"measure each track" is necessary.

##### Known boundaries

- **Internet radio is excluded**: a passthrough live stream that clients pull directly, so the server never sees analysable content.
- **First start is slower**: one full decode per track (~17 s each, 85 s for five), serial on a background thread, non-blocking.
- **A one-chunk mismatch is possible at a track switch**: the gain is computed from `currentFile()` while chunks are queued,
  so the first chunk of a new track may carry the previous track's gain (~20 ms, inaudible).
- **Without ffmpeg it silently falls back to `outputGain`**, with one explicit WARN.
- Metadata depends on file content only: **renaming does not re-measure, changing content always does**
  (the hash covers file size plus the first 64 KB).

### Current build status

- **`build/libs/radio-smile-1.0.0.jar`, about 363 KB**
- `gradlew build` → BUILD SUCCESSFUL
- **git is initialized**, one commit per round; `main` is in sync with origin/main

### Awaiting user verification (ask these first next session)

> This list keeps only the **unverified** items. Anything that passed verification has moved down to "Historical verification" below.

**Round 23 (evening of 2026-11-26, per-track loudness normalisation)**:
- ⚠️ **Is the loudness across a mixed playlist actually aligned** — the core item. With the old fixed 0.10 the four
  test tracks spanned 6.7 dB (-33 to -26.3); they should all sit near -30 now. How to judge: play a list with
  several different sources back to back — no track should suddenly sound much louder or quieter.
- ⚠️ **Is `cache/loudness/` populated as expected** — after the first start the directory should contain
  `<hash>.lufs` files (two lines: `lufs=` / `tp=`), and the log should show one "实测完成 N/N" line.
- ⚠️ **The local → network jump is still there** — normalisation only covers local tracks; internet radio stays at
  1.0, so the relative gap remains (section AF). Levelling those too needs a separate decision.
- ⚠️ **Any artefact at a track switch** — the gain changes with the track, so in theory the first chunk could carry
  the previous track's gain (~20 ms, should be inaudible). If a "volume hiccup" is audible, the gain needs to
  travel with the chunk instead.

**Round 22 (evening of 2026-11-26, `outputGain` calibration)**:
- ⚠️ **Is a local station at 100% level with the video's BGM** — the core item. The default is now 0.10 (-20 dB),
  measured at -29.9 LUFS. How to judge: all in-game volumes maxed, standing within 4 blocks of the radio, a local
  station should feel close to the BGM in that Endfield recording (clearly below the track's native level, still
  clearly audible). Too quiet → raise it (0.13 ≈ -27.7 LUFS); too loud → lower it (0.08 ≈ -31.9 LUFS).
- ⚠️ **Volume jump when switching local → network station** — a known trade-off: with local at 0.10, network
  stations will sound about 15 dB louder. If the jump is unacceptable, choose between "master gain" and
  "per-track loudness normalisation" (see the end of section AF).
- ⚠️ **Loudness consistency across a mixed playlist** — a fixed gain cannot adapt to master variance: a -9.9 LUFS
  track lands exactly at -30, a -15 LUFS track at -35. If a few tracks are obviously quieter, that means
  normalisation is needed rather than more tweaking of this constant.

**Round 21 (morning of 2026-11-25)**:
- ⚠️ **Does the local stations' `outputGain` actually take effect** — the core fix of this round. Set
  `outputGain` to 0.25 in `stations.json`; local stations should be audibly one tier quieter than internet stations;
  setting it back to 1.0 should get louder immediately. Key check: listen to a local station for more than 10 seconds —
  the attenuation must persist (the old build restored it to 100% as soon as the first chunk arrived).
- ⚠️ **Is the local gain correct after a station switch** — internet → local, and local → local (different channels):
  the new station must get its own gain and must not inherit the previous one's.

**Round 21 (night of 2026-11-24)**:
- ⚠️ **Is the internet-radio volume now consistently full** — after the Round 20 fix a race condition still let the
  pump's stale 0.025 packet arrive after the subscription's 1.0, leaving the network station permanently quiet.
  The Round 21 fix adds a validity check in the pump lambda (server-side) and resets the gain on every upstream
  chunk (client-side). Test: switch local → internet → local → internet several times quickly; internet volume
  should always be at full gain, not quietly stuck at `outputGain`.

**Round 20 (evening of 2026-11-24)**:
- ⚠️ **Is the internet-radio volume back to full** — it used to be crushed to 25% by a residual 0.25 stream gain;
  the server now sends 1.0 with the subscription reply. Focus on the switch path (local station → internet station).
- ⚠️ **Tuning-noise feel** — the static should start the instant you pick a station and mask both the old station's
  fade-out and the new station's entrance (HOLD 0.25, program tier); if the peak feels too loud, it can be lowered.
- ⚠️ **Is the fault noise fast enough** — after the upstream drops, program-stop → noise at full should take about
  half a second; the NOSIGNAL light still follows the 4s stall timeout (the user's chosen value); confirm separately
  if the light should move with the sound.

**Round 19 (architecture overhaul)**:
- ⚠️ **Full disconnection chain** — switch to an internet station → drop the internet: the noise floor rises as the
  buffer thins, after 4 seconds the light blinks NOSIGNAL with a sustained noise floor (level constant at 1.0, no
  wandering) → on recovery the noise fades over 1.5s, the light clears, the LCD returns to rate, playback resumes,
  with no loudness jumps anywhere.
- ⚠️ **No more false alarms on track change** — custom-station track changes are declared by empty-volume packets
  (heartbeats removed); changing tracks should not flash NOSIGNAL or play the noise floor.

**Historical verification (passed, recorded for reference)**:
- ✅ Fault display position, black text, LCD background unchanged (Round 3)
- ✅ Selection box hugs the body, entities can pass through (Round 9)
- ✅ LCD shows the frequency during normal playback (Round 8)
- ✅ Logs no longer spam (Round 7, "audio recovered" only logs once on a real recovery)
- ✅ "Dropping half the packets" is gone (Round 13 pacing fix, confirmed by the user)
- ✅ Volume 5% steps / txt first-line boolean / ffmpeg path config (Rounds 13-14)
- ✅ The jump/pop at the station switch is gone (Round 20 digital-silence fix, confirmed by the user)

### Known limitations

- Network radio (live streams) **must be MP3**, and cannot be seeked precisely; it can only play sequentially from the moment of connection
- **Internet radios are streamed client-direct** (since section AC): **the player's client must be able to reach the upstream**,
  and a direct connection fails on restricted networks (the server-proxy design was retired through the Z.3 → AC evolution)
- **Live streams cannot go into txt**: without `Content-Length` they cannot be cached or transcoded,
  and if written they are auto-commented out (see Section T). A live stream should be configured directly as the station's `file`
- **Transcoding depends hard on ffmpeg**: without it, every non-MP3 station is refused (no silent downgrade)
- First start has to wait for transcoding to finish (tens of seconds for large files); afterwards the cache makes it instant
- Playlist track changes are not seamless: the decoder is rebuilt on a track change, leaving a very short gap between tracks
- **HLS/AAC streams are not supported** (evaluated; the user decided not to do it)
- Temporary signed links (such as Bilibili URLs with a `deadline`) **expire after about 2 hours**:
  cached copies keep playing, but re-downloading requires a fresh link
- **Chinese file names** depend on the JVM's `sun.jnu.encoding` (Linux defaults to UTF-8 and works)

---

## 9. Test Environment

Test configuration under `run/`:

```
run/world/serverconfig/radio-smile/
├── stations.json     3 stations (tiehen / testaudio / qtfm)
├── audio/
│   ├── qtfm_tiehen.ogg   22.5 MB (transcoded from Bilibili "Tiehen Radio #02")
│   └── test_station.ogg  14.9 MB
└── cache/
    └── manifest.json
```

**Current actual test config** (`run/config/radio-smile/`):

| id | Type | rate |
|---|---|---|
| `tiehen` | Local OGG (`qtfm_tiehen.ogg`, 23MB) | FM105.7 |
| `fm907` | Live network stream `lhttp-hw.qtfm.cn/live/15318146/64k.mp3` | FM90.7 |
| `realtest` | **txt playlist** (1 local + 4 network: NetEase Cloud mp3 x2 / Bilibili m4s x2) | FM101.7 |
| `mixed` | **txt mixed playlist** (2 local, demonstrating the new first-line format) | FM100.0 |

Both txt files now use the **new first-line boolean** (`false`), and the top level of `stations.json` already carries the `ffmpeg` field.

> `realtest.txt` is the key test material for verifying network caching / transcoding / timing.
> Note that the Bilibili link carries a `deadline` parameter and **expires after about 2 hours**;
> after that the cached local file still plays, but re-downloading requires a fresh link.

Client config: `maxBandwidthKbps=1024`, `maxChunkBytes=8192`, `bufferMillis=1000`

---

## 10. How to Resume Work Quickly

1. `cd "C:\Projects\Smile's Radio mod"`
2. `gradlew build` to confirm the build passes
3. `gradlew runServer` to verify server startup + transcode preheat logs
4. `gradlew runClient` (with `--quickPlaySingleplayer` to drop straight into the world)
5. Key log keywords (in troubleshooting order):

   | Keyword | What it confirms |
   |---|---|
   | station config loaded | Config location and the number of invalid stations (including "plus N invalid configs hidden") |
   | migrated from old location | Whether the old save config was successfully moved to `config/` |
   | transcode preheat | ffmpeg preheat progress; "ffmpeg unavailable" means the dependency is missing |
   | ffmpeg specified by config | Whether the top-level `ffmpeg` path took effect |
   | connected to network radio | Live stream connection established |
   | client started pulling internet-radio stream | The **client-direct** puller thread started (section AC) |
   | network stream reconnected | The upstream dropped and came back; the decoder was rebuilt (sessionId changed) |
   | internet-radio subscription registered | The server's subscription state machine registered (no streaming) |
   | network audio cached locally | Network file download complete |
   | txt playlist preheated | Number of network entries in the txt |
   | MP3 seek to + playlist seek | The exact seek on subscription (the basis of multi-player sync) |
   | [auto-subscribe] | Auto-subscription triggered |
   | sound card opened | Audio device and buffer parameters |
   | jitter buffer overflow | **⚠️ Feeding faster than consuming** — added in Round 13; seeing it means pacing is still wrong |

6. To verify "whether the timing is correct", the most effective method is **screen recording + inspecting the spectrogram** (see Section V):
   generate it with `ffmpeg -i rec.mp4 -lavfi showspectrumpic` —
   **regular comb-like dropouts** mean packet loss or bad pacing, and normal music never has that structure.

---

## 11. Possible Next Steps

| Priority | Item | Notes |
|---|---|---|
| **High** | Re-verify the disconnection chain and internet-radio volume | Rounds 19-20 changed a lot; see the "Awaiting verification" section |
| High | Verify long-run playback continuity | Across tracks, long unattended runs, network jitter |
| Medium | (Proposed earlier, not yet done) `v1.0.0` tag / GitHub Release / README Credits section | Do it whenever the user agrees |
| Medium | Optimize AutoSubscriber scan cost | Currently scans 81 chunks every 0.5s |
| Medium | Playback progress UI | The current UI has no progress indicator |
| Medium | Fault display font size | The green area is 46x13 pixels, and `NOSIGNAL` (8 chars) is already at the width limit; |
|    |  | going bigger requires raising the texture to 512x512 (the user already knows) |
| Low | HLS/AAC support | Technically verified feasible (jaad 0.9.4); the user decided to defer |
| Low | Client-defined stations | Network streams only; local files are limited to the built-in server |
| Low | List paging / scroll performance | Only worth considering with a lot of stations (>200) |

---

## 12. Reminders for the Next Session

**This project's bugs share one common pattern: a single function or field carries two meanings.** This has already been fixed 9 times:

| Function | Confused meanings | Consequence |
|---|---|---|
| Anchor in `AudioSubscription.markSent()` | "position the read thread has read ahead to" vs "position already sent to the client" | 4-second dead air after the first packet |
| `isJitterBufferThinned()` with no audio source | "idle, nothing selected" vs "fault, cannot get data" | The noise floor fails to play when it should |
| `ClientAudioPlayer.stop()` | "the user no longer wants it" vs "server fault" | The noise floor is treated as the user switching the station off |
| Anchor in `overlayEffects()` | "attribution of the tuning sound effect" vs "attribution of a link fault" | No noise on a disconnect (Section K) |
| `PcmSink.stop()` | "the user no longer wants it" vs "fault mute" | The unfinished second half of Section D |
| `ClientAudioPlayer.STREAMS` | "actually receiving a stream" vs "the player clicked confirm" | Fails silently before the first chunk / lit up by late packets after switching off |
| Track list in `PlaylistTimeline` | "playable" vs "configured" | Untranscoded files enter the timeline → **the whole playlist's timing goes wrong** (Section V) |
| `S2C_AUDIO_SILENCE` receiver | "declare silence to prevent misjudgment" vs "mask the track-change jump with noise" | The empty-volume packet falsely triggered tuning noise; reverted (section AC) |
| Lifetime of `streamGains` entries | "the server keeps sending the gain with every chunk" vs "an internet station never gets another packet" | A local station's 0.25 residue silently crushed the internet-radio volume to 25% (section AD) |
| Pump `server.execute` lambda timing | "subscription is still active when the lambda fires" vs "player may have switched away while the lambda was queued" | A stale 0.025 gain packet arrived after the subscription's 1.0, overwriting it — internet-radio volume stuck at `outputGain` (section AD follow-up, Round 21) |
| `1.0` in `streamGains` | "no attenuation (a legal business value)" vs "no gain record (a cleanup signal)" | The client wiped the local stations' `outputGain`, making the compression config entirely ineffective (section AE) |

> The eighth through tenth all happened on 2026-11-24/25: the eighth made the level sit at "0.3 forever after 1.0"
> during NOSIGNAL, the ninth silently crushed the internet-radio volume to 25%, and the tenth conversely wiped the
> local stations' `outputGain` to 100% — each corrected by a single sentence from the user. **Read the code and confirm
> the lifetime before changing anything; never fix on a guess** — and be especially wary of "using a legal business
> value as a cleanup signal".

**Before changing anything, ask: how many places with different meanings call this function?**
If the answer is not 1, split the entry point first, rather than adding conditionals inside the function.

Three more hard constraints:
1. **Do not touch the relationship between sound effects and the buffer** — see the three pitfalls in Section B
2. **Do not verify with any build method other than gradle** — an ad-hoc javac classpath does not match loom's, and easily produces false positives
3. **Internet-radio architecture convention (section AC, explicit from the user)** — audio never passes through the server;
   the server only maintains a subscription state machine and neither knows nor controls the client's light/NOSIGNAL;
   there are no heartbeat packets; the buffer lives on the client.
   Any design that puts "checking the upstream network" back on the server violates this

The verification scripts live in `build-verify/` (including REPORT.md and sim/) and are repeatable.
This round added `build-verify/audio/` (noise floor / ERR state machine simulation, `run-verify.ps1` → `VERIFY_EXIT=0`)
and `build-verify/geom/` (decompiled output of the vanilla jar, used to verify UV / rotation / font matrices).

### Version control (since 2026-11-23)

`git` is initialized, with **one commit per round of fixes** (English early on, Chinese recently). Common commands:

```powershell
git log --oneline                  # history of the twenty rounds of fixes
git log --oneline -S "keyword"     # find when a function/field was introduced
git diff                           # view uncommitted changes
git show <sha> --stat              # see which files a commit changed
git show <sha> -- <file>           # see a commit's specific changes to a file
```

> When investigating "why does this behave the way it does", `git log -S` is faster than reading the HANDOVER:
> the commit messages record each round's core changes, so you can pinpoint the round that introduced it.

> ⚠️ **Do not be surprised by the decompiled output in `build-verify/geom/`**: it is one-off research material,
> not small but very valuable (next time you touch rendering or UV, look it up directly instead of decompiling again).

---

## Appendix: Installing dependencies

```groovy
// key build.gradle configuration
repositories {
    mavenLocal()  // smile_libbox
    maven { url = 'https://api.modrinth.com/maven' }
    maven { url = 'https://maven.terraformersmc.com/releases/' }
}

dependencies {
    modImplementation "smile_libbox:smile_libbox:1.0.26+1.20.1"
    modCompileOnly("maven.modrinth:modmenu:7.2.2") { exclude group: 'net.fabricmc.fabric-api' }
    include implementation("javazoom:jlayer:1.0.1")
}
```

smile_libbox 1.0.26 must be built from the `C:\Projects\Smile's Lib Box` project and published to mavenLocal
(`gradlew :1.20.1:publishToMavenLocal`).
