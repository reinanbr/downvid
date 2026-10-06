import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app_theme.dart';
import 'brand.dart';
import 'core/log.dart';
import 'core/native/go_core.dart';
import 'core/native/platform_bridge.dart';
import 'core/settings.dart';
import 'settings/disclaimer.dart';
import 'settings/settings_page.dart';
import 'core/url/link_parser.dart';
import 'download/downloads_page.dart';
import 'share/share_app.dart';
import 'share/share_sheet.dart';

/// Full app (launcher icon).
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppSettings.instance.load();
  runApp(const DownVidApp());
}

/// Entry point used by ShareActivity: only the bottom sheet, no app shell.
@pragma('vm:entry-point')
Future<void> shareMain() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppSettings.instance.load();
  runApp(const ShareApp());
}

class DownVidApp extends StatelessWidget {
  const DownVidApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'DownVid',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light,
      darkTheme: AppTheme.dark,
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  final _url = TextEditingController();

  // Link found in the clipboard when the app comes to the foreground.
  SharedLink? _clipLink;
  int _clipSeenAt = -1; // timestamp of the last clip already examined
  final Set<String> _offered = {}; // never offer the same link twice
  int _activeDownloads = 0;
  List<Map<String, dynamic>> _recent = const [];
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) => showDisclaimer(context));
    _scheduleClipboardCheck();
    _countDownloads();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => _countDownloads());
  }

  void _countDownloads() {
    final jobs = GoCore.instance.status();
    final n = jobs.where((j) => const {'queued', 'running', 'waiting', 'done', 'expired'}.contains(j['state'])).length;
    // Newest saved files first (ids grow over time).
    final recent = jobs.where((j) => j['state'] == 'saved').toList()
      ..sort((a, b) => (b['id'] as int).compareTo(a['id'] as int));
    final ids = recent.take(8).map((j) => j['id']).join(',');
    if (!mounted) return;
    if (n != _activeDownloads || ids != _recent.map((j) => j['id']).join(',')) {
      setState(() {
        _activeDownloads = n;
        _recent = recent.take(8).toList();
      });
    }
  }

  void _openDownloads() =>
      Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const DownloadsPage())).then((_) {
        _countDownloads();
      });

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _scheduleClipboardCheck();
  }

  // The clipboard is readable only once the window has focus, which comes a
  // moment after "resumed".
  void _scheduleClipboardCheck() => Future<void>.delayed(const Duration(milliseconds: 500), _checkClipboard);

  Future<void> _checkClipboard() async {
    if (!mounted || !AppSettings.instance.clipboardCheck) return;
    try {
      // Cheap check first: reading the content makes Android show a
      // "pasted from clipboard" toast, so only read new clips.
      final info = await ClipboardChannel.info();
      if (!info.hasText || (info.timestamp != 0 && info.timestamp == _clipSeenAt)) return;
      _clipSeenAt = info.timestamp;
      final link = parseSharedText(await ClipboardChannel.read());
      if (link == null || !_offered.add(link.uri.toString())) return;
      dvLog('home: clipboard link ${link.uri}');
      if (mounted) setState(() => _clipLink = link);
    } catch (e) {
      dvLog('home: clipboard check failed: $e');
    }
  }

  void _openClipLink() {
    final link = _clipLink;
    setState(() => _clipLink = null);
    if (link == null) return;
    _url.text = link.uri.toString();
    _analyze();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    _url.dispose();
    super.dispose();
  }

  Future<void> _paste() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    if (data?.text != null) _url.text = data!.text!.trim();
  }

  void _analyze() {
    final text = _url.text.trim();
    if (text.isEmpty) return;
    dvLog('home: analyze $text');
    FocusScope.of(context).unfocus();
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      enableDrag: false,
      builder: (_) => ShareSheet(rawText: text),
    );
  }

  void _openSettings() =>
      Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const SettingsPage())).then((_) {
        if (mounted) setState(() {});
      });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
          children: [
            Row(
              children: [
                const DownVidLogo(size: 36),
                const SizedBox(width: 12),
                Expanded(child: Text('DownVid', style: theme.textTheme.titleLarge)),
                IconButton(
                  tooltip: 'Downloads',
                  onPressed: _openDownloads,
                  icon: Badge(
                    isLabelVisible: _activeDownloads > 0,
                    label: Text('$_activeDownloads'),
                    child: const Icon(Icons.download_rounded),
                  ),
                ),
                IconButton(tooltip: 'Settings', icon: const Icon(Icons.tune_rounded), onPressed: _openSettings),
              ],
            ),
            const SizedBox(height: 16),
            if (_clipLink != null) ...[
              _ClipboardCard(
                link: _clipLink!,
                onOpen: _openClipLink,
                onDismiss: () => setState(() => _clipLink = null),
              ),
              const SizedBox(height: 16),
            ],
            _Hero(controller: _url, onPaste: _paste, onSubmit: _analyze),
            if (_recent.isNotEmpty) ...[
              SectionTitle(
                'Recent downloads',
                trailing: TextButton(onPressed: _openDownloads, child: const Text('See all')),
                padding: const EdgeInsets.fromLTRB(4, 20, 0, 4),
              ),
              SizedBox(
                height: 132,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: _recent.length,
                  separatorBuilder: (_, _) => const SizedBox(width: 12),
                  itemBuilder: (_, i) => _RecentItem(job: _recent[i]),
                ),
              ),
            ],
            const SectionTitle('Works with'),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final s in [
                  'YouTube',
                  'YouTube Music',
                  'Instagram',
                  'Threads',
                  'TikTok',
                  'X',
                  'Facebook',
                  'SoundCloud',
                  'Spotify',
                  'Deezer',
                  'Any web page',
                ])
                  Chip(label: Text(s), visualDensity: VisualDensity.compact),
              ],
            ),
            const SectionTitle('Tips'),
            const _Tip(
              icon: Icons.share_rounded,
              title: 'Share from any app',
              text: 'Tap Share on a video and pick "Download with DownVid". A small sheet opens right there.',
            ),
            const _Tip(
              icon: Icons.grid_view_rounded,
              title: 'Quick Settings tile',
              text: 'Add "Paste & download" to your quick settings to grab a copied link from anywhere.',
            ),
            const _Tip(
              icon: Icons.music_note_rounded,
              title: 'Audio only',
              text: 'Switch to Audio only to save M4A or MP3 with cover art, title, artist and album.',
            ),
            const SizedBox(height: 20),
            Text(
              'Use only with your own, public or authorized content.',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.outline),
            ),
          ],
        ),
      ),
    );
  }
}

/// Gradient card with the link field and the main action.
class _Hero extends StatelessWidget {
  const _Hero({required this.controller, required this.onPaste, required this.onSubmit});

  final TextEditingController controller;
  final VoidCallback onPaste;
  final VoidCallback onSubmit;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 22, 20, 20),
      decoration: BoxDecoration(
        gradient: AppTheme.brandGradient,
        borderRadius: BorderRadius.circular(28),
        boxShadow: [
          BoxShadow(
            color: AppTheme.violet.withValues(alpha: 0.18),
            blurRadius: 20,
            spreadRadius: -4,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            'Save videos, music\nand photos',
            style: theme.textTheme.headlineSmall?.copyWith(color: Colors.white, height: 1.15),
          ),
          const SizedBox(height: 6),
          Text(
            'Paste a link or share it from any app. Videos are saved as MP4.',
            style: theme.textTheme.bodyMedium?.copyWith(color: Colors.white.withValues(alpha: 0.88)),
          ),
          const SizedBox(height: 18),
          TextField(
            controller: controller,
            keyboardType: TextInputType.url,
            textInputAction: TextInputAction.go,
            onSubmitted: (_) => onSubmit(),
            style: const TextStyle(color: Colors.black87),
            decoration: InputDecoration(
              hintText: 'Paste a link',
              hintStyle: const TextStyle(color: Colors.black45),
              fillColor: Colors.white,
              prefixIcon: const Icon(Icons.link_rounded, color: Colors.black45),
              suffixIcon: IconButton(
                tooltip: 'Paste',
                icon: const Icon(Icons.content_paste_rounded, color: AppTheme.violet),
                onPressed: onPaste,
              ),
              focusedBorder: const OutlineInputBorder(
                borderRadius: BorderRadius.all(Radius.circular(16)),
                borderSide: BorderSide.none,
              ),
            ),
          ),
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: onSubmit,
            style: FilledButton.styleFrom(
              backgroundColor: Colors.black.withValues(alpha: 0.22),
              foregroundColor: Colors.white,
            ),
            icon: const Icon(Icons.search_rounded),
            label: const Text('Find media'),
          ),
        ],
      ),
    );
  }
}

class _ClipboardCard extends StatelessWidget {
  const _ClipboardCard({required this.link, required this.onOpen, required this.onDismiss});

  final SharedLink link;
  final VoidCallback onOpen;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      color: theme.colorScheme.secondaryContainer,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 8, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                IconBadge(Icons.content_paste_go_rounded, size: 36, color: theme.colorScheme.onSecondaryContainer),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Link in your clipboard', style: theme.textTheme.titleSmall),
                      Text(
                        link.uri.toString(),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSecondaryContainer),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(onPressed: onDismiss, child: const Text('Dismiss')),
                const SizedBox(width: 4),
                TextButton.icon(
                  onPressed: onOpen,
                  icon: const Icon(Icons.arrow_forward_rounded),
                  label: const Text('Open'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _RecentItem extends StatelessWidget {
  const _RecentItem({required this.job});
  final Map<String, dynamic> job;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final saved = job['saved'] as Map?;
    final mime = saved?['mime'] as String? ?? 'video/mp4';
    final audio = job['kind'] == 'audio' || mime.startsWith('audio/');
    final icon = audio
        ? Icons.music_note_rounded
        : mime.startsWith('image/')
        ? Icons.image_rounded
        : Icons.movie_rounded;
    final placeholder = Container(
      color: theme.colorScheme.surfaceContainerHighest,
      child: Icon(icon, color: theme.colorScheme.outline),
    );
    final thumb = job['thumbnail'] as String?;
    return SizedBox(
      width: 140,
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: saved == null ? null : () => MediaChannel.openVideo(saved['uri'] as String, mime),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(16),
              child: SizedBox(
                width: 140,
                height: 84,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    thumb == null
                        ? placeholder
                        : Image.network(thumb, fit: BoxFit.cover, errorBuilder: (_, _, _) => placeholder),
                    Positioned(
                      right: 6,
                      bottom: 6,
                      child: Container(
                        padding: const EdgeInsets.all(3),
                        decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(8)),
                        child: Icon(icon, size: 14, color: Colors.white),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 6),
            Text(
              job['title'] as String? ?? 'Download',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodySmall?.copyWith(fontWeight: FontWeight.w500),
            ),
          ],
        ),
      ),
    );
  }
}

class _Tip extends StatelessWidget {
  const _Tip({required this.icon, required this.title, required this.text});

  final IconData icon;
  final String title;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              IconBadge(icon),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: theme.textTheme.titleSmall),
                    const SizedBox(height: 2),
                    Text(text, style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
