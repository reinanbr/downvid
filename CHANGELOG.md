# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- The browser's download sheet on a page with several videos (feeds such as
  Reddit's) lists the one you are watching first, under "Playing on this
  page", and preselects it; the other videos follow with each one's
  qualities together instead of every quality of every video mixed. The
  player's download button does the same for the video it plays.

## [0.2.0] - 2026-11-22

### Added

- Built-in browser: tabs, private tabs (separate WebView profile, wiped when
  the last one closes), history and bookmarks, desktop mode and Brave's ad
  and tracker blocker: its engine (adblock-rust) and default lists, with
  network, cosmetic and scriptlet filtering; video ads get an empty video,
  as in Brave (allow per site). A **▶ N**
  button counts the videos of the page you are on (HLS, MP4 and the streams
  its player loads, found from its network requests and a document-start
  script) and lists them with **Download** and **Play**; pages that use DRM
  say so. "Download link…" takes any media URL with Referer, User-Agent and
  Cookie. Web links opened with DownVid go to the browser.
- Built-in player (Media3): HLS, DASH and MP4 with the page's session;
  double-tap and swipe seeking, swipe brightness/volume, hold for 2×, speed,
  lock, fit/stretch/zoom, rotation, picture-in-picture, background audio with
  media controls, a remembered position and a download button. A video you
  start on a page plays in it right away, inside the page in place of the
  site's player (or behind a "Play in DownVid" button, in Settings), with
  fullscreen over the open page. It starts in portrait.
- Downloads: a finished video or song plays in DownVid's player; the ⋮ menu
  adds **Open with…** (another app) next to Share. Downloaded videos show
  their own frame as thumbnail when the source had none.
- Settings: connections per download (1–16), search engine, ad blocker,
  desktop sites, automatic player, background playback and "Clear browsing
  data".
- Threads posts (threads.com / threads.net): videos in the best quality,
  photos, carousels, and the media of quoted/reposted posts, without login;
  share links (threads.com/share/...) included. Optional login (Threads'
  official page, session kept on the device) for posts Threads only shows to
  signed-in users.

### Changed

- The app is now native Kotlin with Jetpack Compose instead of Flutter. Every
  screen was rebuilt with the same design and flows; the download history,
  settings and Instagram/Threads sign-ins carry over when it updates.
- One component (`JobController`) owns the download queue's feed: the
  Downloads screen, the sheet and the background service share one
  long-poll on the Go core, which stops when nobody watches.
- Home, Browser, Downloads and Settings are tabs of a bottom navigation bar.
- Release APKs keep the same name in every release (`DownVid-arm64-v8a.apk`
  and so on), so the README's download link always gets the latest one.

### Fixed

- A two-input download (separate video and audio) interrupted after one
  input finished fetched that input again when it resumed.
- Videos from CDNs that refuse HTTP/1.1 (some answer 410, "link expired")
  failed to download and to play: downloads switch to HTTP/2 for such a
  host (still over several connections) and the player uses HTTP/2/QUIC.
  Requests to another site now send only the page's origin as Referer, as
  browsers do (a full page URL next to an Origin header was refused too).
- Downloads stuck at "Saving to gallery…":
  - Titles whose 80th character fell inside an emoji produced a file name
    with half a surrogate pair, which MediaStore refuses. Names are now cut
    by character (and to 180 UTF-8 bytes).
  - A failed save was retried every 0.7 s forever. That kept the foreground
    service and its wake lock alive, used up Android 15's 6-hour `dataSync`
    budget and paused real downloads. The job now shows the error with a
    **Save again** button that publishes the kept file without downloading
    it again.
  - Downloads resumed, retried or renewed from the Downloads screen did not
    start the foreground service, so nothing saved them when they finished.
    The app now starts it whenever a job becomes active or finishes.
  - A finished download could be saved twice (the service still saw the job
    as finished before the saved state arrived): the second attempt found
    the file already moved and replaced "Download complete" with "Could not
    save".
- Downloaded videos did not open from DownVid: the saved
  file's type was stored empty. Older entries are fixed when opened.
- Browser:
  - DRM sites (Vimeo and streaming services) showed "rights issue" or a
    black player: protected media is now allowed for the page's own player.
    DownVid no longer offers to play or download such a page's video.
  - Players inside web components (archive.org's) were not replaced by
    DownVid's player: their play event never reached the page.
  - Players that load HLS through MediaSource (hls.js) opened in one fixed
    quality; DownVid's player now gets the master playlist with every
    quality.
  - A video found while the previous batch was being resolved cancelled
    that batch: its options were lost (often the master playlist, so a
    single quality played).
  - "Play in DownVid" on a video that had been playing for a while started
    it over, in the quality known when it started; it now continues from
    the page's position with every quality.
  - DownVid's player was taller than the page's when the site crops its
    video (rounded or fixed-ratio frames, video.js): it is now cropped the
    same way.
  - Posters and scripts in a folder named like a video (`clip.webm/poster.jpg`)
    were listed as videos.
  - Taking over a page's video while its picture-in-picture window was open
    left the window black.
  - Browser history and tabs could be lost when the app was killed right
    after a write.
- The download sheet opened from another app covered the status bar with a
  black strip.

## [0.1.0] - 2026-10-04

First public release.

### Added

- Share target: a translucent bottom sheet over the sharing app with
  thumbnail, title, duration, quality list, estimated size and download.
- Copied-link detection when the app comes to the foreground, and a Quick
  Settings tile "Paste & download".
- Go core (`libdvcore.so` for arm64-v8a, armeabi-v7a and x86_64) with
  parallel, resumable HTTP Range downloads and a persistent download queue.
- Generic page scan: HTML, scripts, metadata, iframes and a hidden WebView
  that records the media requests of JavaScript players.
- HLS to MP4: master playlists with one option per variant, AES-128,
  byte ranges, separate audio renditions, fMP4 and TS, assembled by ffmpeg
  without re-encoding.
- yt-dlp extraction (YouTube, TikTok, X/Twitter, Facebook, SoundCloud and
  many more) through a resident Python process, with QuickJS for YouTube's
  JavaScript challenges and automatic yt-dlp updates.
- Instagram public posts, reels, photos and carousels (item selection)
  without login, plus an optional login (Instagram's official page, session
  kept on the device) offered only for private content and stories.
- Audio only: original M4A or MP3 V0/320 with tags and embedded square cover;
  metadata from YouTube Music, iTunes and Deezer with duration checks.
- Spotify, Deezer and Apple Music links matched to the same recording on
  YouTube Music.
- Background downloads in a foreground service with progress notifications
  (pause/cancel), automatic retry when the network drops and renewal of
  expired links.
- Downloads screen with Videos / Music / Photos tabs, pause/resume, history
  with open, share and delete.
- Settings: default video quality, default audio format, music links in
  "Audio only", simultaneous downloads, copied-link detection, yt-dlp update.
- First-use notice about authorized use and copyright.
- DownVid logo, adaptive launcher icon (with Android 13 themed icon),
  branded notifications and launch screen.
- Material 3 design with the brand's violet/pink palette, light and dark
  themes: home screen with link field, recent downloads and tips; card-based
  downloads list with empty states; selectable quality cards in the download
  sheet; grouped settings with an About section.

[Unreleased]: https://github.com/reinanbr/downvid/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/reinanbr/downvid/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/reinanbr/downvid/releases/tag/v0.1.0
