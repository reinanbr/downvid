import 'package:flutter/material.dart';

import '../app_theme.dart';
import '../core/settings.dart';
import '../core/url/link_parser.dart';
import '../download/extract_controller.dart';
import '../download/models.dart';
import '../core/native/platform_bridge.dart';

/// Bottom-sheet content for a shared/pasted link: finds the videos on the
/// page, lists them, downloads the chosen one as MP4 and saves it.
class ShareSheet extends StatefulWidget {
  const ShareSheet({super.key, required this.rawText, this.onClose});

  final String? rawText;

  /// Called by the "Close" button; defaults to popping the sheet.
  final VoidCallback? onClose;

  @override
  State<ShareSheet> createState() => _ShareSheetState();
}

class _ShareSheetState extends State<ShareSheet> {
  late final SharedLink? _link = parseSharedText(widget.rawText);
  ExtractController? _ctl;

  @override
  void initState() {
    super.initState();
    final link = _link;
    if (link != null) {
      final isPlatform = link.platform != SourcePlatform.generic;
      _ctl = ExtractController(
        link.uri.toString(),
        platform: isPlatform ? link.platform.label : null,
        isPlatform: isPlatform,
        isMusicService: link.isMusicService,
        preferAudio: link.prefersAudio && AppSettings.instance.musicAudioMode,
        musicContext: link.prefersAudio,
      )..start();
    }
  }

  @override
  void dispose() {
    _ctl?.dispose();
    super.dispose();
  }

  void _close() => widget.onClose != null ? widget.onClose!() : Navigator.of(context).maybePop();

  @override
  Widget build(BuildContext context) {
    final link = _link;
    final ctl = _ctl;
    final body = link == null
        ? const _Message(
            icon: Icons.link_off,
            title: 'No link found',
            text: 'The shared or copied text has no http(s) link.',
          )
        : ListenableBuilder(
            listenable: ctl!,
            builder: (context, _) => _GenericBody(link: link, ctl: ctl, onClose: _close),
          );

    // Closing the sheet during a download hides it: the controller detaches
    // and the foreground service keeps going (see ExtractController.hide).
    return SafeArea(
      top: false,
      child: Padding(padding: const EdgeInsets.fromLTRB(20, 0, 20, 16), child: body),
    );
  }
}

class _GenericBody extends StatelessWidget {
  const _GenericBody({required this.link, required this.ctl, required this.onClose});

  final SharedLink link;
  final ExtractController ctl;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final maxListHeight = MediaQuery.sizeOf(context).height * 0.42;
    final showList =
        ctl.phase == DownloadPhase.idle || ctl.phase == DownloadPhase.failed || ctl.phase == DownloadPhase.canceled;
    final audio = ctl.mode == MediaMode.audio;
    final meta = ctl.musicMeta;

    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (audio && meta != null)
          _MusicHeader(meta: meta)
        else
          _Header(link: link, title: ctl.title, thumbnail: ctl.thumbnail),
        const SizedBox(height: 16),
        if (ctl.hasAudio && ctl.hasVideo && showList) ...[
          SegmentedButton<MediaMode>(
            segments: const [
              ButtonSegment(value: MediaMode.video, icon: Icon(Icons.movie_rounded), label: Text('Video')),
              ButtonSegment(value: MediaMode.audio, icon: Icon(Icons.music_note_rounded), label: Text('Audio only')),
            ],
            selected: {ctl.mode},
            onSelectionChanged: (s) => ctl.setMode(s.first),
          ),
          const SizedBox(height: 12),
        ],
        _ScanStatus(ctl: ctl),
        if (showList && !audio && ctl.isCarousel) ...[
          const SizedBox(height: 8),
          ConstrainedBox(
            constraints: BoxConstraints(maxHeight: maxListHeight),
            child: _CarouselPicker(ctl: ctl),
          ),
        ] else if (showList && ctl.modeOptions.isNotEmpty) ...[
          const SizedBox(height: 8),
          ConstrainedBox(
            constraints: BoxConstraints(maxHeight: maxListHeight),
            child: ListView(
              shrinkWrap: true,
              children: [
                for (final o in ctl.modeOptions)
                  _OptionTile(option: o, selected: identical(o, ctl.selected), onTap: () => ctl.select(o)),
                if (!audio && (ctl.skipped.isNotEmpty || ctl.hiddenPreviews > 0))
                  _SkippedTile(
                    skipped: [
                      ...ctl.skipped,
                      for (final o in ctl.options.where((o) => o.small && !ctl.visibleOptions.contains(o)))
                        Skipped(o.url, 'preview/fragment (${formatBytes(o.size)})'),
                    ],
                  ),
              ],
            ),
          ),
        ] else if (showList && ctl.skipped.isNotEmpty && !ctl.scanning)
          _SkippedTile(skipped: ctl.skipped),
        if ((ctl.needsInstagramLogin || ctl.needsThreadsLogin) && ctl.visibleOptions.isEmpty) ...[
          const SizedBox(height: 8),
          FilledButton.icon(
            onPressed: ctl.needsThreadsLogin ? ctl.loginToThreads : ctl.loginToInstagram,
            icon: const Icon(Icons.login),
            label: Text('Sign in to ${ctl.needsThreadsLogin ? 'Threads' : 'Instagram'} (optional)'),
          ),
          const SizedBox(height: 4),
          Text(
            'Optional, only for private content. You sign in on '
            '${ctl.needsThreadsLogin ? 'Threads' : 'Instagram'}\'s official page, '
            'in this phone\'s own browser engine. Your password is not stored and nothing is sent to any server '
            'or cloud: the session stays on this device and can be removed with "Sign out".',
            textAlign: TextAlign.center,
            style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.outline),
          ),
        ] else if (ctl.ytdlpError != null && !ctl.genericStarted && ctl.visibleOptions.isEmpty) ...[
          const SizedBox(height: 8),
          FilledButton.tonalIcon(
            onPressed: ctl.startGeneric,
            icon: const Icon(Icons.search),
            label: const Text('Find videos on the page'),
          ),
        ],
        const SizedBox(height: 12),
        _DownloadArea(ctl: ctl, onClose: onClose),
        if (ctl.busy)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              'You can hide this: the download continues in the notification bar.',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.outline),
            ),
          ),
      ],
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.link, this.title, this.thumbnail});

  final SharedLink link;
  final String? title;
  final String? thumbnail;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final placeholder = Container(
      color: scheme.surfaceContainerHighest,
      child: Icon(Icons.movie_rounded, color: scheme.outline),
    );
    final source = link.platform == SourcePlatform.generic ? link.uri.host : link.platform.label;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(14),
          child: SizedBox(
            width: 128,
            height: 72,
            child: thumbnail == null
                ? placeholder
                : Image.network(thumbnail!, fit: BoxFit.cover, errorBuilder: (_, _, _) => placeholder),
          ),
        ),
        const SizedBox(width: 14),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title ?? link.uri.host,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.titleSmall,
              ),
              const SizedBox(height: 6),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(color: scheme.primaryContainer, borderRadius: BorderRadius.circular(8)),
                child: Text(
                  source,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.labelSmall?.copyWith(color: scheme.onPrimaryContainer),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// Square cover + "Title" / "Artist · Album · Year" for audio downloads.
class _MusicHeader extends StatelessWidget {
  const _MusicHeader({required this.meta});
  final Map<String, dynamic> meta;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cover = meta['coverUrl'] as String?;
    final square = meta['coverSquare'] == true;
    final sub = [
      meta['artist'],
      meta['album'],
      meta['year'],
    ].whereType<String>().where((s) => s.isNotEmpty).join(' · ');
    final placeholder = Container(
      color: theme.colorScheme.surfaceContainerHighest,
      child: Icon(Icons.album_outlined, color: theme.colorScheme.outline),
    );
    return Row(
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(14),
          child: SizedBox.square(
            dimension: 72,
            child: cover == null
                ? placeholder
                // Video thumbnails are center-cropped, as in the file.
                : Image.network(
                    cover,
                    fit: square ? BoxFit.contain : BoxFit.cover,
                    errorBuilder: (_, _, _) => placeholder,
                  ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                meta['title'] as String? ?? '',
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.titleSmall,
              ),
              if (sub.isNotEmpty)
                Text(sub, maxLines: 2, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodySmall),
              if (meta['track'] != null && meta['track'] != 0)
                Text(
                  'Track ${meta['track']}',
                  style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.outline),
                ),
            ],
          ),
        ),
      ],
    );
  }
}

class _ScanStatus extends StatelessWidget {
  const _ScanStatus({required this.ctl});
  final ExtractController ctl;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final n = ctl.modeOptions.length;
    final String text;
    if (ctl.matchStatus != null) {
      text = ctl.matchStatus!;
    } else if (ctl.ytdlpRunning && ctl.isPlatform) {
      text = 'Extracting from ${ctl.platform}…';
    } else if (ctl.resolvingMeta && ctl.mode == MediaMode.audio) {
      text = 'Fetching cover and song info…';
    } else if (ctl.staticScanning) {
      text = 'Scanning the page…';
    } else if (ctl.sniffing) {
      text =
          'Opening the page to detect the player… '
          '(${ctl.sniffedCount} ${ctl.sniffedCount == 1 ? 'link' : 'links'} found)';
    } else if (ctl.scanning) {
      text = 'Checking the links found…';
    } else if (n == 0 && ctl.ytdlpError != null) {
      text = ctl.ytdlpError!;
    } else if (n == 0) {
      text = ctl.scanError != null
          ? 'Could not open the page: ${ctl.scanError}'
          : 'No MP4/HLS video found on this page.';
    } else if (ctl.mode == MediaMode.audio) {
      text = n == 1 ? '1 audio format' : '$n audio formats';
    } else if (ctl.modeOptions.isNotEmpty && ctl.modeOptions.every((o) => o.isImage)) {
      text = n == 1 ? '1 photo found' : '$n photos found';
    } else {
      text = n == 1 ? '1 video found' : '$n options found';
    }
    final done = !ctl.scanning && !(ctl.resolvingMeta && ctl.mode == MediaMode.audio);
    return Row(
      children: [
        if (!done)
          const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
        else
          Icon(
            n > 0 ? Icons.check_circle_outline : Icons.info_outline,
            size: 18,
            color: n > 0 ? theme.colorScheme.success : theme.colorScheme.outline,
          ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(text, style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
        ),
        if (!done && n > 0) Text('$n', style: theme.textTheme.labelLarge),
      ],
    );
  }
}

class _OptionTile extends StatelessWidget {
  const _OptionTile({required this.option, required this.selected, required this.onTap});

  final MediaOption option;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final o = option;
    final parts = <String>[
      if (o.durationSec > 0) formatDuration(o.durationSec),
      // yt-dlp URLs point at CDN hosts (googlevideo...), meaningless here.
      if (o.source == 'instagram' || o.source == 'threads') o.sourceLabel,
      if (!const {'ytdlp', 'instagram', 'threads'}.contains(o.source)) ...[
        o.sourceLabel,
        Uri.tryParse(o.url)?.host ?? '',
      ],
    ].where((p) => p.isNotEmpty).toList();
    final icon = o.isAudio
        ? Icons.music_note_rounded
        : o.isImage
        ? Icons.image_rounded
        : o.isHls
        ? Icons.stream_rounded
        : Icons.movie_rounded;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Material(
        color: selected ? scheme.primaryContainer : scheme.surfaceContainerHigh,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
          side: BorderSide(color: selected ? scheme.primary : Colors.transparent, width: 1.5),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            child: Row(
              children: [
                Icon(icon, size: 22, color: selected ? scheme.primary : scheme.onSurfaceVariant),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        o.label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.titleSmall?.copyWith(
                          color: selected ? scheme.onPrimaryContainer : scheme.onSurface,
                        ),
                      ),
                      if (parts.isNotEmpty)
                        Text(
                          parts.join(' · '),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
                        ),
                    ],
                  ),
                ),
                if (o.size > 0) ...[
                  const SizedBox(width: 8),
                  Text(
                    '${o.sizeExact ? '' : '~'}${formatBytes(o.size)}',
                    style: theme.textTheme.labelLarge?.copyWith(
                      color: selected ? scheme.onPrimaryContainer : scheme.onSurfaceVariant,
                    ),
                  ),
                ],
                const SizedBox(width: 8),
                Icon(
                  selected ? Icons.check_circle_rounded : Icons.radio_button_unchecked_rounded,
                  size: 22,
                  color: selected ? scheme.primary : scheme.outlineVariant,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Carousel post: one row per photo/video with a checkbox.
class _CarouselPicker extends StatelessWidget {
  const _CarouselPicker({required this.ctl});
  final ExtractController ctl;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final items = ctl.carouselItems;
    final n = ctl.checkedItems.length;
    final all = n == items.length;
    return ListView(
      shrinkWrap: true,
      children: [
        CheckboxListTile(
          contentPadding: EdgeInsets.zero,
          dense: true,
          tristate: true,
          value: all ? true : (n == 0 ? false : null),
          onChanged: (_) => ctl.setAllItems(!all),
          controlAffinity: ListTileControlAffinity.leading,
          title: Text('Select all (${items.length})', style: theme.textTheme.titleSmall),
        ),
        for (final o in items)
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            dense: true,
            value: ctl.checkedItems.contains(o.item),
            onChanged: (_) => ctl.toggleItem(o.item),
            controlAffinity: ListTileControlAffinity.leading,
            secondary: _ItemThumb(url: o.itemThumb, video: !o.isImage),
            title: Text(o.isImage ? 'Photo ${o.item}' : 'Video ${o.item}'),
            subtitle: Text(
              [
                o.label.replaceFirst(RegExp(r'^\d+/\d+ · '), ''),
                if (o.size > 0) '${o.sizeExact ? '' : '~'}${formatBytes(o.size)}',
                if (o.durationSec > 0) formatDuration(o.durationSec),
              ].join(' · '),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
      ],
    );
  }
}

class _ItemThumb extends StatelessWidget {
  const _ItemThumb({required this.url, required this.video});
  final String? url;
  final bool video;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final placeholder = Container(
      color: theme.colorScheme.surfaceContainerHighest,
      child: Icon(video ? Icons.movie_outlined : Icons.image_outlined, color: theme.colorScheme.outline, size: 20),
    );
    return ClipRRect(
      borderRadius: BorderRadius.circular(6),
      child: SizedBox.square(
        dimension: 52,
        child: Stack(
          fit: StackFit.expand,
          children: [
            url == null ? placeholder : Image.network(url!, fit: BoxFit.cover, errorBuilder: (_, _, _) => placeholder),
            if (video)
              const Align(
                alignment: Alignment.bottomRight,
                child: Padding(
                  padding: EdgeInsets.all(2),
                  child: Icon(Icons.play_circle_fill, size: 16, color: Colors.white),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _SkippedTile extends StatelessWidget {
  const _SkippedTile({required this.skipped});
  final List<Skipped> skipped;

  @override
  Widget build(BuildContext context) {
    final small = Theme.of(context).textTheme.bodySmall;
    return ExpansionTile(
      tilePadding: EdgeInsets.zero,
      dense: true,
      title: Text('${skipped.length} ${skipped.length == 1 ? 'skipped link' : 'skipped links'}', style: small),
      children: [
        for (final s in skipped)
          ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: Text(s.url, maxLines: 2, overflow: TextOverflow.ellipsis, style: small),
            subtitle: Text(s.reason, style: small),
          ),
      ],
    );
  }
}

class _DownloadArea extends StatelessWidget {
  const _DownloadArea({required this.ctl, required this.onClose});
  final ExtractController ctl;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    switch (ctl.phase) {
      case DownloadPhase.downloading || DownloadPhase.muxing || DownloadPhase.converting || DownloadPhase.saving:
        final label = switch (ctl.phase) {
          DownloadPhase.muxing => 'Converting to MP4…',
          DownloadPhase.converting =>
            'Converting to ${ctl.selected?.audioFormat?.toUpperCase() ?? 'audio'}… ${ctl.percent?.toStringAsFixed(0) ?? ''}%',
          DownloadPhase.saving => 'Saving to gallery…',
          _ => _progressText(ctl),
        };
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            LinearProgressIndicator(
              value: ctl.phase == DownloadPhase.downloading || ctl.phase == DownloadPhase.converting
                  ? (ctl.percent ?? 0) / 100
                  : null,
            ),
            const SizedBox(height: 6),
            Text(label, style: theme.textTheme.bodySmall),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: ctl.phase == DownloadPhase.downloading ? ctl.cancelDownload : null,
                    icon: const Icon(Icons.close),
                    label: const Text('Cancel'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: FilledButton.tonalIcon(
                    onPressed: () {
                      ctl.hide();
                      onClose();
                    },
                    icon: const Icon(Icons.notifications_outlined),
                    label: const Text('Hide'),
                  ),
                ),
              ],
            ),
          ],
        );
      case DownloadPhase.saved:
        final s = ctl.saved!;
        final many = ctl.itemsTotal > 1;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _Banner(
              icon: Icons.check_circle_rounded,
              color: theme.colorScheme.success,
              text: many
                  ? '${ctl.itemsSaved} of ${ctl.itemsTotal} items saved to the gallery (photos in Pictures/DownVid, videos in Movies/DownVid)'
                  : 'Saved to ${s.location}/${s.displayName} (${formatBytes(ctl.bytes)})',
            ),
            if (ctl.warning != null) ...[
              const SizedBox(height: 6),
              _Banner(icon: Icons.warning_amber_rounded, color: theme.colorScheme.warning, text: ctl.warning!),
            ],
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(onPressed: onClose, child: const Text('Close')),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: FilledButton.icon(
                    onPressed: () => MediaChannel.openVideo(s.uri, s.mime),
                    icon: const Icon(Icons.play_arrow),
                    label: const Text('Open'),
                  ),
                ),
              ],
            ),
          ],
        );
      case DownloadPhase.idle || DownloadPhase.failed || DownloadPhase.canceled:
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (ctl.phase == DownloadPhase.failed)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _Banner(icon: Icons.error_outline, color: theme.colorScheme.error, text: ctl.error ?? 'Failed'),
              ),
            if (ctl.phase == DownloadPhase.canceled)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _Banner(
                  icon: Icons.cancel_outlined,
                  color: theme.colorScheme.outline,
                  text: 'Download canceled',
                ),
              ),
            if (ctl.isCarousel && ctl.mode == MediaMode.video)
              FilledButton.icon(
                onPressed: ctl.checkedItems.isEmpty ? null : ctl.downloadItems,
                icon: const Icon(Icons.download_rounded),
                label: Text(switch (ctl.checkedItems.length) {
                  0 => 'Select items',
                  1 => 'Download 1 item',
                  final n => 'Download $n items',
                }),
              )
            else
              FilledButton.icon(
                onPressed: ctl.selected == null ? null : ctl.download,
                icon: const Icon(Icons.download_rounded),
                label: Text(
                  ctl.phase != DownloadPhase.idle
                      ? 'Try again'
                      : ctl.selected?.isAudio == true
                      ? 'Download ${ctl.selected!.audioFormat!.toUpperCase()}'
                      : ctl.selected?.isImage == true
                      ? 'Download photo'
                      : 'Download MP4',
                ),
              ),
          ],
        );
    }
  }

  static String _progressText(ExtractController c) {
    if (c.itemsTotal > 1) {
      return '${c.itemsSaved + c.itemsFailed} of ${c.itemsTotal} items · ${(c.percent ?? 0).toStringAsFixed(0)}%';
    }
    final parts = <String>[
      if (c.percent != null) '${c.percent!.toStringAsFixed(0)}%',
      c.total > 0
          ? '${formatBytes(c.bytes)} / ${c.totalIsEstimate ? '~' : ''}${formatBytes(c.total)}'
          : formatBytes(c.bytes),
      if (c.speedBps > 0) '${formatBytes(c.speedBps)}/s',
      if (c.segmentsTotal > 0) 'seg ${c.segmentsDone}/${c.segmentsTotal}',
    ];
    return parts.join(' · ');
  }
}

class _Banner extends StatelessWidget {
  const _Banner({required this.icon, required this.color, required this.text});
  final IconData icon;
  final Color color;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(14)),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: color, size: 20),
          const SizedBox(width: 10),
          Expanded(child: Text(text, style: Theme.of(context).textTheme.bodyMedium)),
        ],
      ),
    );
  }
}

class _Message extends StatelessWidget {
  const _Message({required this.icon, required this.title, required this.text});
  final IconData icon;
  final String title;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Icon(icon),
            const SizedBox(width: 8),
            Text(title, style: theme.textTheme.titleMedium),
          ],
        ),
        const SizedBox(height: 8),
        Text(text, style: theme.textTheme.bodyMedium),
      ],
    );
  }
}
