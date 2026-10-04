import 'dart:async';

import 'package:flutter/material.dart';

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
                Tab(icon: Icon(c.icon), text: groups[c]!.isEmpty ? c.label : '${c.label} (${groups[c]!.length})'),
            ],
          ),
        ),
        body: TabBarView(children: [for (final c in tabs) _list(groups[c]!, c)]),
      ),
    );
  }

  Widget _list(List<Map<String, dynamic>> jobs, _Category c) {
    final active = jobs.where((j) => _active.contains(j['state'])).toList();
    final saved = jobs.where((j) => j['state'] == 'saved').toList();
    if (jobs.isEmpty) return Center(child: Text(c.empty));
    return ListView(
      children: [
        if (active.isNotEmpty) ...[
          const _Section('In progress'),
          for (final j in active) _JobTile(job: j, renewing: _renewing.contains(j['id']), page: this),
        ],
        if (saved.isNotEmpty) ...[
          const _Section('Completed'),
          for (final j in saved) _JobTile(job: j, renewing: false, page: this),
        ],
      ],
    );
  }
}

/// Tabs of the downloads screen, by what the job produces.
enum _Category {
  video('Videos', Icons.movie_outlined, 'No videos downloaded yet.'),
  music('Music', Icons.music_note_outlined, 'No music downloaded yet.'),
  photo('Photos', Icons.image_outlined, 'No photos downloaded yet.');

  const _Category(this.label, this.icon, this.empty);
  final String label;
  final IconData icon;
  final String empty;

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

class _Section extends StatelessWidget {
  const _Section(this.title);
  final String title;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
    child: Text(title, style: Theme.of(context).textTheme.titleSmall),
  );
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
    final percent = (job['percent'] as num?)?.toDouble() ?? 0;
    final running = state == 'running';
    return ListTile(
      leading: _Thumb(url: job['thumbnail'] as String?, kind: job['kind'] as String?),
      title: Text(job['title'] as String? ?? 'Download', maxLines: 2, overflow: TextOverflow.ellipsis),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (running || state == 'paused' || state == 'waiting')
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: LinearProgressIndicator(value: percent > 0 ? percent / 100 : null),
            ),
          Text(
            _statusText(),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: state == 'error' ? theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error) : null,
          ),
        ],
      ),
      trailing: _actions(context),
      onTap: state == 'saved' ? () => _open() : null,
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
            IconButton(tooltip: 'Pause', icon: const Icon(Icons.pause), onPressed: () => go.pause(id)),
            IconButton(tooltip: 'Cancel', icon: const Icon(Icons.close), onPressed: () => go.cancel(id)),
          ],
        );
      case 'paused' || 'error':
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              tooltip: state == 'paused' ? 'Resume' : 'Retry',
              icon: Icon(state == 'paused' ? Icons.play_arrow : Icons.refresh),
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
          itemBuilder: (_) => const [
            PopupMenuItem(value: 'open', child: Text('Open')),
            PopupMenuItem(value: 'share', child: Text('Share')),
            PopupMenuItem(value: 'forget', child: Text('Remove from history')),
            PopupMenuItem(value: 'delete', child: Text('Delete file')),
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
  const _Thumb({required this.url, required this.kind});
  final String? url;
  final String? kind;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final icon = switch (kind) {
      'audio' => Icons.music_note_outlined,
      'image' => Icons.image_outlined,
      _ => Icons.movie_outlined,
    };
    final placeholder = Container(
      color: theme.colorScheme.surfaceContainerHighest,
      child: Icon(icon, color: theme.colorScheme.outline),
    );
    return ClipRRect(
      borderRadius: BorderRadius.circular(6),
      child: SizedBox.square(
        dimension: 52,
        child: url == null
            ? placeholder
            : Image.network(url!, fit: BoxFit.cover, errorBuilder: (_, _, _) => placeholder),
      ),
    );
  }
}
