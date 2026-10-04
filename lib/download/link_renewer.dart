import '../core/log.dart';
import '../core/native/go_core.dart';
import '../core/url/link_parser.dart';
import 'extract_controller.dart';

/// Signed CDN links (YouTube, Instagram, X...) expire after a few hours. When
/// a job reports "expired", the original link is extracted again, the same
/// choice (optionKey: quality/format/item) is located and its fresh URLs are
/// handed to the Go job, which continues the partial download.
abstract final class LinkRenewer {
  static final Set<int> _running = {};

  static Future<bool> renew(Map<String, dynamic> job) async {
    final id = job['id'] as int;
    final pageUrl = job['pageUrl'] as String?;
    final key = job['optionKey'] as String?;
    if (pageUrl == null || key == null || !_running.add(id)) return false;
    dvLog('renew: job $id from $pageUrl');
    final link = parseSharedText(pageUrl);
    if (link == null) {
      _running.remove(id);
      return false;
    }
    final isPlatform = link.platform != SourcePlatform.generic;
    final c = ExtractController(
      link.uri.toString(),
      platform: isPlatform ? link.platform.label : null,
      isPlatform: isPlatform,
      isMusicService: link.isMusicService,
    );
    try {
      await c.start();
      await c.settled();
      final o = c.allOptions.where((o) => o.optionKey == key).firstOrNull;
      if (o == null) {
        dvLog('renew: job $id: option not found among ${c.allOptions.length} (${c.ytdlpError ?? 'no error'})');
        return false;
      }
      GoCore.instance.replace(id, await c.sourceFor(o));
      dvLog('renew: job $id renewed');
      return true;
    } catch (e) {
      dvLog('renew: job $id failed: $e');
      return false;
    } finally {
      c.dispose();
      _running.remove(id);
    }
  }
}
