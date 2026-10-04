import 'package:flutter/services.dart';

import 'log.dart';

/// User settings, stored in Android SharedPreferences ("downvid/settings")
/// so every engine (app, share sheet) and the services see the same values.
/// Loaded once before runApp.
class AppSettings {
  AppSettings._();
  static final AppSettings instance = AppSettings._();

  static const _ch = MethodChannel('downvid/settings');

  /// Default video quality: 0 = best available, else max height (720...).
  int videoMaxHeight = 0;

  /// Default audio: m4a_copy | mp3_v0 | mp3_320.
  String audioFormat = 'm4a_copy';

  /// Music links (YouTube Music, SoundCloud, Spotify...) open in "audio only".
  bool musicAudioMode = true;

  /// Simultaneous downloads (1–4).
  int maxConcurrent = 2;

  /// Offer the copied link when the app comes to the foreground.
  bool clipboardCheck = true;

  /// The first-use notice was acknowledged.
  bool disclaimerAccepted = false;

  Future<void> load() async {
    try {
      final m = Map<String, dynamic>.from(await _ch.invokeMethod<Map>('getAll') ?? const {});
      videoMaxHeight = (m['videoMaxHeight'] as num?)?.toInt() ?? videoMaxHeight;
      audioFormat = m['audioFormat'] as String? ?? audioFormat;
      musicAudioMode = m['musicAudioMode'] as bool? ?? musicAudioMode;
      maxConcurrent = ((m['maxConcurrent'] as num?)?.toInt() ?? maxConcurrent).clamp(1, 4);
      clipboardCheck = m['clipboardCheck'] as bool? ?? clipboardCheck;
      disclaimerAccepted = m['disclaimerAccepted'] as bool? ?? disclaimerAccepted;
    } catch (e) {
      dvLog('settings: load failed: $e');
    }
  }

  Future<void> set(String key, Object value) async {
    switch (key) {
      case 'videoMaxHeight':
        videoMaxHeight = value as int;
      case 'audioFormat':
        audioFormat = value as String;
      case 'musicAudioMode':
        musicAudioMode = value as bool;
      case 'maxConcurrent':
        maxConcurrent = value as int;
      case 'clipboardCheck':
        clipboardCheck = value as bool;
      case 'disclaimerAccepted':
        disclaimerAccepted = value as bool;
    }
    dvLog('settings: $key = $value');
    await _ch.invokeMethod('set', {'key': key, 'value': value});
  }
}
