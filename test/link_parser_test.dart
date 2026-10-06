import 'package:downvid/core/url/link_parser.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('parseSharedText', () {
    test('returns null without a URL', () {
      expect(parseSharedText(null), isNull);
      expect(parseSharedText('sem link aqui'), isNull);
      expect(parseSharedText('ftp://example.com/a'), isNull);
    });

    test('extracts the URL from surrounding text and trims punctuation', () {
      final l = parseSharedText('Olha isso: https://youtu.be/dQw4w9WgXcQ?si=abc!')!;
      expect(l.uri.toString(), 'https://youtu.be/dQw4w9WgXcQ?si=abc');
      expect(l.platform, SourcePlatform.youtube);
    });

    test('takes the first URL when several are present', () {
      final l = parseSharedText('https://x.com/a/status/1 e https://tiktok.com/@b')!;
      expect(l.platform, SourcePlatform.x);
    });
  });

  test('prefersAudio', () {
    expect(parseSharedText('https://music.youtube.com/watch?v=x')!.prefersAudio, isTrue);
    expect(parseSharedText('https://open.spotify.com/track/abc')!.isMusicService, isTrue);
    expect(parseSharedText('https://www.youtube.com/watch?v=x')!.prefersAudio, isFalse);
  });

  group('detectPlatform', () {
    final cases = {
      'https://www.youtube.com/watch?v=1': SourcePlatform.youtube,
      'https://m.youtube.com/shorts/1': SourcePlatform.youtube,
      'https://www.instagram.com/reel/abc/': SourcePlatform.instagram,
      'https://www.threads.com/@u/post/DTvidE0xAmp': SourcePlatform.threads,
      'https://www.threads.net/@u/post/DTvidE0xAmp?xmt=a': SourcePlatform.threads,
      'https://vm.tiktok.com/ZMabc/': SourcePlatform.tiktok,
      'https://www.kwai.com/@u/video/1': SourcePlatform.kwai,
      'https://twitter.com/u/status/1': SourcePlatform.x,
      'https://fb.watch/abc/': SourcePlatform.facebook,
      'https://m.facebook.com/watch/?v=1': SourcePlatform.facebook,
      'https://example.com/video.mp4': SourcePlatform.generic,
      // Lookalike domains must not match.
      'https://notyoutube.com/watch': SourcePlatform.generic,
      'https://box.com/x': SourcePlatform.generic,
      'https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT': SourcePlatform.spotify,
      'https://www.deezer.com/br/track/14408104': SourcePlatform.deezer,
      'https://music.apple.com/br/album/x/1559885415?i=1559885421': SourcePlatform.appleMusic,
      'https://soundcloud.com/artist/track': SourcePlatform.soundcloud,
      'https://music.youtube.com/watch?v=x': SourcePlatform.youtube,
    };
    cases.forEach((url, expected) {
      test(url, () => expect(detectPlatform(Uri.parse(url)), expected));
    });
  });
}
