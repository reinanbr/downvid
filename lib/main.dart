import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app_theme.dart';
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
    return MaterialApp(title: 'DownVid', theme: AppTheme.light, darkTheme: AppTheme.dark, home: const HomePage());
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  final _url = TextEditingController();
  String? _coreInfo;

  // Link found in the clipboard when the app comes to the foreground.
  SharedLink? _clipLink;
  int _clipSeenAt = -1; // timestamp of the last clip already examined
  final Set<String> _offered = {}; // never offer the same link twice
  int _activeDownloads = 0;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) => showDisclaimer(context));
    _scheduleClipboardCheck();
    _countDownloads();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => _countDownloads());
    GoCore.instance
        .ping('home')
        .then(
          (r) => setState(() => _coreInfo = 'Go core ${r['version']} (${r['goarch']})'),
          onError: (Object e) => setState(() => _coreInfo = 'Go core unavailable: $e'),
        );
  }

  void _countDownloads() {
    final n = GoCore.instance
        .status()
        .where((j) => const {'queued', 'running', 'waiting', 'done', 'expired'}.contains(j['state']))
        .length;
    if (mounted && n != _activeDownloads) setState(() => _activeDownloads = n);
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
      showDragHandle: true,
      isScrollControlled: true,
      enableDrag: false,
      builder: (_) => ShareSheet(rawText: text),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('DownVid'),
        actions: [
          IconButton(
            tooltip: 'Settings',
            icon: const Icon(Icons.settings_outlined),
            onPressed: () => Navigator.of(
              context,
            ).push(MaterialPageRoute<void>(builder: (_) => const SettingsPage())).then((_) => setState(() {})),
          ),
          IconButton(
            tooltip: 'Downloads',
            onPressed: _openDownloads,
            icon: Badge(
              isLabelVisible: _activeDownloads > 0,
              label: Text('$_activeDownloads'),
              child: const Icon(Icons.download_outlined),
            ),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (_clipLink != null) ...[
            Card(
              color: theme.colorScheme.secondaryContainer,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 8, 4),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const Icon(Icons.content_paste_go),
                        const SizedBox(width: 8),
                        Text('Copied link', style: theme.textTheme.titleSmall),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(_clipLink!.uri.toString(), maxLines: 2, overflow: TextOverflow.ellipsis),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        TextButton(onPressed: () => setState(() => _clipLink = null), child: const Text('Dismiss')),
                        FilledButton.tonal(onPressed: _openClipLink, child: const Text('Find videos')),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
          ],
          Text(
            'Share a link from another app with DownVid, or paste it below. '
            'The app finds every video on the page (MP4 and HLS/m3u8) and saves it as MP4.',
            style: theme.textTheme.bodyLarge,
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _url,
            keyboardType: TextInputType.url,
            textInputAction: TextInputAction.go,
            onSubmitted: (_) => _analyze(),
            decoration: InputDecoration(
              labelText: 'Page or video link',
              border: const OutlineInputBorder(),
              suffixIcon: IconButton(tooltip: 'Paste', icon: const Icon(Icons.content_paste), onPressed: _paste),
            ),
          ),
          const SizedBox(height: 12),
          FilledButton.icon(onPressed: _analyze, icon: const Icon(Icons.search), label: const Text('Find videos')),
          const SizedBox(height: 16),
          const SizedBox(height: 24),
          Text(
            _coreInfo ?? 'Loading Go core…',
            style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.outline),
          ),
          const SizedBox(height: 4),
          Text(
            'Use only with your own, public or authorized content.',
            style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.outline),
          ),
        ],
      ),
    );
  }
}
