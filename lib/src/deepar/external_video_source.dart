import 'dart:typed_data';

import 'package:flutter/services.dart';

/// Native WebRTC external video track used to publish DeepAR frames to LiveKit.
///
/// Creates a local `MediaStream` + video track via `flutter_webrtc`'s
/// `PeerConnectionFactory`, then accepts raw RGBA/BGRA frames.
class IsmLiveExternalVideoSource {
  IsmLiveExternalVideoSource._();

  static const _channel = MethodChannel('appscrip_live_stream_component');

  /// Creates an empty external video track.
  ///
  /// Returns a map shaped like `getUserMedia`:
  /// `{ streamId, audioTracks: [], videoTracks: [{ id, kind, ... }] }`.
  static Future<Map<dynamic, dynamic>> createTrack({
    required int width,
    required int height,
    int fps = 30,
  }) async {
    // Release AOT decodes method-channel maps as Map<Object?, Object?>.
    // Casting that straight to Map<dynamic, dynamic> throws on some SDKs
    // and the caller then tears DeepAR down. Copy into a fresh map.
    final raw = await _channel.invokeMethod<Object?>(
      'createExternalVideoTrack',
      {
        'width': width,
        'height': height,
        'fps': fps,
      },
    );
    if (raw is! Map) {
      throw StateError(
        'createExternalVideoTrack returned ${raw?.runtimeType}',
      );
    }
    return Map<dynamic, dynamic>.from(raw);
  }

  /// Pushes one AR-processed frame into the active external track.
  static Future<void> pushFrame({
    required Uint8List data,
    required int width,
    required int height,
    required String format,
    required int timestampMs,
    int rotation = 0,
    bool mirror = false,
  }) {
    return _channel.invokeMethod<void>(
      'pushExternalVideoFrame',
      {
        'data': data,
        'width': width,
        'height': height,
        'format': format,
        'timestampMs': timestampMs,
        'rotation': rotation,
        'mirror': mirror,
      },
    );
  }

  /// Releases the external track / capturer.
  static Future<void> disposeTrack() {
    return _channel.invokeMethod<void>('disposeExternalVideoTrack');
  }

  /// Logcat line under the FlutterDeepAR tag the host already filters on.
  static Future<void> log(String message) async {
    try {
      await _channel.invokeMethod<void>('logDeepAr', {'message': message});
    } catch (_) {}
  }
}
