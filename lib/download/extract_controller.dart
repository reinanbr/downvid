import 'dart:async';

import 'package:flutter/foundation.dart';

import '../core/log.dart';
import '../core/native/go_core.dart';
import '../core/native/platform_bridge.dart';
import '../core/settings.dart';
import 'models.dart';

enum DownloadPhase { idle, downloading, muxing, converting, saving, saved, failed, canceled }

enum MediaMode { video, audio }

/// Drives extraction and download for one link:
///   - platform links (YouTube, Instagram, ...): yt-dlp first; when it does
///     not support the link, falls back to the generic scan automatically;
///   - generic links: static scan in Go + hidden-WebView sniffing, with
///     yt-dlp in parallel (its many site extractors find embedded players);
///   - every URL found is resolved by Go into options; downloads run in Go
///     (→ MP4) and are saved by DownloadService (Movies/DownVid).
class ExtractController extends ChangeNotifier {
  ExtractController(
    this.pageUrl, {
    this.platform,
    this.isPlatform = false,
    this.isMusicService = false,
    bool preferAudio = false,
    bool musicContext = false,
  }) : mode = preferAudio ? MediaMode.audio : MediaMode.video,
       _musicContext = musicContext || preferAudio;

  /// Music link (YouTube Music, SoundCloud...): metadata trusts
  /// "Artist - Title" in the title even without a catalog match.
  final bool _musicContext;

  final String pageUrl;

  /// Display name of the platform (YouTube, Instagram...), for messages.
  final String? platform;

  /// Platform link: yt-dlp is the primary extractor.
  final bool isPlatform;

  /// Spotify/Deezer/Apple Music link: metadata from the link, audio from a
  /// matching YouTube Music recording.
  final bool isMusicService;

  // ---- audio only
  MediaMode mode;
  final List<MediaOption> audioOptions = [];
  Map<String, dynamic>? _musicBasic; // what yt-dlp knows (title, artist...)
  Map<String, dynamic>? _linkMeta; // from a music-service link
  Map<String, dynamic>? musicMeta; // resolved tags + cover
  bool resolvingMeta = false;
  String? matchStatus; // "Procurando no YouTube Music…"

  // ---- yt-dlp state
  bool ytdlpRunning = false;
  String? ytdlpError;
  bool genericStarted = false;
  final String _ytdlpId = 'x${DateTime.now().microsecondsSinceEpoch}';

  // ---- scan state
  String? title;
  String? thumbnail;
  bool staticScanning = false;
  bool sniffing = false;
  int sniffedCount = 0;
  String? scanError;
  final List<MediaOption> options = [];
  final List<Skipped> skipped = [];
  MediaOption? selected;

  bool get scanning => ytdlpRunning || staticScanning || sniffing || _pendingProbes > 0;

  /// Options shown in the list: tiny files (previews of related videos, HLS
  /// fragments) are hidden as soon as the page has a real video.
  bool get hasAudio => audioOptions.isNotEmpty;
  bool get hasVideo => options.isNotEmpty;

  /// Options of the current mode.
  List<MediaOption> get modeOptions => mode == MediaMode.audio ? audioOptions : visibleOptions;

  void setMode(MediaMode m) {
    if (busy || mode == m) return;
    mode = m;
    _userPicked = false;
    selected = _defaultOption();
    if (m == MediaMode.audio) unawaited(_resolveMeta());
    _notify();
  }

  List<MediaOption> get visibleOptions {
    final real = options.where((o) => !o.small).toList();
    return real.isEmpty ? options : real;
  }

  int get hiddenPreviews => options.length - visibleOptions.length;

  // ---- download state
  DownloadPhase phase = DownloadPhase.idle;
  double? percent; // 0..100, null = indeterminate
  int bytes = 0;
  int total = -1;
  bool totalIsEstimate = false;
  double speedBps = 0;
  int segmentsDone = 0;
  int segmentsTotal = 0;
  String? error;
  String? warning;
  SavedVideo? saved;

  bool get busy =>
      phase == DownloadPhase.downloading ||
      phase == DownloadPhase.muxing ||
      phase == DownloadPhase.converting ||
      phase == DownloadPhase.saving;

  String _userAgent = '';
  final Set<String> _knownCandidates = {};
  final List<SniffedUrl> _queue = [];
  Timer? _flushTimer;
  int _pendingProbes = 0;
  final List<StreamSubscription<Object?>> _subs = [];
  DownloadHandle? _download;
  bool _disposed = false;

  Future<void> start() async {
    dvLog('extract: start $pageUrl platform=$platform');
    try {
      _userAgent = await SnifferChannel.instance.userAgent();
    } catch (e) {
      dvLog('extract: userAgent failed: $e');
    }
    _knownCandidates.add(pageUrl);
    if (isMusicService) {
      await _runMusicLink();
    } else if (platform == 'Instagram') {
      // Public posts via Instagram's own logged-out query; yt-dlp as fallback
      // (stories, profiles) — it needs login for most of them.
      if (!await _runInstagram()) await _runYtdlp();
    } else if (platform == 'Threads' && await _runThreads()) {
      // Post read from the Threads page. Otherwise (login wall, profile
      // links): yt-dlp, then the page scan, as for other platforms.
    } else if (isPlatform) {
      final ok = await _runYtdlp();
      // Link not handled by yt-dlp (e.g. Kwai): look for videos in the page.
      if (!ok && _ytdlpUnsupported) startGeneric();
    } else {
      unawaited(_runYtdlp());
      startGeneric();
    }
  }

  /// Generic page scan (static + WebView). Also offered as a button after a
  /// platform extraction error.
  void startGeneric() {
    if (genericStarted || _disposed) return;
    genericStarted = true;
    unawaited(_staticScan());
    _startSniffer();
    _notify();
  }

  bool _ytdlpUnsupported = false;

  /// Runs yt-dlp -J (Kotlin) and converts its output into options (Go).
  /// Returns whether it produced options.
  Future<bool> _runYtdlp({String? url, double? wantDuration}) async {
    ytdlpRunning = true;
    _notify();
    final sw = Stopwatch()..start();
    try {
      final r = await YtdlpChannel.info(url ?? pageUrl, _ytdlpId);
      if (_disposed) return false;
      final data = await GoCore.instance.ytdlpOptions({
        'json': r['json'],
        'stderr': r['stderr'],
        'exitCode': r['exitCode'],
        'platform': platform ?? Uri.parse(pageUrl).host,
      });
      dvLog(
        'extract: yt-dlp ${sw.elapsedMilliseconds}ms (-J ${r['elapsedMs']}ms) '
        'extractor=${data['extractor']} options=${(data['options'] as List?)?.length ?? 0}',
      );
      final basic = Map<String, dynamic>.from(data['music'] as Map? ?? const {});
      if (wantDuration != null && wantDuration > 0) {
        final got = (basic['duration'] as num?)?.toDouble() ?? 0;
        if (got > 0 && (got - wantDuration).abs() >= 5) {
          dvLog('extract: candidate rejected: duration ${got}s vs ${wantDuration}s');
          return false;
        }
      }
      // yt-dlp's metadata is better than the page's (real title, HD thumb),
      // but a music-service link keeps its own.
      if (_linkMeta == null) {
        title = _nonEmpty(data['title'] as String?) ?? title;
        thumbnail = _nonEmpty(data['thumbnail'] as String?) ?? thumbnail;
      }
      _musicBasic = basic;
      _merge(data);
      final existing = {for (final o in audioOptions) o.key};
      for (final j in (data['audioOptions'] as List? ?? const [])) {
        final o = MediaOption.fromJson(Map<String, dynamic>.from(j as Map));
        if (existing.add(o.key)) audioOptions.add(o);
      }
      if (options.isEmpty && audioOptions.isNotEmpty) mode = MediaMode.audio;
      if (mode == MediaMode.audio) {
        _pickDefault();
        unawaited(_resolveMeta());
      }
      return true;
    } on GoCoreException catch (e) {
      dvLog('extract: yt-dlp failed in ${sw.elapsedMilliseconds}ms: ${e.message} unsupported=${e.unsupported}');
      _ytdlpUnsupported = e.unsupported;
      // For generic links the page scan is the main path: stay quiet.
      if (isPlatform) ytdlpError = e.message;
      return false;
    } catch (e) {
      dvLog('extract: yt-dlp error: $e');
      if (isPlatform) ytdlpError = 'Extractor failed: $e';
      return false;
    } finally {
      ytdlpRunning = false;
      _notify();
    }
  }

  Map<String, dynamic> get _headers => {if (_userAgent.isNotEmpty) 'userAgent': _userAgent};

  Future<void> _staticScan() async {
    staticScanning = true;
    _notify();
    final sw = Stopwatch()..start();
    try {
      final r = await GoCore.instance.probe({'pageUrl': pageUrl, 'headers': _headers});
      dvLog(
        'extract: static scan ${sw.elapsedMilliseconds}ms '
        'options=${(r['options'] as List?)?.length ?? 0} skipped=${(r['skipped'] as List?)?.length ?? 0}',
      );
      title ??= _nonEmpty(r['title'] as String?);
      thumbnail ??= _nonEmpty(r['thumbnail'] as String?);
      _merge(r);
    } on GoCoreException catch (e) {
      dvLog('extract: static scan failed: ${e.message}');
      scanError = e.message;
    } catch (e) {
      dvLog('extract: static scan error: $e');
      scanError = '$e';
    } finally {
      staticScanning = false;
      _notify();
    }
  }

  int _session = -1;

  void _startSniffer() {
    final s = SnifferChannel.instance;
    sniffing = true;
    _subs.add(
      s.found.where((f) => f.session == _session).listen((f) {
        sniffedCount++;
        dvLog('extract: sniffed [${f.via}] ${f.url}');
        if (_knownCandidates.add(f.url)) {
          _queue.add(f);
          // Batch URLs that arrive together (master + variants, etc.).
          _flushTimer?.cancel();
          _flushTimer = Timer(const Duration(milliseconds: 700), _flushQueue);
        }
        _notify();
      }),
    );
    _subs.add(
      s.done.where((d) => d.session == _session).listen((d) {
        dvLog('extract: sniff done reason=${d.reason} count=${d.count} title=${d.title}');
        title ??= _nonEmpty(d.title);
        if (d.userAgent.isNotEmpty) _userAgent = d.userAgent;
        sniffing = false;
        _flushTimer?.cancel();
        _flushQueue();
        _notify();
      }),
    );
    _session = s.start(
      pageUrl,
      onError: (e) {
        dvLog('extract: sniffer start failed: $e');
        sniffing = false;
        _notify();
      },
    );
  }

  Future<void> _flushQueue() async {
    if (_queue.isEmpty || _disposed) return;
    final batch = List<SniffedUrl>.of(_queue);
    _queue.clear();
    _pendingProbes++;
    _notify();
    try {
      final r = await GoCore.instance.probe({
        'pageUrl': pageUrl,
        'skipPage': true,
        'headers': _headers,
        'candidates': [
          for (final f in batch)
            {
              'url': f.url,
              'source': 'webview',
              if (f.referer != null) 'referer': f.referer,
              if (f.cookie != null) 'cookie': f.cookie,
            },
        ],
      });
      _merge(r);
    } catch (e) {
      dvLog('extract: probe of sniffed urls failed: $e');
    } finally {
      _pendingProbes--;
      _notify();
    }
  }

  void _merge(Map<String, dynamic> r) {
    final existing = {for (final o in options) o.key};
    for (final j in (r['options'] as List? ?? const [])) {
      final o = MediaOption.fromJson(Map<String, dynamic>.from(j as Map));
      _knownCandidates.add(o.group);
      _knownCandidates.add(o.url);
      if (existing.add(o.key)) {
        options.add(o);
        dvLog('extract: option ${o.kind} "${o.label}" size=${o.size} small=${o.small} src=${o.source} ${o.url}');
      }
    }
    for (final j in (r['skipped'] as List? ?? const [])) {
      final m = Map<String, dynamic>.from(j as Map);
      _knownCandidates.add(m['url'] as String);
      skipped.add(Skipped(m['url'] as String, m['reason'] as String));
    }
    options.sort(_compare);
    // Carousel: every item starts ticked.
    if (checkedItems.isEmpty && isCarousel) checkedItems.addAll(carouselItems.map((o) => o.item));
    final visible = visibleOptions;
    // Re-pick when the current choice became hidden (a real video showed up).
    if (mode == MediaMode.video && !busy && (!_userPicked || !visible.contains(selected))) {
      selected = _defaultOption();
    }
  }

  // Real videos first, then resolution, bitrate and size; best first.
  static int _compare(MediaOption a, MediaOption b) {
    if (a.small != b.small) return a.small ? 1 : -1;
    if (a.height != b.height) return b.height.compareTo(a.height);
    if (a.bandwidth != b.bandwidth) return b.bandwidth.compareTo(a.bandwidth);
    return b.size.compareTo(a.size);
  }

  /// Set once the user taps an option: defaults no longer override it.
  bool _userPicked = false;

  /// The option matching the user's defaults (Settings): the closest
  /// quality not above the chosen maximum, or the chosen audio format.
  MediaOption? _defaultOption() {
    final s = AppSettings.instance;
    if (mode == MediaMode.audio) {
      if (audioOptions.isEmpty) return null;
      final (fmt, q) = switch (s.audioFormat) {
        'mp3_v0' => ('mp3', 'v0'),
        'mp3_320' => ('mp3', '320'),
        _ => ('m4a', null),
      };
      return audioOptions.where((o) => o.audioFormat == fmt && (q == null || o.audioQuality == q)).firstOrNull ??
          audioOptions.where((o) => o.audioFormat == fmt).firstOrNull ??
          audioOptions.first;
    }
    final v = visibleOptions;
    if (v.isEmpty) return null;
    if (s.videoMaxHeight > 0) {
      final fit = v.where((o) => o.height > 0 && o.height <= s.videoMaxHeight).firstOrNull;
      if (fit != null) return fit;
    }
    return v.first;
  }

  void _pickDefault() {
    if (!_userPicked && !busy) selected = _defaultOption();
  }

  void select(MediaOption o) {
    if (busy) return;
    _userPicked = true;
    selected = o;
    _notify();
  }

  /// Content Instagram only shows to signed-in users: the sheet offers
  /// "Entrar no Instagram" and retries after login.
  bool needsInstagramLogin = false;

  /// Instagram post/reel/story, queried by the app's WebView:
  ///   1. posts: the public (logged-out) query first;
  ///   2. if Instagram refuses it, or for stories, the user's own session
  ///      (signed in once in the app), when present.
  /// Returns whether Instagram gave a definitive answer (else: try yt-dlp).
  Future<bool> _runInstagram() async {
    final Map<String, dynamic> q;
    try {
      q = await GoCore.instance.instaQuery(pageUrl);
    } on GoCoreException catch (e) {
      dvLog('extract: instagram: ${e.message}');
      return false;
    }
    final story = q['kind'] == 'story';
    ytdlpRunning = true;
    needsInstagramLogin = false;
    ytdlpError = null;
    matchStatus = 'Querying Instagram…';
    _notify();
    final sw = Stopwatch()..start();
    var loggedIn = false;
    try {
      loggedIn = await InstagramChannel.isLoggedIn();
      Map<String, dynamic>? data;
      if (!story) {
        try {
          data = await _instaQuery(q, 'public');
        } on GoCoreException catch (e) {
          // Not public, or blocked (HTML instead of JSON): the session may still work.
          if (!loggedIn || e.code == 'notfound') rethrow;
          dvLog('extract: instagram: public query failed (${e.code}), retrying with the session');
        }
      } else if (!loggedIn) {
        throw GoCoreException('Stories are only visible when signed in to Instagram.', code: 'unavailable');
      }
      if (data == null) {
        matchStatus = 'Querying Instagram with your account…';
        _notify();
        data = await _instaQuery(q, story ? 'story' : 'media');
      }
      dvLog('extract: instagram ${sw.elapsedMilliseconds}ms options=${(data['options'] as List?)?.length ?? 0}');
      title = _nonEmpty(data['title'] as String?) ?? title;
      thumbnail = _nonEmpty(data['thumbnail'] as String?) ?? thumbnail;
      final basic = data['music'] as Map?;
      if (basic != null && (basic['duration'] as num? ?? 0) > 0) _musicBasic = Map<String, dynamic>.from(basic);
      _merge(data);
      for (final j in (data['audioOptions'] as List? ?? const [])) {
        audioOptions.add(MediaOption.fromJson(Map<String, dynamic>.from(j as Map)));
      }
      return true;
    } on GoCoreException catch (e) {
      dvLog('extract: instagram failed in ${sw.elapsedMilliseconds}ms: ${e.message} (${e.code})');
      ytdlpError = e.message;
      // Not public / expired session: signing in (again) can solve it.
      needsInstagramLogin = e.code == 'unavailable' || e.code == 'login' || (!loggedIn && e.code != 'notfound');
      return true;
    } catch (e) {
      dvLog('extract: instagram query error: $e');
      return false;
    } finally {
      matchStatus = null;
      ytdlpRunning = false;
      _notify();
    }
  }

  Future<Map<String, dynamic>> _instaQuery(Map<String, dynamic> q, String mode) async {
    final body = await InstagramChannel.query({...q, 'mode': mode});
    return GoCore.instance.instaParse(body, storyPk: q['storyPk'] as String?);
  }

  /// Post Threads only shows to signed-in users: the sheet offers "Sign in
  /// to Threads" and retries after login.
  bool needsThreadsLogin = false;

  /// Threads post: the WebView opens the post page and Go reads the post from
  /// its embedded data (Instagram's media schema). Returns whether Threads
  /// gave a definitive answer (else: yt-dlp and the generic scan).
  Future<bool> _runThreads() async {
    final Map<String, dynamic> q;
    try {
      q = await GoCore.instance.threadsQuery(pageUrl);
    } on GoCoreException catch (e) {
      dvLog('extract: threads: ${e.message}');
      return false;
    }
    ytdlpRunning = true;
    ytdlpError = null;
    needsThreadsLogin = false;
    matchStatus = 'Querying Threads…';
    _notify();
    final sw = Stopwatch()..start();
    var loggedIn = false;
    try {
      // The query's WebView shares the app's cookies: with a session, the
      // page shows what the user's account can see.
      loggedIn = await InstagramChannel.isLoggedIn(site: 'threads');
      final body = await InstagramChannel.query({...q, 'mode': 'threads'});
      final data = await GoCore.instance.threadsParse(body, q['shortcode'] as String? ?? '');
      dvLog('extract: threads ${sw.elapsedMilliseconds}ms options=${(data['options'] as List?)?.length ?? 0}');
      title = _nonEmpty(data['title'] as String?) ?? title;
      thumbnail = _nonEmpty(data['thumbnail'] as String?) ?? thumbnail;
      final basic = data['music'] as Map?;
      if (basic != null && (basic['duration'] as num? ?? 0) > 0) _musicBasic = Map<String, dynamic>.from(basic);
      _merge(data);
      for (final j in (data['audioOptions'] as List? ?? const [])) {
        audioOptions.add(MediaOption.fromJson(Map<String, dynamic>.from(j as Map)));
      }
      return true;
    } on GoCoreException catch (e) {
      dvLog('extract: threads failed in ${sw.elapsedMilliseconds}ms: ${e.message} (${e.code})');
      // Text-only post, or one Threads only shows to signed-in users (yt-dlp
      // and the page scan cannot see it either): a definitive answer.
      if (e.code == 'nomedia') {
        ytdlpError = e.message;
      } else if (e.code == 'unavailable') {
        ytdlpError = loggedIn
            ? 'Threads did not show this post to your account either (private profile you don\'t follow, or removed).'
            : 'This post is only visible to people signed in to Threads (restricted profile or content).';
        needsThreadsLogin = !loggedIn;
      } else {
        return false;
      }
      return true;
    } catch (e) {
      dvLog('extract: threads query error: $e');
      return false;
    } finally {
      matchStatus = null;
      ytdlpRunning = false;
      _notify();
    }
  }

  /// "Sign in to Threads": opens the login page, then retries this link.
  Future<void> loginToThreads() async {
    final ok = await InstagramChannel.login(site: 'threads');
    dvLog('extract: threads login -> $ok');
    if (ok && !_disposed) await _runThreads();
  }

  /// "Entrar no Instagram": opens the login page, then retries this link.
  Future<void> loginToInstagram() async {
    final ok = await InstagramChannel.login();
    dvLog('extract: instagram login -> $ok');
    if (ok && !_disposed) await _runInstagram();
  }

  /// Resolves tags + cover (Deezer/iTunes in Go) for the audio file.
  Future<void> _resolveMeta() async {
    final basic = _musicBasic;
    if (basic == null || musicMeta != null || resolvingMeta) return;
    resolvingMeta = true;
    _notify();
    try {
      musicMeta = await GoCore.instance.musicMeta({
        'basic': {...basic, 'musicContext': _musicContext},
        'override': _linkMeta,
      });
      dvLog(
        'extract: music meta ${musicMeta!['artist']} - ${musicMeta!['title']} '
        '(${musicMeta!['album']} ${musicMeta!['year']}) via ${musicMeta!['source']}',
      );
    } catch (e) {
      dvLog('extract: music meta failed: $e');
    } finally {
      resolvingMeta = false;
      _notify();
    }
  }

  /// Spotify/Deezer/Apple Music: read the track, search YouTube Music and
  /// take the first candidate whose duration matches (±5 s).
  Future<void> _runMusicLink() async {
    mode = MediaMode.audio;
    ytdlpRunning = true;
    matchStatus = 'Reading the track on ${platform ?? 'the service'}…';
    _notify();
    try {
      final r = await GoCore.instance.musicLink(pageUrl);
      _linkMeta = Map<String, dynamic>.from(r['meta'] as Map);
      title = _linkMeta!['artist'] != null
          ? '${_linkMeta!['artist']} - ${_linkMeta!['title']}'
          : _linkMeta!['title'] as String?;
      thumbnail = _linkMeta!['coverUrl'] as String?;
      matchStatus = 'Looking for the same track on YouTube Music…';
      _notify();

      final search = await YtdlpChannel.search(r['searchUrl'] as String);
      if (search['exitCode'] != 0) throw GoCoreException('YouTube Music search failed');
      final picked = await GoCore.instance.musicPick({'searchJson': search['json'], 'meta': _linkMeta, 'max': 3});
      final want = (_linkMeta!['duration'] as num?)?.toDouble();
      for (final c in (picked['candidates'] as List).cast<String>()) {
        dvLog('extract: trying candidate $c');
        matchStatus = 'Checking the version found…';
        _notify();
        if (await _runYtdlp(url: c, wantDuration: want)) {
          matchStatus = null;
          return;
        }
      }
      ytdlpError = 'Could not find this track (same duration) on YouTube Music.';
    } on GoCoreException catch (e) {
      ytdlpError = e.message;
      dvLog('extract: music link failed: ${e.message}');
    } catch (e) {
      ytdlpError = 'Could not read the link: $e';
      dvLog('extract: music link error: $e');
    } finally {
      matchStatus = null;
      ytdlpRunning = false;
      _notify();
    }
  }

  /// Tags for the audio file (resolved, or the extractor's basics).
  Map<String, dynamic> get _audioMeta =>
      musicMeta ??
      _linkMeta ??
      {
        'title': title ?? _musicBasic?['title'] ?? 'Audio',
        'artist': _musicBasic?['artist'] ?? _musicBasic?['uploader'],
        'duration': _musicBasic?['duration'],
        'coverUrl': _musicBasic?['thumbnail'],
        'coverSquare': _musicBasic?['thumbSquare'] ?? false,
      };

  /// Go job request + gallery title for an option.
  Future<(Map<String, dynamic>, String)> _buildRequest(MediaOption o) async {
    final dir = await MediaChannel.workDir();
    final ext = o.isAudio ? (o.audioFormat ?? 'm4a') : (o.isImage ? 'jpg' : 'mp4');
    if (o.isAudio && musicMeta == null) await _resolveMeta();
    final out = '$dir/dl_${DateTime.now().microsecondsSinceEpoch}.$ext';
    final req = {
      'kind': o.kind,
      'url': o.url,
      if (o.audioUrl != null) 'audioUrl': o.audioUrl,
      'outPath': out,
      'tmpDir': '$out.parts',
      if (o.chunkSize > 0) 'chunkSize': o.chunkSize,
      // HLS too: ffmpeg assembles segments far more robustly than the Go
      // remux (e.g. X/Twitter's negative composition offsets).
      if (o.kind == 'merge' || o.isAudio || o.isHls) 'ffmpeg': await YtdlpChannel.ffmpeg(),
      if (o.isAudio) ...{
        'audioFormat': o.audioFormat,
        'audioQuality': o.audioQuality,
        'sourceHls': o.sourceHls,
        'meta': _audioMeta,
      },
      'headers': {
        ..._headers,
        // yt-dlp formats carry their own headers (UA, cookies) and must not
        // get the page referer added.
        if (o.headers.isEmpty) 'referer': o.referer ?? pageUrl,
        if (o.cookie != null) 'cookie': o.cookie,
        if (o.headers.isNotEmpty) 'extra': o.headers,
      },
    };
    final m = _audioMeta;
    var name = o.isAudio
        ? [m['artist'], m['title']].whereType<String>().where((x) => x.isNotEmpty).join(' - ')
        : title ?? Uri.parse(pageUrl).host;
    if (o.item > 0) name = '$name (${o.item} of ${o.itemCount})';
    // Shown in the downloads screen; pageUrl + optionKey renew expired links.
    req['meta'] = {
      'title': name,
      'pageUrl': pageUrl,
      'optionKey': o.optionKey,
      'thumbnail': o.itemThumb ?? (o.isAudio ? (musicMeta?['coverUrl'] ?? thumbnail) : thumbnail),
    };
    return (req, name);
  }

  /// Source fields of [o] for renewing an expired job (DV_Replace).
  Future<Map<String, dynamic>> sourceFor(MediaOption o) async {
    final (req, _) = await _buildRequest(o);
    return {'url': req['url'], 'audioUrl': req['audioUrl'] ?? '', 'headers': req['headers'], 'chunkSize': o.chunkSize};
  }

  /// Waits until extraction finished (options listed or error).
  Future<void> settled({Duration timeout = const Duration(seconds: 60)}) async {
    final end = DateTime.now().add(timeout);
    while (DateTime.now().isBefore(end) && !_disposed) {
      if (!scanning && !resolvingMeta) return;
      await Future<void>.delayed(const Duration(milliseconds: 250));
    }
  }

  /// All options (video + audio), for matching an optionKey.
  List<MediaOption> get allOptions => [...options, ...audioOptions];

  // ---- carousel posts: the user ticks which items to download

  bool get isCarousel => options.any((o) => o.itemCount > 1);

  List<MediaOption> get carouselItems => options.where((o) => o.item > 0).toList()..sort((a, b) => a.item - b.item);

  final Set<int> checkedItems = {};
  int itemsTotal = 0;
  int itemsSaved = 0;
  int itemsFailed = 0;
  final Map<int, double> _itemPct = {};
  final List<DownloadHandle> _handles = [];

  void toggleItem(int item) {
    if (busy) return;
    checkedItems.contains(item) ? checkedItems.remove(item) : checkedItems.add(item);
    _notify();
  }

  void setAllItems(bool checked) {
    if (busy) return;
    checkedItems.clear();
    if (checked) checkedItems.addAll(carouselItems.map((o) => o.item));
    _notify();
  }

  /// Downloads the ticked carousel items. Each item is its own Go job (own
  /// notification); the sheet shows the aggregate.
  Future<void> downloadItems() async {
    final items = carouselItems.where((o) => checkedItems.contains(o.item)).toList();
    if (items.isEmpty || busy) return;
    unawaited(DownloadsChannel.instance.ensureNotificationPermission().catchError((_) => false));
    phase = DownloadPhase.downloading;
    error = warning = null;
    saved = null;
    percent = 0;
    itemsTotal = items.length;
    itemsSaved = itemsFailed = 0;
    _itemPct.clear();
    _notify();
    dvLog('extract: downloading ${items.length} carousel items');

    // All items start at once (Go downloads them in parallel): if the sheet
    // is hidden, every item is already a job the service will finish.
    Future<void> one(MediaOption o) async {
      final r = await _runJob(o, (p) {
        _itemPct[o.item] = p;
        percent = _itemPct.values.fold<double>(0, (a, b) => a + b) / itemsTotal;
        _notify();
      });
      if (r == null) {
        itemsFailed++;
      } else {
        itemsSaved++;
        saved ??= r;
        _itemPct[o.item] = 100;
      }
      _notify();
    }

    await Future.wait(items.map(one));
    if (_hidden || _disposed) return;
    if (itemsSaved == 0) {
      phase = DownloadPhase.failed;
      error ??= 'No item was downloaded.';
    } else {
      phase = DownloadPhase.saved;
      if (itemsFailed > 0) warning = '$itemsFailed of $itemsTotal items failed.';
    }
    _notify();
  }

  /// One Go job tracked by the service; returns the saved file or null.
  Future<SavedVideo?> _runJob(MediaOption o, void Function(double percent) onProgress) async {
    final (req, name) = await _buildRequest(o);
    final h = GoCore.instance.download(req);
    _handles.add(h);
    var tracked = false;
    if (h.id >= 0) {
      try {
        tracked = await DownloadsChannel.instance.track(h.id, name);
      } catch (e) {
        dvLog('extract: track failed: $e');
      }
    }
    final serviceResult = tracked ? DownloadsChannel.instance.events.firstWhere((e) => e['id'] == h.id) : null;
    String? donePath;
    await for (final ev in h.events) {
      switch (ev['type']) {
        case 'progress':
          onProgress((ev['percent'] as num?)?.toDouble() ?? 0);
        case 'done':
          donePath = ev['path'] as String;
        case 'error':
          error = ev['message'] as String?;
          dvLog('extract: item ${o.item} error: $error');
      }
    }
    _handles.remove(h);
    if (donePath == null || _hidden) return null;
    try {
      if (serviceResult != null) {
        final r = await serviceResult;
        if (r['event'] != 'saved') return null;
        return SavedVideo(
          r['uri'] as String,
          r['displayName'] as String,
          r['location'] as String,
          r['mime'] as String? ?? 'video/mp4',
        );
      }
      return await MediaChannel.saveVideo(donePath, name);
    } catch (e) {
      dvLog('extract: item ${o.item} save failed: $e');
      return null;
    }
  }

  Future<void> download() async {
    final o = selected;
    if (o == null || busy) return;
    // Stop sniffing: the page keeps playing video and competes for bandwidth.
    if (sniffing) unawaited(SnifferChannel.instance.stop(_session));
    // Progress is shown in a notification; Android 13+ needs the permission.
    unawaited(DownloadsChannel.instance.ensureNotificationPermission().catchError((_) => false));

    phase = DownloadPhase.downloading;
    error = warning = null;
    saved = null;
    percent = null;
    bytes = 0;
    total = o.size;
    _notify();

    final (req, videoTitle) = await _buildRequest(o);
    final out = req['outPath'] as String;
    dvLog('extract: download ${o.kind} "${o.label}" -> $out');
    final h = GoCore.instance.download(req);
    _download = h;

    // The foreground service tracks the job (notification) and owns saving it
    // to the gallery, so it completes even if this sheet is hidden.
    var tracked = false;
    if (h.id >= 0) {
      try {
        tracked = await DownloadsChannel.instance.track(h.id, videoTitle);
      } catch (e) {
        dvLog('extract: track failed: $e');
      }
      dvLog('extract: job ${h.id} tracked by service=$tracked');
    }
    final serviceResult = tracked ? DownloadsChannel.instance.events.firstWhere((e) => e['id'] == h.id) : null;

    String? donePath;
    await for (final ev in h.events) {
      switch (ev['type']) {
        case 'progress':
          phase = switch (ev['phase']) {
            'muxing' => DownloadPhase.muxing,
            'converting' => DownloadPhase.converting,
            _ => DownloadPhase.downloading,
          };
          bytes = (ev['bytes'] as num?)?.toInt() ?? 0;
          final t = (ev['total'] as num?)?.toInt() ?? -1;
          if (t > 0) total = t;
          totalIsEstimate = ev['totalEstimate'] as bool? ?? false;
          final p = (ev['percent'] as num?)?.toDouble();
          percent = (p == null || p <= 0) ? null : p;
          speedBps = (ev['speedBps'] as num?)?.toDouble() ?? 0;
          segmentsDone = (ev['segmentsDone'] as num?)?.toInt() ?? 0;
          segmentsTotal = (ev['segmentsTotal'] as num?)?.toInt() ?? 0;
        case 'done':
          donePath = ev['path'] as String;
          warning = ev['warning'] as String?;
          bytes = total = (ev['size'] as num?)?.toInt() ?? bytes;
          percent = 100;
          dvLog('extract: download done size=$bytes durationMs=${ev['durationMs']} warning=$warning');
        case 'error':
          phase = DownloadPhase.failed;
          error = ev['message'] as String? ?? 'unknown error';
          dvLog('extract: download error: $error');
        case 'canceled':
          phase = DownloadPhase.canceled;
          dvLog('extract: download canceled');
        case 'waiting':
          speedBps = 0;
          error = 'No connection — retrying automatically…';
        case 'paused':
          phase = DownloadPhase.failed;
          error = 'Download paused. Resume it from the Downloads screen.';
        case 'expired':
          phase = DownloadPhase.failed;
          error = 'The link expired. Open Downloads to renew it and continue.';
      }
      if (ev['type'] == 'progress') error = null;
      _notify();
    }
    if (_hidden) return; // detached: the service finishes the job
    _download = null;
    if (donePath == null) return;

    phase = DownloadPhase.saving;
    _notify();
    if (serviceResult != null) {
      final r = await serviceResult;
      if (_disposed) return;
      if (r['event'] == 'saved') {
        saved = SavedVideo(
          r['uri'] as String,
          r['displayName'] as String,
          r['location'] as String,
          r['mime'] as String? ?? 'video/mp4',
        );
        phase = DownloadPhase.saved;
        dvLog('extract: saved by service ${saved!.location}/${saved!.displayName}');
      } else {
        phase = DownloadPhase.failed;
        error = 'Could not save to the gallery: ${r['message']}';
      }
      _notify();
      return;
    }
    // Fallback when the service could not start: save from here.
    try {
      saved = await MediaChannel.saveVideo(donePath, videoTitle);
      phase = DownloadPhase.saved;
      dvLog('extract: saved ${saved!.location}/${saved!.displayName} ${saved!.uri}');
    } catch (e) {
      phase = DownloadPhase.failed;
      error = 'Could not save to the gallery: $e';
      dvLog('extract: save failed: $e');
    }
    _notify();
  }

  void cancelDownload() {
    dvLog('extract: cancel requested');
    for (final h in [?_download, ..._handles]) {
      GoCore.instance.cancel(h.id);
    }
  }

  bool _hidden = false;

  /// Leaves a running download to the foreground service (notification) and
  /// stops listening here. Called when the sheet is hidden or closed.
  void hide() {
    final hs = [?_download, ..._handles];
    if (hs.isEmpty || _hidden) return;
    _hidden = true;
    _busyControllers.remove(this);
    for (final h in hs) {
      dvLog('extract: job ${h.id} hidden; continuing in background');
      h.detach();
    }
  }

  static final Set<ExtractController> _busyControllers = {};

  /// Whether any download is running in this isolate.
  static bool get anyBusy => _busyControllers.isNotEmpty;

  /// Detaches every running download before this isolate goes away (the
  /// activity finishing can outrun widget disposal).
  static void hideAll() {
    for (final c in List.of(_busyControllers)) {
      c.hide();
    }
  }

  void _notify() {
    busy && !_hidden ? _busyControllers.add(this) : _busyControllers.remove(this);
    if (!_disposed) notifyListeners();
  }

  static String? _nonEmpty(String? s) => (s == null || s.trim().isEmpty) ? null : s.trim();

  @override
  void dispose() {
    _disposed = true;
    _busyControllers.remove(this);
    _flushTimer?.cancel();
    for (final s in _subs) {
      s.cancel();
    }
    if (sniffing) SnifferChannel.instance.stop(_session);
    if (ytdlpRunning) YtdlpChannel.cancel(_ytdlpId);
    // Closing the sheet never cancels: the download moves to the background.
    hide();
    super.dispose();
  }
}
