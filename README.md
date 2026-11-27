# Smile's Radio

**English** | [简体中文](README.zh-CN.md)

A **radio block that actually plays broadcasts** for Minecraft 1.20.1 (Fabric).

Place a radio, right-click to tune in, and you hear the stations your server configured — local
playlists, internet radio, synchronized multiplayer playback, spatial audio, and an LCD that shows
an error when the signal dies.

---

## Features

| Feature | Description |
|---|---|
| Radio block | Slim form factor (Arknights-inspired), faces the player when placed, needs solid support below |
| Station picker | Search, scrollable list, volume slider (5% steps), scan, confirm |
| Local audio | Single-track loop / playlist (natural filename order / shuffled) |
| Internet radio | Direct connection to a live stream (MP3) |
| **txt redirect playlists** | One txt file mixing local files and remote URLs |
| **Remote audio cache** | Remote URLs are downloaded, transcoded to MP3 and cached locally, with a lightweight proof file |
| Multiplayer sync | Every player hears the same content at the same moment |
| Spatialization | Distance attenuation (audible within 48 blocks) + constant-power L/R panning |
| Fault display | Red light blinking + LCD showing `NOSIGNAL` |
| Normal display | LCD shows the station frequency (e.g. `FM90.7`) |
| Sound effects | Tuning noise on station change, static on a bad network link |

---

## Installation

1. Install Minecraft **1.20.1** + **Fabric Loader** (>= 0.20.1)
2. Add the required dependencies:
   - [Fabric API](https://modrinth.com/mod/fabric-api) (>= 0.92.12+1.20.1)
   - **smile_libbox** 1.0.26+1.20.1 (the config screen depends on it)
3. Drop `radio-smile-1.0.0.jar` into `mods/`
4. **Optional**: install [ModMenu](https://modrinth.com/mod/modmenu) to change client settings in game

### Optional: ffmpeg

**Non-MP3 audio (ogg / flac / wav ...) needs ffmpeg to be transcoded** before it can play.

- Installed and on `PATH` -> works out of the box
- Not installed -> non-MP3 stations are rejected explicitly (never silently degraded into broken audio)
- If ffmpeg is not on `PATH`, point the `ffmpeg` field in the config at an absolute path

---

## Configuration

### Server: `config/radio-smile/stations.json`

> Location: **game root/config/radio-smile/** (global, independent of the world save)
> Configs from older versions stored in `serverconfig/radio-smile/` inside the world are **migrated automatically**.

```json
{
  "ffmpeg": "",
  "targetLoudness": -19,
  "stations": [
    {
      "id": "tiehen",
      "name": "Tiehen Radio #02",
      "icon": "fm907.png",
      "file": "qtfm_tiehen.ogg",
      "mode": "block",
      "rate": "FM105.7"
    },
    {
      "id": "fm907",
      "name": "Hangzhou Pop Music Radio",
      "icon": "fm907.png",
      "file": "https://lhttp-hw.qtfm.cn/live/15318146/64k.mp3",
      "mode": "block",
      "rate": "FM90.7"
    },
    {
      "id": "mixlist",
      "name": "Mixed playlist",
      "icon": "",
      "file": "mylist.txt",
      "mode": "sequence",
      "rate": "FM101.7"
    }
  ]
}
```

#### Top-level fields (outside the station list)

| Field | Description |
|---|---|
| `ffmpeg` | Path to the ffmpeg executable. **Leave empty to use ffmpeg from `PATH`**; only set it when ffmpeg is not on `PATH` or you need a specific build (e.g. `C:\\tools\\ffmpeg\\bin\\ffmpeg.exe` or `/usr/local/bin/ffmpeg`) |
| `targetLoudness` | Target level for **per-track loudness normalisation** (LUFS, default `-19`; set `0` to disable). When enabled the server measures every local track with ffmpeg during prewarm and stores the result under `cache/loudness/` (named by file content hash); at playback the gain is derived as "target − measured". **This lines up tracks from different masters**, which a single fixed gain cannot do (so there is no `outputGain` anymore). The default `-19` is the **measured level of an internet radio stream**: local tracks are aligned *to* internet radio, while the stream itself is passed through untouched. Attenuation only, never boosting: tracks already quieter than the target stay as they are (gain is clamped to 0~1). Changing this value takes effect immediately with no re-measurement; to re-measure a track, delete its entry under `cache/loudness/` and reload. Setting `0` plays local tracks **raw, with no processing at all** |

#### Station fields

| Field | Required | Description |
|---|---|---|
| `id` | Yes | Unique identifier. **`example` is reserved**: entries with that id are not loaded |
| `name` | Yes | Display name |
| `file` | Yes | Single file / folder (playlist) / `.txt` (redirect) / `http(s)` live stream |
| `icon` | | Icon filename (placed in `icons/`) |
| `mode` | | See the table below, defaults to single-track loop |
| `rate` | | Frequency/slot shown on the LCD and at the right of the list, e.g. `FM90.7`, `Episode 1`. Empty shows `NETWORK` |
| `seed` | | Seed for shuffling; derived from the id when omitted (keeps every player in sync) |

#### `mode` (playback mode)

| Mode | Accepted values |
|---|---|
| **Single-track loop** | `block` / `single` / `false` / `0` |
| **Sequential loop** (plays the whole list in order, then restarts) | `sequence` / `order` / `loop` / `true` / `1` |
| **Shuffle** (reshuffled every round, identical order for every player within a round) | `random` / `shuffle` |

> Case-insensitive, leading/trailing whitespace allowed. Omitting `mode` means single-track loop.
> When `file` points at a `.txt` the station **cycles through the list even without `mode`**; whether it shuffles
> is decided by the txt's first line.

### Directory layout

```
config/radio-smile/
├── stations.json        station list (+ top-level ffmpeg path)
├── audio/               raw audio / folder playlists / .txt redirect lists
├── icons/               station icons
└── cache/               generated automatically, safe to delete
    ├── manifest.json    audio hash manifest (lightweight verification)
    ├── baked/           transcoded MP3 (ffmpeg output)
    └── remote/          remote audio cache + .proof verification files
```

---

## txt redirect playlists

Point `file` at a `.txt` (also `.list` / `.m3u` / `.m3u8`) to chain several sources into one playlist.

```text
# First line: shuffle switch (the whole line may be omitted = false)
false

# One source per line from here on
song_a.mp3
folder/song_b.ogg
https://example.com/song.mp3
```

### First line: shuffle

| Value | Meaning |
|---|---|
| `false` / `0` / omitted | **No shuffle**: strict top-to-bottom file order (default) |
| `true` / `1` | **Shuffle**: reshuffled every round, identical order for every player within a round |

> Legacy spellings still work: `random`/`shuffle` = shuffle, `sequence`/`order`/`loop` = no shuffle.

### Rules

- **Without shuffle**: playback order = top to bottom in the file; **with shuffle**: reshuffled at the start of every round
- Blank lines and lines starting with `#` are ignored; trailing comments are supported (whitespace required before `#`)
- Local paths are tried against three bases in order: **the txt's own directory → `audio/` → the config root**
  (so `../hit.mp3` inside `audio/lists/rock.txt` points at `audio/hit.mp3`, and `audio/01.mp3` also resolves)
- **Remote URLs are downloaded, transcoded to MP3 and then take part in playback** (that is what gives them a definite duration and lets them join the sync timeline)

### Dead entries are commented out automatically

When the startup check finds an entry that simply cannot be played, the mod **comments that line out and states the
reason**, instead of only spamming the log:

```text
# [已禁用：文件不存在] gone.mp3
# [已禁用：HTTP 403，源文件已失效] https://.../expired.mp3
# [已禁用：流式传输，无法缓存] https://.../live.mp3
```

- The original text is kept verbatim; **delete the `# [已禁用…] ` prefix** to restore the line once it is fixed;
- Remote sources are only commented out on **deterministic 4xx** (400/401/403/404/410) — an expired signed link
  typically answers 403. **408 / 429 / 5xx / timeouts** are treated as transient: logged, file untouched;
- Local files are commented out only when missing, and **only during the startup check** (never mid-playback);
- **If not a single entry resolves, nothing is touched** (WARN only) — all-missing almost always means a wrong path,
  not that every file vanished;
- A remote entry that already has a complete local copy is **never** commented out — an upstream failure does not stop it
  from playing;
- Commenting is **idempotent**: prefixes are never stacked.

---

## Remote audio and caching

### Live streams vs. finite audio

| Type | Detection | Handling |
|---|---|---|
| **Live stream** (unbounded) | no `Content-Length` | **Cannot go into a txt**, only usable as a station `file` |
| **Finite audio file** | has `Content-Length` | Can go into a txt; downloaded, cached, transcoded to MP3, then played |

If a live stream URL appears in a txt, the mod **comments that line out automatically** and states why:

```text
# [disabled: streaming source cannot be cached] https://x/live.mp3
```

### Cache and verification

- Every remote audio file is downloaded into `cache/remote/`
- A few-KB **`.proof`** file is written alongside it (first 8KB of the file + total size)
- On the next start only a Range request for the header is needed — **no re-download**
- When the link expires (e.g. a temporary signed URL with `deadline`) the **existing cache keeps being used**,
  because the local file is complete

> Downloads are transcoded to MP3 automatically. Entries that are not finished stay **pending**:
> reaching that line in the playlist **skips straight to the next track** while the download continues in the background.

---

## Client configuration

### ModMenu screen

| Option | Default | Description |
|---|---|---|
| `enableStreaming` | true | Whether to receive the audio stream |
| `maxBandwidthKbps` | 64 | Expected bandwidth |
| `maxChunkBytes` | 8192 | Upper bound for a single chunk |
| `bufferMillis` | 1000 | Jitter buffer length (larger = more resilient, higher latency) |
| `showNetworkStats` | false | Show network debug information |

You can also edit `config/radio-smile-client.json` directly.

---

## In-game usage

| Action | Effect |
|---|---|
| Place the radio | Automatically faces the player |
| **Right-click** (with any item in hand) | Opens the station picker (never places a block by mistake) |
| Click a station in the list | Selects it; **clicking the selected entry again deselects it** |
| Scroll wheel / Arrow up, down | Moves the selection |
| Enter | Confirms and closes |
| Volume slider | 5% per drag step |
| Scan | Re-fetches the station list (does **not** reconnect the stream that is currently playing) |

**Listening range is 48 blocks**; beyond that the server stops pushing audio.

### Fault display

On a fault the LCD shows `NOSIGNAL` and the red light blinks:

| Situation | Behaviour |
|---|---|
| Upstream internet radio died | Continuous static + `NOSIGNAL` |
| Station config invalid / removed | Continuous static + `NOSIGNAL` |
| Player walked away | Quietly stops (not a fault) |
| Local station cannot read its audio | Logged only; the screen does not claim "no signal" |

> "No signal" belongs to **internet radio only** — a missing local file has no signal to lose.

### Commands

| Command | Permission | Description |
|---|---|---|
| `/radio reload` | 2 | Reloads the config and rebuilds all subscriptions (progress reset) |
| `/radio reload <station>` | 2 | Refreshes one station only; other stations keep playing. The reshuffled round never starts with the track that was playing |
| `/radio play <station> <track>` | 2 | Jumps the station to track `n` of the round it is currently playing (also works for shuffled stations; `n` is the position in that round's order) |
| `/reload` | 2 | Also reloads the station config |

Both `<station>` and `<track>` are tab-completable: station ids come from `stations.json`, track numbers from the station's actual track count.

---

## Known limitations

- Internet live streams **must be MP3** and cannot be seeked; playback is sequential from the moment of connection
- Non-MP3 local audio **hard-depends on ffmpeg**
- The first start waits for transcoding (tens of seconds for large files); afterwards it opens instantly from cache
- Track changes in a playlist are not gapless: the decoder is rebuilt, leaving a very short gap
- HLS / HE-AAC streams are not supported
- Live streams **cannot** go into a txt playlist (no duration, so they cannot join the sync timeline)

---

## Building from source

```bash
./gradlew build
# artifact: build/libs/radio-smile-1.0.0.jar
```

Requires JDK 17+.

---

## Developer documentation

The engineering handover covers the architecture, network protocol, the audio pipeline and the
pitfalls that have already been hit (English: [HANDOVER.md](HANDOVER.md), Chinese:
[HANDOVER.zh-CN.md](HANDOVER.zh-CN.md)).

---

## License

CC0-1.0
