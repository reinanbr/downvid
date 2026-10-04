import 'package:flutter/foundation.dart';

/// App log line; shows in logcat under the "flutter" tag with a [DV] prefix.
/// Filter on the device with: adb logcat -s flutter DownVid DownVid-Go
void dvLog(String message) => debugPrint('[DV] $message');
