import 'package:flutter/material.dart';

import '../brand.dart';
import '../core/settings.dart';

const disclaimerText =
    'Use DownVid only with content that is your own, public, or that you are authorized to download, '
    'respecting each platform\'s terms of service and copyright. You are responsible '
    'for how you use the downloaded files.';

/// First-use notice (once, in the app or the first share). [force] shows it
/// again from Settings.
Future<void> showDisclaimer(BuildContext context, {bool force = false}) async {
  final s = AppSettings.instance;
  if (s.disclaimerAccepted && !force) return;
  await showDialog<void>(
    context: context,
    barrierDismissible: false,
    builder: (ctx) => AlertDialog(
      icon: const Center(child: DownVidLogo(size: 56)),
      title: const Text('Before you start'),
      content: const Text(disclaimerText),
      actions: [
        FilledButton(
          style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
          onPressed: () {
            if (!s.disclaimerAccepted) s.set('disclaimerAccepted', true);
            Navigator.of(ctx).pop();
          },
          child: const Text('I understand'),
        ),
      ],
    ),
  );
}
