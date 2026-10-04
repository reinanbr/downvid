import 'package:flutter/material.dart';

import '../app_theme.dart';
import '../core/log.dart';
import '../settings/disclaimer.dart';
import '../download/extract_controller.dart';
import 'share_channel.dart';
import 'share_sheet.dart';

/// Root widget of the `shareMain` entrypoint, hosted by the translucent
/// ShareActivity. Only the bottom sheet is visible; dismissing it closes the
/// activity and returns to the app the link was shared from.
class ShareApp extends StatefulWidget {
  const ShareApp({super.key});

  @override
  State<ShareApp> createState() => _ShareAppState();
}

class _ShareAppState extends State<ShareApp> {
  final _navigatorKey = GlobalKey<NavigatorState>();
  bool _sheetOpen = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final ctx = _navigatorKey.currentContext;
      if (ctx != null) await showDisclaimer(ctx); // first use only
      _show(await ShareChannel.instance.initialText());
    });
    ShareChannel.instance.newTexts.listen((text) {
      if (_sheetOpen) {
        // Closing the current sheet moves a running download to the
        // background (notification); it is never canceled.
        if (ExtractController.anyBusy) dvLog('share: new link; current download continues in background');
        ExtractController.hideAll();
        _navigatorKey.currentState?.pop(_replace);
      }
      _show(text);
    });
  }

  static const _replace = Object();

  Future<void> _show(String? text) async {
    final ctx = _navigatorKey.currentContext;
    if (ctx == null) return;
    _sheetOpen = true;
    dvLog('share: sheet for ${text?.replaceAll('\n', ' ')}');
    final result = await showModalBottomSheet<Object>(
      context: ctx,
      showDragHandle: true,
      isScrollControlled: true,
      // Drag-to-dismiss bypasses PopScope; closing goes through back/scrim.
      enableDrag: false,
      builder: (_) => ShareSheet(key: UniqueKey(), rawText: text),
    );
    _sheetOpen = false;
    // Replaced by a newer share: keep the activity alive.
    if (!identical(result, _replace)) {
      ExtractController.hideAll();
      await ShareChannel.instance.close();
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: _navigatorKey,
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light,
      darkTheme: AppTheme.dark,
      color: Colors.transparent,
      home: const Scaffold(backgroundColor: Colors.transparent),
    );
  }
}
