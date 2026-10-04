# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/OWNER/downvid/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/OWNER/downvid/releases/tag/v0.1.0
