import 'dart:async';

import 'package:flutter/services.dart';

/// Bridge to ShareActivity.kt (channel "downvid/share").
class ShareChannel {
  ShareChannel._() {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onSharedText') {
        _incoming.add(call.arguments as String?);
      }
    });
  }
  static final ShareChannel instance = ShareChannel._();

  static const _channel = MethodChannel('downvid/share');
  final _incoming = StreamController<String?>.broadcast();

  /// Text from the intent that launched the activity.
  Future<String?> initialText() => _channel.invokeMethod<String>('getSharedText');

  /// Texts shared while the sheet was already open (onNewIntent).
  Stream<String?> get newTexts => _incoming.stream;

  /// Closes the translucent activity, returning to the app that shared.
  Future<void> close() => _channel.invokeMethod('close');
}
