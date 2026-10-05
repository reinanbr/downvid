import 'dart:async';

import 'package:flutter/material.dart';

import '../app_theme.dart';
import '../brand.dart';
import '../core/native/go_core.dart';
import '../core/native/platform_bridge.dart';
import 'link_renewer.dart';
import 'models.dart';

/// Downloads screen: the persistent queue (in progress / failed) and the
/// history of saved files. Polls the Go core once per second.
class DownloadsPage extends StatefulWidget {
  const DownloadsPage({super.key});

  @override
  State<DownloadsPage> createState() => _DownloadsPageState();
}

const _active = {'queued', 'running', 'waiting', 'done', 'paused', 'error', 'expired'};

class _DownloadsPageState extends State<DownloadsPage> {
  List<Map<String, dynamic>> _jobs = const [];
  Timer? _timer;
  final Set<int> _renewTried = {};
  final Set<int> _renewing = {};

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  void _refresh() {
    final jobs = GoCore.instance.status();
    if (!mounted) return;
    setState(() => _jobs = jobs);
    // Expired links are renewed automatically once per job.
    for (final j in jobs) {
      final id = j['id'] as int;
      if (j['state'] == 'expired' && _renewTried.add(id)) _renew(j);
    }
  }

  Future<void> _renew(Map<String, dynamic> j) async {
    final id = j['id'] as int;
    setState(() => _renewing.add(id));
    final ok = await LinkRenewer.renew(j);
    if (!mounted) return;
    setState(() => _renewing.remove(id));
    if (!ok) _snack('Could not renew the link of "${j['title'] ?? 'download'}".');
  }

  void _snack(String text) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  Future<void> _delete(Map<String, dynamic> j) async {
    final saved = j['saved'] as Map?;
    final yes = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete file?'),
        content: Text('"${saved?['displayName'] ?? j['title']}" will be deleted from the gallery.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Delete')),
        ],
      ),
    );
    if (yes != true) return;
    if (saved != null) await MediaChannel.deleteMedia(saved['uri'] as String);
    GoCore.instance.remove(j['id'] as int);
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final visible = _jobs.where((j) => _active.contains(j['state']) || j['state'] == 'saved').toList();
    final groups = {for (final c in _Category.values) c: visible.where((j) => _Category.of(j) == c).toList()};
    // Photos only get a tab when there are any.
    final tabs = [_Category.video, _Category.music, if (groups[_Category.photo]!.isNotEmpty) _Category.photo];
    return DefaultTabController(
      length: tabs.length,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Downloads'),
          bottom: TabBar(
            tabs: [
              for (final c in tabs)
                Tab(
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(c.icon, size: 20),
                      const SizedBox(width: 6),
                      Text(c.label),
                      if (groups[c]!.isNotEmpty) ...[
                        const SizedBox(width: 6),
                        Badge(
                          label: Text('${groups[c]!.length}'),
                          backgroundColor: Theme.of(context).colorScheme.secondaryContainer,
                          textColor: Theme.of(context).colorScheme.onSecondaryContainer,
                        ),
                      ],
                    ],
                  ),
                ),
            ],
          ),
        ),
        body: TabBarView(children: [for (final c in tabs) _list(groups[c]!, c)]),
      ),
    );
  }

  Widget _list(List<Map<String, dynamic>> jobs, _Category c) {
    final active = jobs.where((j) => _active.contains(j['state'])).toList();
    final saved = jobs.where((j) => j['state'] == 'saved').toList()
      ..sort((a, b) => (b['id'] as int).compareTo(a['id'] as int));
    if (jobs.isEmpty) return EmptyState(icon: c.icon, title: c.empty, hint: c.hint);
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
      children: [
        if (active.isNotEmpty) ...[
          SectionTitle('In progress · ${active.length}', padding: const EdgeInsets.fromLTRB(4, 16, 4, 8)),
          for (final j in active) _JobTile(job: j, renewing: _renewing.contains(j['id']), page: this),
        ],
        if (saved.isNotEmpty) ...[
          SectionTitle('Completed · ${saved.length}', padding: const EdgeInsets.fromLTRB(4, 16, 4, 8)),
          for (final j in saved) _JobTile(job: j, renewing: false, page: this),
        ],
      ],
    );
  }
}

/// Tabs of the downloads screen, by what the job produces.
enum _Category {
  video(
    'Videos',
    Icons.movie_rounded,
    'No videos yet',
    'Share a video link with DownVid or paste it on the home screen.',
  ),
  music(
    'Music',
    Icons.music_note_rounded,
    'No music yet',
    'Pick "Audio only" in the download sheet to save M4A or MP3.',
  ),
  photo('Photos', Icons.image_rounded, 'No photos yet', 'Instagram photos and carousels show up here.');

  const _Category(this.label, this.icon, this.empty, this.hint);
  final String label;
  final IconData icon;
  final String empty;
  final String hint;

  static _Category of(Map<String, dynamic> job) {
    final mime = (job['saved'] as Map?)?['mime'] as String? ?? '';
    return switch (job['kind']) {
      'audio' => music,
      'image' => photo,
      _ when mime.startsWith('audio/') => music,
      _ when mime.startsWith('image/') => photo,
      _ => video,
    };
  }
}

class _JobTile extends StatelessWidget {
  const _JobTile({required this.job, required this.renewing, required this.page});
  final Map<String, dynamic> job;
  final bool renewing;
  final _DownloadsPageState page;

  int get id => job['id'] as int;
  String get state => job['state'] as String;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final percent = (job['percent'] as num?)?.toDouble() ?? 0;
    final showProgress = state == 'running' || state == 'paused' || state == 'waiting';
    final statusColor = switch (state) {
      'error' => scheme.error,
      'expired' || 'waiting' => scheme.warning,
      'paused' => scheme.onSurfaceVariant,
      'saved' => scheme.onSurfaceVariant,
      _ => scheme.primary,
    };
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Card(
        child: InkWell(
          onTap: state == 'saved' ? _open : null,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 12, 4, 12),
            child: Row(
              children: [
                _Thumb(url: job['thumbnail'] as String?, kind: job['kind'] as String?, done: state == 'saved'),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        job['title'] as String? ?? 'Download',
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.titleSmall,
                      ),
                      const SizedBox(height: 4),
                      if (showProgress) ...[
                        LinearProgressIndicator(
                          value: percent > 0 ? percent / 100 : null,
                          color: state == 'paused' ? scheme.outline : null,
                        ),
                        const SizedBox(height: 6),
                      ],
                      Text(
                        _statusText(),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodySmall?.copyWith(color: statusColor),
                      ),
                    ],
                  ),
                ),
                _actions(context),
              ],
            ),
          ),
        ),
      ),
    );
  }

  String _statusText() {
    final percent = (job['percent'] as num?)?.toDouble() ?? 0;
    final bytes = (job['bytes'] as num?)?.toInt() ?? 0;
    final total = (job['total'] as num?)?.toInt() ?? -1;
    final speed = (job['speedBps'] as num?)?.toDouble() ?? 0;
    String size() => total > 0 ? '${formatBytes(bytes)} / ${formatBytes(total)}' : formatBytes(bytes);
    switch (state) {
      case 'queued':
        return 'Queued';
      case 'running':
        return switch (job['phase']) {
          'muxing' => 'Converting to MP4…',
          'converting' => 'Converting audio… ${percent.toStringAsFixed(0)}%',
          _ => [
            if (percent > 0) '${percent.toStringAsFixed(0)}%',
            size(),
            if (speed > 0) '${formatBytes(speed)}/s',
            if (speed > 0 && total > bytes) _eta((total - bytes) / speed),
          ].join(' · '),
        };
      case 'waiting':
        return 'No connection — retrying';
      case 'paused':
        return 'Paused · ${size()}';
      case 'done':
        return 'Saving to gallery…';
      case 'expired':
        return renewing ? 'Renewing the link…' : 'Link expired';
      case 'error':
        return job['message'] as String? ?? 'Failed';
      case 'saved':
        final s = job['saved'] as Map?;
        return [
          s?['location'] ?? '',
          if ((job['size'] as num? ?? 0) > 0) formatBytes(job['size'] as num),
        ].where((x) => '$x'.isNotEmpty).join(' · ');
    }
    return state;
  }

  static String _eta(double seconds) {
    final s = seconds.round();
    if (s < 60) return '${s}s left';
    if (s < 3600) return '${s ~/ 60} min left';
    return '${s ~/ 3600} h ${(s % 3600) ~/ 60} min left';
  }

  Widget _actions(BuildContext context) {
    final go = GoCore.instance;
    switch (state) {
      case 'running' || 'queued' || 'waiting':
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(tooltip: 'Pause', icon: const Icon(Icons.pause_rounded), onPressed: () => go.pause(id)),
            IconButton(tooltip: 'Cancel', icon: const Icon(Icons.close_rounded), onPressed: () => go.cancel(id)),
          ],
        );
      case 'paused' || 'error':
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              tooltip: state == 'paused' ? 'Resume' : 'Retry',
              icon: Icon(state == 'paused' ? Icons.play_arrow_rounded : Icons.refresh_rounded),
              onPressed: () => go.resume(id),
            ),
            IconButton(tooltip: 'Remove', icon: const Icon(Icons.delete_outline), onPressed: () => go.remove(id)),
          ],
        );
      case 'expired':
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            renewing
                ? const Padding(
                    padding: EdgeInsets.all(12),
                    child: SizedBox.square(dimension: 20, child: CircularProgressIndicator(strokeWidth: 2)),
                  )
                : IconButton(tooltip: 'Renew link', icon: const Icon(Icons.refresh), onPressed: () => page._renew(job)),
            IconButton(tooltip: 'Remove', icon: const Icon(Icons.delete_outline), onPressed: () => go.remove(id)),
          ],
        );
      case 'saved':
        return PopupMenuButton<String>(
          onSelected: (v) => switch (v) {
            'open' => _open(),
            'share' => _share(),
            'forget' => go.remove(id),
            _ => page._delete(job),
          },
          icon: const Icon(Icons.more_vert_rounded),
          itemBuilder: (_) => const [
            PopupMenuItem(
              value: 'open',
              child: ListTile(leading: Icon(Icons.play_arrow_rounded), title: Text('Open')),
            ),
            PopupMenuItem(
              value: 'share',
              child: ListTile(leading: Icon(Icons.share_rounded), title: Text('Share')),
            ),
            PopupMenuItem(
              value: 'forget',
              child: ListTile(leading: Icon(Icons.history_rounded), title: Text('Remove from history')),
            ),
            PopupMenuItem(
              value: 'delete',
              child: ListTile(leading: Icon(Icons.delete_outline_rounded), title: Text('Delete file')),
            ),
          ],
        );
    }
    return const SizedBox.shrink();
  }

  Map<String, dynamic>? get _saved => job['saved'] == null ? null : Map<String, dynamic>.from(job['saved'] as Map);

  void _open() {
    final s = _saved;
    if (s != null) MediaChannel.openVideo(s['uri'] as String, s['mime'] as String? ?? 'video/mp4');
  }

  void _share() {
    final s = _saved;
    if (s != null) MediaChannel.shareMedia(s['uri'] as String, s['mime'] as String? ?? 'video/mp4');
  }
}

class _Thumb extends StatelessWidget {
  const _Thumb({required this.url, required this.kind, required this.done});
  final String? url;
  final String? kind;
  final bool done;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final icon = switch (kind) {
      'audio' => Icons.music_note_rounded,
      'image' => Icons.image_rounded,
      _ => Icons.movie_rounded,
    };
    final placeholder = Container(
      color: theme.colorScheme.surfaceContainerHighest,
      child: Icon(icon, color: theme.colorScheme.outline),
    );
    // Videos keep their 16:9 frame; music covers and photos are square.
    final width = kind == 'audio' || kind == 'image' ? 56.0 : 88.0;
    return ClipRRect(
      borderRadius: BorderRadius.circular(12),
      child: SizedBox(
        width: width,
        height: 56,
        child: Stack(
          fit: StackFit.expand,
          children: [
            url == null ? placeholder : Image.network(url!, fit: BoxFit.cover, errorBuilder: (_, _, _) => placeholder),
            if (done && kind != 'image')
              Center(
                child: Container(
                  padding: const EdgeInsets.all(4),
                  decoration: const BoxDecoration(color: Colors.black45, shape: BoxShape.circle),
                  child: Icon(
                    kind == 'audio' ? Icons.music_note_rounded : Icons.play_arrow_rounded,
                    size: 18,
                    color: Colors.white,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
