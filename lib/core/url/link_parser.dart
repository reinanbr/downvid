/// Platforms with dedicated handling (error messages, quality defaults).
enum SourcePlatform {
  youtube('YouTube'),
  instagram('Instagram'),
  tiktok('TikTok'),
  kwai('Kwai'),
  x('X / Twitter'),
  facebook('Facebook'),
  soundcloud('SoundCloud'),
  spotify('Spotify'),
  deezer('Deezer'),
  appleMusic('Apple Music'),
  generic('Link');

  const SourcePlatform(this.label);
  final String label;
}

class SharedLink {
  const SharedLink(this.uri, this.platform);
  final Uri uri;
  final SourcePlatform platform;

  /// Spotify/Deezer/Apple Music: audio is DRM-protected; only metadata is
  /// read and the song is found on YouTube Music.
  bool get isMusicService =>
      platform == SourcePlatform.spotify || platform == SourcePlatform.deezer || platform == SourcePlatform.appleMusic;

  /// Links that are about the song, not the video: open in "audio only".
  bool get prefersAudio =>
      isMusicService || platform == SourcePlatform.soundcloud || uri.host.toLowerCase() == 'music.youtube.com';

  @override
  String toString() => '$platform $uri';
}

final _urlPattern = RegExp(r'''https?://[^\s<>"']+''', caseSensitive: false);

// Trailing punctuation that share texts often glue onto the URL.
final _trailingJunk = RegExp(r'[).,;:!?\]}>]+$');

/// Extracts the first http(s) URL from arbitrary shared text, e.g.
/// "Olha isso https://youtu.be/abc?si=x !" -> https://youtu.be/abc?si=x.
SharedLink? parseSharedText(String? text) {
  if (text == null) return null;
  final match = _urlPattern.firstMatch(text);
  if (match == null) return null;
  final raw = match.group(0)!.replaceFirst(_trailingJunk, '');
  final uri = Uri.tryParse(raw);
  if (uri == null || uri.host.isEmpty) return null;
  return SharedLink(uri, detectPlatform(uri));
}

SourcePlatform detectPlatform(Uri uri) {
  final host = uri.host.toLowerCase();
  bool isHost(String domain) => host == domain || host.endsWith('.$domain');

  if (isHost('youtube.com') || isHost('youtu.be') || isHost('youtube-nocookie.com')) {
    return SourcePlatform.youtube;
  }
  if (isHost('instagram.com') || isHost('instagr.am')) {
    return SourcePlatform.instagram;
  }
  if (isHost('tiktok.com')) return SourcePlatform.tiktok;
  if (isHost('kwai.com') || isHost('kw.ai') || isHost('kwai.net')) {
    return SourcePlatform.kwai;
  }
  if (isHost('x.com') || isHost('twitter.com') || isHost('t.co')) {
    return SourcePlatform.x;
  }
  if (isHost('facebook.com') || isHost('fb.watch') || isHost('fb.com')) {
    return SourcePlatform.facebook;
  }
  if (isHost('soundcloud.com') || isHost('snd.sc')) return SourcePlatform.soundcloud;
  if (isHost('spotify.com') || isHost('spotify.link')) return SourcePlatform.spotify;
  if (isHost('deezer.com') || isHost('deezer.page.link') || isHost('dzr.page.link')) return SourcePlatform.deezer;
  if (isHost('music.apple.com')) return SourcePlatform.appleMusic;
  return SourcePlatform.generic;
}
