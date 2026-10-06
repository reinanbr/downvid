/// One downloadable choice returned by DV_Probe (scan.Option in Go).
class MediaOption {
  MediaOption.fromJson(Map<String, dynamic> j)
    : kind = j['kind'] as String,
      label = j['label'] as String,
      url = j['url'] as String,
      audioUrl = j['audioUrl'] as String?,
      height = (j['height'] as num?)?.toInt() ?? 0,
      bandwidth = (j['bandwidth'] as num?)?.toInt() ?? 0,
      size = (j['size'] as num?)?.toInt() ?? -1,
      sizeExact = j['sizeExact'] as bool? ?? false,
      durationSec = (j['durationSec'] as num?)?.toDouble() ?? 0,
      source = j['source'] as String? ?? '',
      group = j['group'] as String? ?? '',
      referer = j['referer'] as String?,
      cookie = j['cookie'] as String?,
      small = j['small'] as bool? ?? false,
      headers = Map<String, String>.from(j['headers'] as Map? ?? const {}),
      chunkSize = (j['chunkSize'] as num?)?.toInt() ?? 0,
      vcodec = j['vcodec'] as String?,
      fps = (j['fps'] as num?)?.toDouble() ?? 0,
      audioFormat = j['audioFormat'] as String?,
      audioQuality = j['audioQuality'] as String?,
      sourceHls = j['sourceHls'] as bool? ?? false,
      item = (j['item'] as num?)?.toInt() ?? 0,
      itemCount = (j['itemCount'] as num?)?.toInt() ?? 0,
      itemThumb = j['itemThumb'] as String?;

  final String kind; // mp4 | hls
  final String label;
  final String url;
  final String? audioUrl;
  final int height;
  final int bandwidth;
  final int size;
  final bool sizeExact;
  final double durationSec;
  final String source; // html | meta | jsonld | iframe | webview | direct
  final String group; // candidate URL it came from
  final String? referer;
  final String? cookie;

  /// Under 1 MB: likely a preview of another video or a stream fragment.
  final bool small;

  /// yt-dlp options: request headers, Range chunk cap, codec info.
  final Map<String, String> headers;
  final int chunkSize;
  final String? vcodec;
  final double fps;

  /// Audio-only options: m4a|mp3, copy|v0|320|aac256, HLS source.
  final String? audioFormat;
  final String? audioQuality;
  final bool sourceHls;

  /// Carousel item (1-based) / total / per-item thumbnail.
  final int item;
  final int itemCount;
  final String? itemThumb;

  bool get isAudio => kind == 'audio';
  bool get isImage => kind == 'image';

  bool get isHls => kind == 'hls';

  /// Identifies "the same choice" in a later extraction of the same link
  /// (URLs change; quality/format/item don't) — used to renew expired links.
  String get optionKey => [kind, height, vcodec ?? '', audioFormat ?? '', audioQuality ?? '', item, label].join('|');

  /// Stable identity across probe rounds.
  String get key => '$kind|$url|${audioUrl ?? ''}|${audioFormat ?? ''}|${audioQuality ?? ''}';

  String get sourceLabel => switch (source) {
    'webview' => 'browser',
    'iframe' => 'embedded player',
    'meta' || 'jsonld' => 'metadata',
    'direct' => 'direct link',
    'ytdlp' => 'yt-dlp',
    'instagram' => 'Instagram',
    'threads' => 'Threads',
    _ => 'page',
  };
}

class Skipped {
  Skipped(this.url, this.reason);
  final String url;
  final String reason;
}

String formatBytes(num bytes) {
  if (bytes < 0) return '?';
  const units = ['B', 'KB', 'MB', 'GB'];
  var v = bytes.toDouble();
  var i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return '${v.toStringAsFixed(v >= 100 || i == 0 ? 0 : 1)} ${units[i]}';
}

String formatDuration(double seconds) {
  if (seconds <= 0) return '';
  final d = Duration(milliseconds: (seconds * 1000).round());
  final h = d.inHours;
  final m = d.inMinutes.remainder(60).toString().padLeft(h > 0 ? 2 : 1, '0');
  final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
  return h > 0 ? '$h:$m:$s' : '$m:$s';
}
