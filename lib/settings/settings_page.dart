import 'package:flutter/material.dart';

import '../core/native/go_core.dart';
import '../core/native/platform_bridge.dart';
import '../core/settings.dart';
import 'disclaimer.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final s = AppSettings.instance;
  String? _ytdlpVersion;
  bool _updating = false;
  bool _igLoggedIn = false;

  @override
  void initState() {
    super.initState();
    YtdlpChannel.version().then((v) => mounted ? setState(() => _ytdlpVersion = v) : null, onError: (_) {});
    InstagramChannel.isLoggedIn().then((v) => mounted ? setState(() => _igLoggedIn = v) : null);
  }

  Future<void> _set(String key, Object value) async {
    await s.set(key, value);
    if (key == 'maxConcurrent') GoCore.instance.setMaxConcurrent(value as int);
    if (mounted) setState(() {});
  }

  Future<void> _updateYtdlp() async {
    setState(() => _updating = true);
    String msg;
    try {
      final v = await YtdlpChannel.update();
      msg = 'yt-dlp updated: $v';
      _ytdlpVersion = v;
    } catch (e) {
      msg = 'Could not update: $e';
    }
    if (!mounted) return;
    setState(() => _updating = false);
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    Widget section(String t) => Padding(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 4),
      child: Text(t, style: theme.textTheme.titleSmall?.copyWith(color: theme.colorScheme.primary)),
    );

    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        children: [
          section('Video'),
          ListTile(
            title: const Text('Default quality'),
            subtitle: const Text('Pre-selected in the download sheet; you can still change it.'),
            trailing: DropdownButton<int>(
              value: s.videoMaxHeight,
              onChanged: (v) => _set('videoMaxHeight', v!),
              items: const [
                DropdownMenuItem(value: 0, child: Text('Best')),
                DropdownMenuItem(value: 2160, child: Text('Up to 2160p')),
                DropdownMenuItem(value: 1440, child: Text('Up to 1440p')),
                DropdownMenuItem(value: 1080, child: Text('Up to 1080p')),
                DropdownMenuItem(value: 720, child: Text('Up to 720p')),
                DropdownMenuItem(value: 480, child: Text('Up to 480p')),
              ],
            ),
          ),
          section('Audio'),
          RadioGroup<String>(
            groupValue: s.audioFormat,
            onChanged: (v) => _set('audioFormat', v!),
            child: const Column(
              children: [
                RadioListTile(
                  value: 'm4a_copy',
                  title: Text('Original M4A'),
                  subtitle: Text('No conversion: faster and lossless'),
                ),
                RadioListTile(value: 'mp3_v0', title: Text('MP3 V0'), subtitle: Text('~245 kbps, smaller')),
                RadioListTile(value: 'mp3_320', title: Text('MP3 320 kbps'), subtitle: Text('Maximum compatibility')),
              ],
            ),
          ),
          SwitchListTile(
            title: const Text('Music links open in "Audio only"'),
            subtitle: const Text('YouTube Music, SoundCloud, Spotify, Deezer, Apple Music'),
            value: s.musicAudioMode,
            onChanged: (v) => _set('musicAudioMode', v),
          ),
          section('Downloads'),
          ListTile(
            title: const Text('Simultaneous downloads'),
            subtitle: const Text('The others wait in the queue'),
            trailing: SegmentedButton<int>(
              showSelectedIcon: false,
              segments: const [
                ButtonSegment(value: 1, label: Text('1')),
                ButtonSegment(value: 2, label: Text('2')),
                ButtonSegment(value: 3, label: Text('3')),
                ButtonSegment(value: 4, label: Text('4')),
              ],
              selected: {s.maxConcurrent},
              onSelectionChanged: (v) => _set('maxConcurrent', v.first),
            ),
          ),
          SwitchListTile(
            title: const Text('Offer the copied link when opening the app'),
            value: s.clipboardCheck,
            onChanged: (v) => _set('clipboardCheck', v),
          ),
          const ListTile(
            title: Text('Folders'),
            subtitle: Text('Videos in Movies/DownVid, audio in Music/DownVid, photos in Pictures/DownVid'),
          ),
          section('Extractor (yt-dlp)'),
          ListTile(
            title: const Text('Version'),
            subtitle: Text('${_ytdlpVersion ?? 'loading…'} · updates itself every 3 days'),
            trailing: _updating
                ? const SizedBox.square(dimension: 24, child: CircularProgressIndicator(strokeWidth: 2))
                : TextButton(onPressed: _updateYtdlp, child: const Text('Update now')),
          ),
          if (_igLoggedIn) ...[
            section('Instagram'),
            ListTile(
              title: const Text('Signed in'),
              subtitle: const Text('Session stored on this device only. No password is kept.'),
              trailing: TextButton(
                onPressed: () async {
                  await InstagramChannel.logout();
                  setState(() => _igLoggedIn = false);
                },
                child: const Text('Sign out'),
              ),
            ),
          ],
          section('About'),
          ListTile(
            title: const Text('Usage notice'),
            subtitle: const Text(disclaimerText, maxLines: 2, overflow: TextOverflow.ellipsis),
            onTap: () => showDisclaimer(context, force: true),
          ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }
}
