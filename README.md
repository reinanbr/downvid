<p align="center"><img src="assets/brand/logo.svg" width="112" alt="DownVid logo"></p>

<h1 align="center">DownVid</h1>

Open-source Android downloader for videos, music and photos from public links
(YouTube, YouTube Music, Instagram, Threads, TikTok, X/Twitter, Facebook, SoundCloud,
plain web pages with MP4/HLS players) — and music links from Spotify, Deezer
and Apple Music, matched to the same recording on YouTube Music.

Flutter UI, a **Go core** compiled as a native library (downloads, HLS,
queue, metadata) and a thin Kotlin layer for Android-only APIs.

> **Use DownVid only with content that is your own, public, or that you are
> authorized to download**, respecting each platform's terms of service and
> copyright. You are responsible for how you use the downloaded files.

---

## Contents

- [Features](#features)
- [How it works](#how-it-works)
  - [Architecture](#architecture)
  - [From a shared link to the download sheet](#from-a-shared-link-to-the-download-sheet)
  - [Extractors](#extractors)
  - [Downloading and muxing](#downloading-and-muxing)
  - [Audio only](#audio-only)
  - [Instagram](#instagram)
  - [Queue, resume and background downloads](#queue-resume-and-background-downloads)
  - [Storage and permissions](#storage-and-permissions)
  - [Privacy](#privacy)
- [Building](#building)
- [Testing](#testing)
- [Releases and CI](#releases-and-ci)
- [Project layout](#project-layout)
- [Debugging](#debugging)
- [Limitations](#limitations)
- [License and third-party software](#license-and-third-party-software)

---

## Features

- **Share target**: share a link from any app → a translucent bottom sheet
  opens over that app (the full app does not open) with thumbnail, title,
  duration, quality list, estimated size and a download button.
- **Copied links**: when DownVid comes to the foreground it offers a link you
  just copied; a **Quick Settings tile "Paste & download"** does the same
  from anywhere.
- **Video**: one option per resolution (H.264 up to 1080p for compatibility,
  VP9/AV1 above), always saved as **MP4** — HLS (`.m3u8`, including AES-128)
  and separate video/audio streams are merged without re-encoding.
- **Audio only**: original M4A (no conversion) or MP3 V0/320 with title,
  artist, album, year, track number and an embedded square cover.
- **Photos**: Instagram photos and carousels (pick the items with checkboxes).
- **Generic pages**: finds every MP4/HLS video on a web page — in the HTML,
  inline scripts, metadata, iframes, and by watching what the page's player
  requests in a hidden WebView.
- **Downloads screen** (tabs Videos / Music / Photos): persistent queue,
  pause/resume, resume after the app is killed or the network drops,
  automatic renewal of expired links, history with open/share/delete.
- **Background downloads** with progress notifications (pause/cancel).
- **Settings**: default video quality, default audio format, "music links
  open in Audio only", simultaneous downloads, copied-link detection, yt-dlp
  version and update.

## How it works

### Architecture

```
┌──────────────────────────── Flutter (Dart) ────────────────────────────┐
│ share sheet · home · downloads screen · settings                       │
│ ExtractController: picks the extraction route, merges options          │
└───────┬──────────────────────────────┬─────────────────────────────────┘
        │ dart:ffi (ffigen bindings)   │ MethodChannels
┌───────▼───────────────────┐  ┌───────▼─────────────────────────────────┐
│ Go core  libdvcore.so     │  │ Kotlin                                  │
│ scan     page analysis    │  │ ShareActivity / PasteTileService        │
│ ytdlp    yt-dlp → options │  │ YtDlp + YtDlpServer (resident yt-dlp)   │
│ instagram posts + Threads │  │ PageSniffer (hidden WebView)            │
│ music    tags, matching   │  │ InstagramQuery / InstagramLogin         │
│ hls      m3u8, AES-128    │  │ DownloadService (foreground service)    │
│ netx     ranged download  │◄─┤ MediaSaver (MediaStore)                 │
│ media    remux / ffmpeg   │JNI│ Settings, clipboard                     │
│ jobs     persistent queue │  └─────────────────────────────────────────┘
└───────────────────────────┘
```

- The Go core is built with `-buildmode=c-shared` for `arm64-v8a`,
  `armeabi-v7a` and `x86_64` (`scripts/build_go.sh`, run automatically by
  Gradle). Dart calls it through `dart:ffi`; every string returned by Go is
  freed with `DV_Free`, results are `{"ok":…,"data"|"error":…}` JSON, and
  progress events reach Dart through `NativeCallable.listener`. Blocking
  calls run in short-lived isolates.
- Kotlin loads **the same** `.so` through JNI (`DvCore`), so the foreground
  service observes the very same download queue without a Flutter engine.
- [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)
  provides Python, yt-dlp, QuickJS and ffmpeg for Android. DownVid only uses
  yt-dlp to **extract** (`-J`); downloading and muxing happen in Go.

### From a shared link to the download sheet

`ShareActivity` receives `ACTION_SEND text/plain`, runs the `shareMain` Dart
entry point in a transparent window, and starts yt-dlp extraction right away
(before Flutter has booted). The link is classified (`link_parser.dart`) and
`ExtractController` picks a route:

| Link | Route |
|---|---|
| Spotify / Deezer / Apple Music | Read track metadata from the service → search YouTube Music → accept the first result with the same duration (±5 s) |
| Instagram post/reel/story | Instagram's own post query in a WebView (see [Instagram](#instagram)); yt-dlp as fallback |
| Threads post | The post page opened in a WebView; Go reads the post from the data the page embeds (Instagram's media schema, quotes/reposts included), or from the post data the page fetches; optional login (Threads' official page) for posts hidden from visitors |
| YouTube, TikTok, X, Facebook, SoundCloud… | yt-dlp; if it does not support the link, falls back to the generic scan |
| Any other page | Generic scan **and** yt-dlp in parallel (yt-dlp has extractors for thousands of sites and embedded players) |

The **generic scan** has two halves running in parallel:

1. **Static (Go, `scan`)**: downloads the HTML and extracts candidates from
   `<video>`/`<source>`, `og:video`, JSON-LD `contentUrl`, absolute and
   relative URLs ending in `.mp4/.m3u8/.m4v/.mov` inside scripts (with
   `\/`, `/` and percent-encoding undone), and iframes one level deep
   (with the iframe as `Referer`).
2. **Dynamic (Kotlin, `PageSniffer`)**: loads the page in a hidden WebView
   (laid out behind the Flutter view, almost transparent), mutes and plays
   videos, presses common "big play" buttons and records every media request
   (`shouldInterceptRequest`, Resource Timing). This finds players that load
   their streams with JavaScript (hls.js, video.js, JW Player…).

Every candidate is **resolved** in Go: HLS master playlists expand into one
option per variant (resolution, bitrate, estimated size from bandwidth ×
duration), live streams and DRM are rejected with a reason, direct files are
probed (`Range: bytes=0-0`) for type and size; WebM/OGG are skipped because
they cannot be saved as MP4 without re-encoding, and files under 1 MB
(previews, stream fragments) are hidden when the page has a real video.

### Extractors

- **yt-dlp** runs inside a **resident Python process** (`YtDlpServer.kt` +
  `assets/ytdlp_server.py`): starting Python and importing yt-dlp costs ~7 s
  on a phone, so the server pays it once (right after install/update via
  `UpdateReceiver`, or when the app opens) and then answers JSON requests over
  stdin/stdout; it stops after 5 idle minutes. If it fails, a one-shot
  `yt-dlp -J` process is used.
- YouTube's JavaScript challenges are solved with the **QuickJS** binary that
  ships with youtubedl-android (`--js-runtimes quickjs:…`); yt-dlp updates
  itself to the latest stable release every 3 days.
- `go/internal/ytdlp` turns yt-dlp's JSON into options: one per height,
  preferring H.264 up to 1080p (plays everywhere), VP9 above (wide hardware
  support), direct HTTPS over HLS (faster), the best AAC audio for merges, and
  watermark-free copies; Facebook's progressive `hd`/`sd` files are kept as
  "HD · MP4"/"SD · MP4". yt-dlp's per-format headers and cookies are passed
  to the downloader (quoted cookie values are unquoted — TikTok rejects them
  otherwise). yt-dlp errors are translated into short messages per platform
  (private, age-restricted, login required, rate-limited, geo-blocked, DRM…).

### Downloading and muxing

- **Direct files** (`netx`): parallel HTTP Range download in 4 parts for
  files ≥ 4 MB, with each request capped at yt-dlp's `http_chunk_size`
  (10 MB for YouTube, which throttles larger ranges), retries with backoff
  that resume from the exact byte. On the desktop this is ~35% faster than
  yt-dlp's own downloader for the same file.
- **Separate video + audio** ("merge"): both streams are downloaded in
  parallel, then `ffmpeg -c copy -movflags +faststart` joins them into MP4.
- **HLS**: segments are fetched by 6 workers, AES-128 segments decrypted
  (key cache, IV from the playlist or the media sequence number), byte-range
  segments supported; separate audio renditions are downloaded too. The
  segments of each rendition are concatenated (fMP4 with its init section)
  and **ffmpeg copies them into MP4** — it handles every HLS flavor,
  including the negative composition offsets used by X/Twitter. A pure-Go
  remuxer (`media`, based on a vendored and patched
  [gomedia](https://github.com/yapingcat/gomedia)) is the fallback.
- The ffmpeg used on the device is youtubedl-android's (`libffmpeg.so` in
  `nativeLibraryDir`), executed by Go with `os/exec`.

### Audio only

- Options (`ytdlp/audio.go`): **M4A original** (the AAC stream copied as is —
  instant and lossless), **MP3 320** and **MP3 V0** encoded from the best
  source (usually Opus); MP3 sources are copied.
- One ffmpeg pass converts/copies, embeds the cover (16:9 thumbnails are
  center-cropped to a square, up to 1000 px) and writes the tags, with real
  progress (`-progress`).
- **Metadata** (`go/internal/music`): YouTube Music's track/artist/album when
  present; otherwise the cleaned title ("(Official Video)", "[Lyrics]"…
  removed) split as "Artist - Title". **iTunes and Deezer are queried in
  parallel** and a result is accepted only if the duration matches within
  ±3 s and title/artist are similar; iTunes usually gives the original album,
  Deezer fills the gaps (ISRC). Without a match, the "Artist - Title" split
  is kept only for music links (YouTube Music, SoundCloud); otherwise the
  full title and the channel are used.
- **Spotify/Deezer/Apple Music**: their audio is DRM-protected, so DownVid
  reads the track metadata (Spotify embed page, Deezer API, iTunes lookup),
  searches YouTube Music and verifies the duration of the candidate before
  downloading it; live/remix/cover versions are penalized unless requested.

### Instagram

- **Public posts and reels, no login**: the app opens the post page in a
  hidden WebView (a real Chrome, presented as the desktop build so Instagram
  serves the highest renditions), reads the page's session token and the
  current query id, and runs the same logged-out post query the website runs
  for visitors (`/api/graphql`). `go/internal/instagram` parses the answer:
  DASH renditions (video + audio track, up to 1440p VP9), the progressive
  H.264 MP4 ("compatible"), photos, carousels (one option per item, with
  checkboxes) and the audio track for "Audio only". Media files are then
  downloaded by Go from Instagram's CDN, which needs no session.
- **Optional login, only offered for private content**: Instagram hides some
  posts from logged-out visitors (private accounts you follow, accounts of
  minors, sensitive content) and always hides stories. Only then the sheet
  offers "Sign in to Instagram (optional)": `InstagramLoginActivity` shows
  Instagram's **official login page**; the app never sees or stores the
  password — it only detects that the session cookie exists. The session
  lives in the app's private WebView cookie store, never leaves the device,
  and is used only when the public query is refused (`/api/v1/media/{id}/info/`
  for posts, `/api/v1/feed/reels_media/` for stories). Sign out in Settings.

### Threads

- **Public posts, no login**: Threads posts use Instagram's media schema.
  `InstagramQuery` (mode `threads`) opens the post page in the hidden WebView;
  share links (`threads.com/share/...`) redirect to the post and the code is
  read from the final URL. The post comes from the data the page embeds
  (`<script type="application/json">`) or, when the page loads it later, from
  the page's own GraphQL responses (kept by a document-start script,
  `androidx.webkit`). `go/internal/instagram/threads.go` finds the post by
  code and reuses the Instagram parser: DASH renditions, the H.264 MP4,
  photos, carousels and audio; quotes/reposts offer the shared post's media.
- **Optional login**: some posts are hidden from visitors (restricted
  profiles, sensitive content). The sheet then offers "Sign in to Threads
  (optional)": the same login activity shows Threads' official login page,
  and the query WebView uses the session from the app's cookie store. Sign
  out in Settings.

### Queue, resume and background downloads

- `go/internal/jobs` keeps a **persistent queue** in `files/jobs/jobs.json`
  (atomic writes): states `queued → running → done → saved`, plus `paused`,
  `waiting` (no network), `expired`, `error`, `canceled`; at most N downloads
  run at once (Settings, default 2).
- **Resume**: ranged downloads store each part's position in
  `<file>.dvstate`; HLS segments already fetched are reused; pausing or
  losing the process keeps partial data (only "cancel" deletes it).
- **No network**: the job waits and retries by itself (10–60 s, up to 60
  times), continuing where it stopped.
- **Expired links** (HTTP 401/403/404/410 from signed CDN URLs): the
  Downloads screen extracts the original link again, finds the same choice
  (`optionKey`: kind, quality, codec, format, carousel item) and swaps the
  URLs (`DV_Replace`) keeping the partial files.
- When the app starts, `DvCore.ensureInit` loads the queue and resumes what
  was pending. `DownloadService` (foreground service, type `dataSync`) polls
  the queue through JNI, shows per-download notifications with Pause/Cancel,
  publishes finished files and records them in the history. Closing or
  hiding the sheet never cancels a download.

### Storage and permissions

- Files are published with **MediaStore** (no storage permission on
  Android 10+): videos → `Movies/DownVid`, audio → `Music/DownVid`, photos →
  `Pictures/DownVid`. Android 7–9 write the file directly
  (`WRITE_EXTERNAL_STORAGE`, `maxSdkVersion=28`).
- Permissions: `INTERNET`, `FOREGROUND_SERVICE(_DATA_SYNC)`,
  `POST_NOTIFICATIONS` (asked on the first download), `WAKE_LOCK`.
- The clipboard is read only while the app has focus (Android 10+ forbids
  background reads); the app first checks the clip's timestamp, which does
  not trigger Android's "pasted from clipboard" toast, and reads the content
  only when something new was copied.

### Privacy

DownVid has no server, account, analytics or ads. Everything runs on the
device; network requests go only to the sites you download from and, for
music metadata, to the public iTunes Search and Deezer APIs.

## Building

Requirements:

| Tool | Version |
|---|---|
| Flutter | 3.44+ (Dart 3.12+) |
| Go | 1.25+ |
| Android SDK + NDK | NDK 28.2.13676358 (the one Flutter's Gradle plugin uses) |
| Java | 17 |
| libclang | only to regenerate the FFI bindings |

```bash
flutter pub get

# Debug APK (Gradle runs scripts/build_go.sh incrementally)
flutter build apk --debug
# Smaller debug APK for one device ABI (Flutter ignores --target-platform in debug)
DV_ABI=arm64-v8a flutter build apk --debug --target-platform android-arm64

# Release APKs, one per ABI
flutter build apk --release --split-per-abi
```

The Go core can be built on its own:

```bash
scripts/build_go.sh                  # arm64-v8a armeabi-v7a x86_64
scripts/build_go.sh arm64-v8a        # one ABI
DV_VERSION=1.2.3 scripts/build_go.sh # version embedded in the library
```

The libraries are 16 KB page-aligned (required on Android 15+ devices).
After changing exported Go functions, update `go/include/dvcore.h` and run
`dart run ffigen --config ffigen.yaml`.

**Release signing**: put `android/key.properties` (git-ignored) with
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`, or set
`DV_KEYSTORE_PATH`, `DV_KEYSTORE_PASSWORD`, `DV_KEY_ALIAS`,
`DV_KEY_PASSWORD`. Without them, release builds are signed with the debug key.

## Testing

```bash
flutter analyze && flutter test        # Dart
cd go && go vet ./... && go test -race ./internal/...

# Tests that reach the network (iTunes/Deezer/Spotify lookups)
cd go && DV_NET=1 go test ./internal/music -run Net -v
# Check option selection against a real `yt-dlp -J` dump
cd go && DV_YTDLP_JSON=/path/dump.json go test ./internal/ytdlp -v
```

`go/cmd/dvcli` exercises the core on a desktop (some commands need `yt-dlp`
and `ffmpeg` on the PATH):

```bash
cd go
go run ./cmd/dvcli probe <page-url>                       # generic scan
DV_FFMPEG=/usr/bin/ffmpeg go run ./cmd/dvcli get <url> 0 out.mp4
DV_YTDLP=$(which yt-dlp) go run ./cmd/dvcli ytdlp <url> 2 out.mp4
DV_YTDLP=$(which yt-dlp) go run ./cmd/dvcli audio <url> 0 out.m4a
```

Simulate a share on a device or emulator:

```bash
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "'https://www.youtube.com/watch?v=aqz-KE-bpKQ'" \
  -n com.downvid.downvid/.ShareActivity
```

## Releases and CI

`.github/workflows/ci.yml`:

- **Every push and pull request**: `go vet`, `go test -race`,
  `flutter analyze`, `flutter test`, and a debug build to catch Android/Go
  build breakages.
- **Tags `v*`** (e.g. `v0.1.0`): builds the release APKs per ABI
  (`arm64-v8a`, `armeabi-v7a`, `x86_64`) with SHA-256 checksums, and publishes
  a GitHub Release whose notes are the matching section of
  [`CHANGELOG.md`](CHANGELOG.md).

To sign release APKs in CI, add these repository secrets (otherwise the APKs
are signed with a throwaway debug key — fine for testing, but users cannot
update between builds signed with different keys):

| Secret | Content |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Release steps: update `version:` in `pubspec.yaml` and `CHANGELOG.md`,
commit, then `git tag vX.Y.Z && git push origin vX.Y.Z`.

## Project layout

```
go/                         Go core (module github.com/downvid/core)
  dvcore/                   C exports for Dart (FFI) and JNI exports for Kotlin
  include/dvcore.h          C API (input for ffigen)
  internal/scan             generic page scan and candidate resolution
  internal/ytdlp            yt-dlp JSON → options, error messages, audio options
  internal/instagram        Instagram query parameters and response parser; Threads post parser
  internal/music            metadata (iTunes/Deezer), music-service links
  internal/hls              m3u8 parser, segment downloader, AES-128
  internal/netx             HTTP client, parallel/resumable Range downloads
  internal/media            ffmpeg wrapper, pure-Go TS/fMP4 → MP4 remux
  internal/jobs             persistent queue, scheduler, job runner
  cmd/dvcli                 desktop test tool
  third_party/gomedia       vendored gomedia (MIT) with patches (PATCHES.md)
lib/                        Flutter app
  main.dart                 entry points: main() (app) and shareMain() (sheet)
  share/                    share sheet
  download/                 ExtractController, downloads screen, link renewal
  settings/                 settings screen and usage notice
  core/                     FFI facade, platform channels, settings, link parser
android/app/src/main/
  kotlin/…                  ShareActivity, services, WebView helpers, MediaSaver
  assets/ytdlp_server.py    resident yt-dlp process
scripts/build_go.sh         builds libdvcore.so for every ABI
assets/brand/               logo (SVG master, 512 px PNG); Android icons are generated from it
```

## Debugging

```bash
adb logcat -s flutter DownVid DownVid-Go
```

`DownVid-Go` is the Go core (extraction, downloads, ffmpeg commands),
`DownVid` the Kotlin side (yt-dlp server, WebViews, service, MediaStore),
`flutter` the Dart side (lines prefixed with `[DV]`).

## Limitations

- Live streams, DRM-protected content and DASH `.mpd` manifests from generic
  pages are not supported; WebM/OGG files from generic pages are skipped
  (they cannot be saved as MP4 without re-encoding).
- Instagram content that Instagram only shows to signed-in users needs the
  optional login; stories always do. The same goes for Threads posts hidden
  from visitors. Text-only Threads posts have nothing to download.
- Android 15 limits `dataSync` foreground services to 6 hours a day; when the
  limit is reached, downloads are paused and can be resumed later.
- Kwai has no dedicated yt-dlp extractor and goes through the generic scan.

## License and third-party software

DownVid is licensed under the **GNU General Public License v3.0** (see
[`LICENSE`](LICENSE)) — required because the app bundles youtubedl-android
(GPL-3.0).

| Component | License |
|---|---|
| [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (Python, yt-dlp, QuickJS and ffmpeg builds for Android) | GPL-3.0 |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Unlicense |
| [FFmpeg](https://ffmpeg.org) | LGPL/GPL (as built by youtubedl-android) |
| [gomedia](https://github.com/yapingcat/gomedia) (vendored, patched) | MIT |
| [golang.org/x/text](https://pkg.go.dev/golang.org/x/text) | BSD-3-Clause |
| Flutter, `package:ffi`, `package:ffigen` | BSD-3-Clause |

The DownVid logo (`assets/brand/`) is part of this project and covered by the
same license.
