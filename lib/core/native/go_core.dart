import 'dart:async';
import 'dart:convert';
import 'dart:ffi' as ffi;
import 'dart:io';
import 'dart:isolate';

import 'package:ffi/ffi.dart';

import 'dvcore_bindings.g.dart';

/// Error returned by the Go core (`{"ok":false,"error":...}`).
class GoCoreException implements Exception {
  GoCoreException(this.message, {this.unsupported = false, this.code = ''});
  final String message;

  /// Machine-readable reason (Instagram: unavailable | login | notfound).
  final String code;

  /// The extractor does not handle this link (try the generic page scan).
  final bool unsupported;
  @override
  String toString() => 'GoCoreException: $message';
}

/// Dart facade over libdvcore.so.
///
/// Synchronous Go calls that may block run in a short-lived isolate
/// ([Isolate.run]) so the UI isolate never waits on native code. Event
/// streams use [ffi.NativeCallable.listener], which is safe to invoke from
/// Go's own threads.
class GoCore {
  GoCore._();
  static final GoCore instance = GoCore._();

  static const _libName = 'libdvcore.so';

  // Each isolate needs its own handle; dlopen is refcounted, so this is cheap.
  static DvCoreBindings _open() {
    if (!Platform.isAndroid) {
      throw UnsupportedError('libdvcore is only bundled for Android');
    }
    return DvCoreBindings(ffi.DynamicLibrary.open(_libName));
  }

  late final DvCoreBindings _b = _open();
  int _nextId = 1;

  /// Converts a Go-owned C string to Dart and releases it with DV_Free.
  static String _take(DvCoreBindings b, ffi.Pointer<ffi.Char> p) {
    if (p == ffi.nullptr) return '';
    try {
      return p.cast<Utf8>().toDartString();
    } finally {
      b.DV_Free(p);
    }
  }

  /// Unwraps the `{"ok":..,"data":..,"error":..}` envelope.
  static Object? _unwrap(String raw) {
    final m = jsonDecode(raw) as Map<String, dynamic>;
    if (m['ok'] == true) return m['data'];
    throw GoCoreException(
      m['error'] as String? ?? 'unknown error',
      unsupported: m['unsupported'] == true,
      code: m['code'] as String? ?? '',
    );
  }

  String version() => _take(_b, _b.DV_Version());

  /// Synchronous FFI round-trip, executed off the UI isolate.
  Future<Map<String, dynamic>> ping(String message) {
    return Isolate.run(() {
      final b = _open();
      final arg = message.toNativeUtf8();
      try {
        final raw = _take(b, b.DV_Ping(arg.cast()));
        return Map<String, dynamic>.from(_unwrap(raw) as Map);
      } finally {
        malloc.free(arg);
      }
    });
  }

  /// Go -> Dart callback round-trip: emits `count` progress events, then a
  /// `done` event, after which the stream closes.
  Stream<Map<String, dynamic>> pingAsync(int count) {
    final id = _nextId++;
    late final ffi.NativeCallable<ffi.Void Function(ffi.Int64, ffi.Pointer<ffi.Char>)> cb;
    final controller = StreamController<Map<String, dynamic>>(onCancel: () => cb.close());

    cb = ffi.NativeCallable<ffi.Void Function(ffi.Int64, ffi.Pointer<ffi.Char>)>.listener((
      int evId,
      ffi.Pointer<ffi.Char> json,
    ) {
      final ev = jsonDecode(_take(_b, json)) as Map<String, dynamic>;
      if (evId != id || controller.isClosed) return;
      controller.add(ev);
      if (ev['type'] == 'done') {
        controller.close();
        cb.close(); // idempotent; also called from onCancel
      }
    });

    _b.DV_PingAsync(id, count, cb.nativeFunction);
    return controller.stream;
  }

  /// Scans a page / resolves candidate URLs (see go/include/dvcore.h).
  /// Network-bound, so it runs in a separate isolate.
  Future<Map<String, dynamic>> probe(Map<String, dynamic> request) {
    final reqJson = jsonEncode(request);
    return Isolate.run(() {
      final b = _open();
      final arg = reqJson.toNativeUtf8();
      try {
        final raw = _take(b, b.DV_Probe(arg.cast()));
        return Map<String, dynamic>.from(_unwrap(raw) as Map);
      } finally {
        malloc.free(arg);
      }
    });
  }

  /// Converts `yt-dlp -J` output (+ stderr/exit code) into options, or
  /// throws a [GoCoreException] with a friendly message.
  Future<Map<String, dynamic>> ytdlpOptions(Map<String, dynamic> request) {
    final reqJson = jsonEncode(request);
    return Isolate.run(() {
      final b = _open();
      final arg = reqJson.toNativeUtf8();
      try {
        final raw = _take(b, b.DV_Ytdlp(arg.cast()));
        return Map<String, dynamic>.from(_unwrap(raw) as Map);
      } finally {
        malloc.free(arg);
      }
    });
  }

  /// Song metadata (Deezer + iTunes): {basic, override?} -> meta.
  Future<Map<String, dynamic>> musicMeta(Map<String, dynamic> request) => _isolateCall('meta', request);

  /// Spotify/Deezer/Apple Music link -> {meta, searchUrl}.
  Future<Map<String, dynamic>> musicLink(String url) => _isolateCall('link', {'url': url});

  /// Ranks a YouTube Music search -> {candidates}.
  Future<Map<String, dynamic>> musicPick(Map<String, dynamic> request) => _isolateCall('pick', request);

  /// Instagram post/reel URL -> query parameters (ErrNotPost for others).
  Future<Map<String, dynamic>> instaQuery(String url) => _isolateCall('instaQuery', {'url': url});

  /// Raw response of the Instagram query -> {title, thumbnail, options, audioOptions, music}.
  Future<Map<String, dynamic>> instaParse(String body, {String? storyPk}) =>
      _isolateCall('instaParse', {'body': body, 'storyPk': ?storyPk});

  static Future<Map<String, dynamic>> _isolateCall(String fn, Map<String, dynamic> request) {
    final reqJson = jsonEncode(request);
    return Isolate.run(() {
      final b = _open();
      final arg = reqJson.toNativeUtf8().cast<ffi.Char>();
      try {
        final raw = _take(b, switch (fn) {
          'meta' => b.DV_MusicMeta(arg),
          'link' => b.DV_MusicLink(arg),
          'instaQuery' => b.DV_InstaQuery(arg),
          'instaParse' => b.DV_InstaParse(arg),
          _ => b.DV_MusicPick(arg),
        });
        return Map<String, dynamic>.from(_unwrap(raw) as Map);
      } finally {
        malloc.free(arg);
      }
    });
  }

  /// Starts a download in the Go core. Events: progress, then exactly one of
  /// done / error / canceled, after which the stream closes. The download
  /// survives this isolate: [DownloadHandle.detach] stops the events and
  /// leaves the job running (the foreground service keeps tracking it).
  DownloadHandle download(Map<String, dynamic> request) {
    late final ffi.NativeCallable<ffi.Void Function(ffi.Int64, ffi.Pointer<ffi.Char>)> cb;
    final controller = StreamController<Map<String, dynamic>>();

    void finish() {
      if (!controller.isClosed) controller.close();
      cb.close();
    }

    cb = ffi.NativeCallable<ffi.Void Function(ffi.Int64, ffi.Pointer<ffi.Char>)>.listener((
      int _,
      ffi.Pointer<ffi.Char> json,
    ) {
      final ev = jsonDecode(_take(_b, json)) as Map<String, dynamic>;
      if (controller.isClosed) return;
      controller.add(ev);
      if (const {'done', 'error', 'canceled'}.contains(ev['type'])) finish();
    });

    final arg = jsonEncode(request).toNativeUtf8();
    var id = -1;
    try {
      final data = _unwrap(_take(_b, _b.DV_Download(arg.cast(), cb.nativeFunction))) as Map;
      id = (data['id'] as num).toInt();
    } on GoCoreException catch (e) {
      controller.add({'type': 'error', 'message': e.message});
      finish();
    } finally {
      malloc.free(arg);
    }
    return DownloadHandle(id, controller.stream, () {
      if (id < 0) return;
      // After DV_Detach returns Go never calls cb again, so closing is safe.
      _b.DV_Detach(id);
      finish();
    });
  }

  bool cancel(int id) => _b.DV_Cancel(id) == 1;
  bool pause(int id) => _b.DV_Pause(id) == 1;
  bool resume(int id) => _b.DV_Resume(id) == 1;
  void remove(int id) => _b.DV_Remove(id);
  void setMaxConcurrent(int n) => _b.DV_SetMaxConcurrent(n);

  /// Every job of the persistent queue/history, newest first.
  List<Map<String, dynamic>> status() {
    final data = _unwrap(_take(_b, _b.DV_Status())) as List? ?? const [];
    return [for (final j in data) Map<String, dynamic>.from(j as Map)];
  }

  /// New source URLs for an expired job (partial data is reused).
  void replace(int id, Map<String, dynamic> source) {
    final arg = jsonEncode({'id': id, ...source}).toNativeUtf8();
    try {
      _unwrap(_take(_b, _b.DV_Replace(arg.cast())));
    } finally {
      malloc.free(arg);
    }
  }
}

class DownloadHandle {
  DownloadHandle(this.id, this.events, this._detach);
  final int id;
  final Stream<Map<String, dynamic>> events;
  final void Function() _detach;

  /// Stops delivering events here; the download continues in background.
  void detach() => _detach();
}
