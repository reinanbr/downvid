import 'package:flutter/material.dart';

import '../brand.dart';
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
  bool _threadsLoggedIn = false;
  String? _coreVersion;

  @override
  void initState() {
    super.initState();
    YtdlpChannel.version().then((v) => mounted ? setState(() => _ytdlpVersion = v) : null, onError: (_) {});
    GoCore.instance
        .ping('settings')
        .then(
          (r) => mounted ? setState(() => _coreVersion = '${r['version']}'.replaceFirst(RegExp('^v'), '')) : null,
          onError: (_) {},
        );
    InstagramChannel.isLoggedIn().then((v) => mounted ? setState(() => _igLoggedIn = v) : null);
    InstagramChannel.isLoggedIn(site: 'threads').then((v) => mounted ? setState(() => _threadsLoggedIn = v) : null);
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
    final scheme = theme.colorScheme;
    Widget group(String title, List<Widget> children) => Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionTitle(title, padding: const EdgeInsets.fromLTRB(8, 20, 8, 8)),
        Card(
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Column(children: children),
          ),
        ),
      ],
    );
    Widget leading(IconData icon) => IconBadge(icon, size: 36);

    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 32),
        children: [
          group('Video', [
            ListTile(
              leading: leading(Icons.high_quality_rounded),
              title: const Text('Default quality'),
              trailing: DropdownButton<int>(
                value: s.videoMaxHeight,
                underline: const SizedBox.shrink(),
                borderRadius: BorderRadius.circular(16),
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
          ]),
          group('Audio', [
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
            const Divider(indent: 16, endIndent: 16),
            SwitchListTile(
              secondary: leading(Icons.library_music_rounded),
              title: const Text('Music links open in "Audio only"'),
              subtitle: const Text('YouTube Music, SoundCloud, Spotify, Deezer, Apple Music'),
              value: s.musicAudioMode,
              onChanged: (v) => _set('musicAudioMode', v),
            ),
          ]),
          group('Downloads', [
            ListTile(
              leading: leading(Icons.layers_rounded),
              title: const Text('Simultaneous downloads'),
              subtitle: const Text('The others wait in the queue'),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: SegmentedButton<int>(
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
              secondary: leading(Icons.content_paste_go_rounded),
              title: const Text('Offer copied links'),
              subtitle: const Text('When you open the app with a link in the clipboard'),
              value: s.clipboardCheck,
              onChanged: (v) => _set('clipboardCheck', v),
            ),
            ListTile(
              leading: leading(Icons.folder_rounded),
              title: const Text('Folders'),
              subtitle: const Text('Movies/DownVid · Music/DownVid · Pictures/DownVid'),
            ),
          ]),
          group('Extractor', [
            ListTile(
              leading: leading(Icons.extension_rounded),
              title: const Text('yt-dlp'),
              subtitle: Text('${_ytdlpVersion ?? 'loading…'} · updates itself every 3 days'),
              trailing: _updating
                  ? const Padding(
                      padding: EdgeInsets.all(8),
                      child: SizedBox.square(dimension: 24, child: CircularProgressIndicator(strokeWidth: 2)),
                    )
                  : TextButton(onPressed: _updateYtdlp, child: const Text('Update')),
            ),
          ]),
          if (_igLoggedIn)
            group('Instagram', [
              ListTile(
                leading: leading(Icons.lock_open_rounded),
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
            ]),
          if (_threadsLoggedIn)
            group('Threads', [
              ListTile(
                leading: leading(Icons.lock_open_rounded),
                title: const Text('Signed in'),
                subtitle: const Text('Session stored on this device only. No password is kept.'),
                trailing: TextButton(
                  onPressed: () async {
                    await InstagramChannel.logout(site: 'threads');
                    setState(() => _threadsLoggedIn = false);
                  },
                  child: const Text('Sign out'),
                ),
              ),
            ]),
          group('About', [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
              child: Row(
                children: [
                  const DownVidLogo(size: 52),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('DownVid', style: theme.textTheme.titleMedium),
                        Text(
                          'Version ${_coreVersion ?? '…'} · open source, GPL-3.0',
                          style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            ListTile(
              leading: leading(Icons.verified_user_rounded),
              title: const Text('Usage notice'),
              subtitle: const Text(disclaimerText, maxLines: 2, overflow: TextOverflow.ellipsis),
              onTap: () => showDisclaimer(context, force: true),
            ),
            ListTile(
              leading: leading(Icons.shield_rounded),
              title: const Text('Privacy'),
              subtitle: const Text('No account, analytics or ads. Everything runs on this device.'),
            ),
            ListTile(
              leading: leading(Icons.description_rounded),
              title: const Text('Open-source licenses'),
              onTap: () => showLicensePage(
                context: context,
                applicationName: 'DownVid',
                applicationVersion: _coreVersion,
                applicationIcon: const Padding(padding: EdgeInsets.all(12), child: DownVidLogo(size: 56)),
              ),
            ),
          ]),
        ],
      ),
    );
  }
}
