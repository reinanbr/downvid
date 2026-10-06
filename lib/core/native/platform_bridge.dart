import 'dart:async';

import 'package:flutter/services.dart';

/// URL seen by the hidden WebView (PageSniffer.kt).
class SniffedUrl {
  SniffedUrl(this.session, this.url, this.referer, this.via, this.cookie);
  final int session;
  final String url;
  final String? referer;
  final String via; // net | dom
  final String? cookie;
}

class SniffDone {
  SniffDone(this.session, this.finalUrl, this.title, this.userAgent, this.cookies, this.reason, this.count);
  final int session;
  final String? finalUrl;
  final String? title;
  final String userAgent;
  final Map<String, String> cookies;
  final String reason;
  final int count;
}

/// "downvid/sniffer" channel: loads the page in a hidden WebView and reports
/// media requests as they happen.
class SnifferChannel {
  SnifferChannel._() {
    _ch.setMethodCallHandler((call) async {
      final a = Map<String, dynamic>.from(call.arguments as Map);
      switch (call.method) {
        case 'onFound':
          _found.add(
            SniffedUrl(
              a['session'] as int,
              a['url'] as String,
              a['referer'] as String?,
              a['via'] as String,
              a['cookie'] as String?,
            ),
          );
        case 'onDone':
          _done.add(
            SniffDone(
              a['session'] as int,
              a['finalUrl'] as String?,
              a['title'] as String?,
              a['userAgent'] as String,
              Map<String, String>.from(a['cookies'] as Map),
              a['reason'] as String,
              a['count'] as int,
            ),
          );
      }
    });
  }
  static final SnifferChannel instance = SnifferChannel._();

  static const _ch = MethodChannel('downvid/sniffer');
  final _found = StreamController<SniffedUrl>.broadcast();
  final _done = StreamController<SniffDone>.broadcast();

  Stream<SniffedUrl> get found => _found.stream;
  Stream<SniffDone> get done => _done.stream;

  Future<String> userAgent() async => (await _ch.invokeMethod<String>('userAgent'))!;
  int _nextSession = 1;

  /// Starts sniffing; events carry the returned session id (a newer start
  /// ends the previous session, whose late events must be ignored).
  int start(String url, {void Function(Object error)? onError}) {
    final session = _nextSession++;
    _ch.invokeMethod('start', {'url': url, 'session': session}).catchError((Object e) {
      onError?.call(e);
    });
    return session;
  }

  Future<void> stop(int session) => _ch.invokeMethod('stop', {'session': session});
}

class SavedVideo {
  SavedVideo(this.uri, this.displayName, this.location, [this.mime = 'video/mp4']);
  final String uri;
  final String displayName;
  final String location;
  final String mime;
}

/// "downvid/media" channel: app work dir and MediaStore publishing.
abstract final class MediaChannel {
  static const _ch = MethodChannel('downvid/media');

  static Future<String> workDir() async => (await _ch.invokeMethod<String>('workDir'))!;

  static Future<SavedVideo> saveVideo(String path, String title) async {
    final r = Map<String, dynamic>.from((await _ch.invokeMethod<Map>('saveVideo', {'path': path, 'title': title}))!);
    return SavedVideo(
      r['uri'] as String,
      r['displayName'] as String,
      r['location'] as String,
      r['mime'] as String? ?? 'video/mp4',
    );
  }

  static Future<void> openVideo(String uri, [String mime = 'video/mp4']) =>
      _ch.invokeMethod('openVideo', {'uri': uri, 'mime': mime});

  static Future<void> shareMedia(String uri, String mime) => _ch.invokeMethod('shareMedia', {'uri': uri, 'mime': mime});

  /// Deletes a file this app published to the gallery.
  static Future<bool> deleteMedia(String uri) async =>
      await _ch.invokeMethod<bool>('deleteMedia', {'uri': uri}) ?? false;
}

/// "downvid/downloads" channel: hands Go jobs to DownloadService (foreground
/// service with progress notification), which also saves them to MediaStore.
class DownloadsChannel {
  DownloadsChannel._() {
    _ch.setMethodCallHandler((call) async {
      if (call.method == 'onEvent') {
        _events.add(Map<String, dynamic>.from(call.arguments as Map));
      }
    });
  }
  static final DownloadsChannel instance = DownloadsChannel._();

  static const _ch = MethodChannel('downvid/downloads');
  final _events = StreamController<Map<String, dynamic>>.broadcast();

  /// Service events: {event: saved|saveFailed|canceled, id, ...}.
  Stream<Map<String, dynamic>> get events => _events.stream;

  Future<bool> track(int id, String title) async =>
      await _ch.invokeMethod<bool>('track', {'id': id, 'title': title}) ?? false;

  /// Asks for POST_NOTIFICATIONS on Android 13+ (no-op when granted).
  Future<bool> ensureNotificationPermission() async =>
      await _ch.invokeMethod<bool>('ensureNotificationPermission') ?? false;
}

/// "downvid/ytdlp" channel: yt-dlp (youtubedl-android) and its ffmpeg.
abstract final class YtdlpChannel {
  static const _ch = MethodChannel('downvid/ytdlp');
  static Map<String, dynamic>? _ffmpeg;

  /// Runs `yt-dlp -J`; returns {json, stderr, exitCode, elapsedMs}.
  static Future<Map<String, dynamic>> info(String url, String id) async =>
      Map<String, dynamic>.from((await _ch.invokeMethod<Map>('info', {'url': url, 'id': id}))!);

  static Future<void> cancel(String id) => _ch.invokeMethod('cancel', {'id': id});

  /// Flat search (YouTube Music search URL) -> {json, stderr, exitCode}.
  static Future<Map<String, dynamic>> search(String url) async =>
      Map<String, dynamic>.from((await _ch.invokeMethod<Map>('search', {'url': url}))!);

  /// {path, libraryPath} of the bundled ffmpeg (for the Go core).
  static Future<Map<String, dynamic>> ffmpeg() async =>
      _ffmpeg ??= Map<String, dynamic>.from((await _ch.invokeMethod<Map>('ffmpeg'))!);

  static Future<String?> version() => _ch.invokeMethod<String>('version');
  static Future<String?> update() => _ch.invokeMethod<String>('update');
}

/// "downvid/instagram" channel: runs Instagram's logged-out post query in a
/// hidden WebView (public posts only) and returns the raw response.
abstract final class InstagramChannel {
  static const _ch = MethodChannel('downvid/instagram');

  /// [q] from DV_InstaQuery plus "mode": public | media | story; or from
  /// DV_ThreadsQuery with mode "threads" (returns the post page's data).
  static Future<String> query(Map<String, dynamic> q) async => (await _ch.invokeMethod<String>('query', q))!;

  /// Whether this app holds a session for [site] (instagram | threads),
  /// i.e. the user signed in once.
  static Future<bool> isLoggedIn({String site = 'instagram'}) async =>
      await _ch.invokeMethod<bool>('isLoggedIn', {'site': site}) ?? false;

  /// Opens the site's login page; true once the session exists.
  static Future<bool> login({String site = 'instagram'}) async =>
      await _ch.invokeMethod<bool>('login', {'site': site}) ?? false;

  static Future<void> logout({String site = 'instagram'}) => _ch.invokeMethod('logout', {'site': site});
}

/// "downvid/clipboard": [info] does not read the content (Android shows no
/// "pasted" toast); [read] does and only works while the app has focus.
abstract final class ClipboardChannel {
  static const _ch = MethodChannel('downvid/clipboard');

  static Future<({bool hasText, int timestamp})> info() async {
    final r = Map<String, dynamic>.from(await _ch.invokeMethod<Map>('info') ?? const {});
    return (hasText: r['hasText'] == true, timestamp: (r['timestamp'] as num?)?.toInt() ?? 0);
  }

  static Future<String?> read() => _ch.invokeMethod<String>('read');
}
