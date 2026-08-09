import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'opendash_dash_engine_platform_interface.dart';

/// Method-channel + event-channel implementation of [OpendashDashEnginePlatform].
class MethodChannelOpendashDashEngine extends OpendashDashEnginePlatform {
  @visibleForTesting
  final methodChannel = const MethodChannel('opendash_dash_engine');

  @visibleForTesting
  final eventChannel = const EventChannel('opendash_dash_engine/state');

  @visibleForTesting
  final logChannel = const EventChannel('opendash_dash_engine/log');

  Stream<Map<String, dynamic>>? _stateStream;
  Stream<Map<String, dynamic>>? _logStream;

  @override
  Future<String?> getPlatformVersion() async {
    return methodChannel.invokeMethod<String>('getPlatformVersion');
  }

  @override
  Stream<Map<String, dynamic>> get stateStream {
    return _stateStream ??= eventChannel.receiveBroadcastStream().map(
          (event) => Map<String, dynamic>.from(event as Map),
        );
  }

  @override
  Stream<Map<String, dynamic>> get logStream {
    return _logStream ??= logChannel.receiveBroadcastStream().map(
          (event) => Map<String, dynamic>.from(event as Map),
        );
  }

  @override
  Future<void> connect() => methodChannel.invokeMethod('connect');

  @override
  Future<void> disconnect() => methodChannel.invokeMethod('disconnect');

  @override
  Future<void> setDestination({String? name, double? lat, double? lng}) =>
      methodChannel.invokeMethod('setDestination', {
        'name': name,
        'lat': lat,
        'lng': lng,
      });

  @override
  Future<void> clearDestination() => methodChannel.invokeMethod('clearDestination');

  @override
  Future<void> setNavState({
    double? remainingMeters,
    double? nextTurnMeters,
    int maneuver = 0x0B,
    String? etaHHMM,
    bool offRoute = false,
    List<List<double>> points = const [],
    List<int> jamSegments = const [],
  }) =>
      methodChannel.invokeMethod('setNavState', {
        'remainingMeters': remainingMeters,
        'nextTurnMeters': nextTurnMeters,
        'maneuver': maneuver,
        'etaHHMM': etaHHMM,
        'offRoute': offRoute,
        'points': points,
        'jamSegments': jamSegments,
      });

  @override
  Future<void> setFollowMode(bool enabled) =>
      methodChannel.invokeMethod('setFollowMode', {'enabled': enabled});

  @override
  Future<void> panBy(double dx, double dy) =>
      methodChannel.invokeMethod('panBy', {'dx': dx, 'dy': dy});

  @override
  Future<void> zoomIn() => methodChannel.invokeMethod('zoomIn');

  @override
  Future<void> zoomOut() => methodChannel.invokeMethod('zoomOut');

  @override
  Future<void> toggleHeadingUp() => methodChannel.invokeMethod('toggleHeadingUp');

  @override
  Future<void> recenter() => methodChannel.invokeMethod('recenter');

  @override
  Future<void> forgetDash() => methodChannel.invokeMethod('forgetDash');

  @override
  Future<void> setSsid(String ssid) =>
      methodChannel.invokeMethod('setSsid', {'ssid': ssid});

  @override
  Future<void> setWifiPassword(String password) =>
      methodChannel.invokeMethod('setWifiPassword', {'password': password});

  @override
  Future<Map<String, dynamic>> getConfig() async {
    final result = await methodChannel.invokeMethod<Map>('getConfig');
    return Map<String, dynamic>.from(result ?? {});
  }

  @override
  Future<void> updateNowPlaying({String? title, String album = '', String artist = ''}) =>
      methodChannel.invokeMethod('updateNowPlaying', {
        'title': title,
        'album': album,
        'artist': artist,
      });

  @override
  Future<void> updateCall(String? caller) =>
      methodChannel.invokeMethod('updateCall', {'caller': caller});

  @override
  Future<void> setWallpaper({
    String? path,
    String? kind,
    String? fit,
    double biasX = 0,
    double biasY = 0,
  }) =>
      methodChannel.invokeMethod('setWallpaper', {
        'path': path,
        'kind': kind,
        'fit': fit,
        'biasX': biasX,
        'biasY': biasY,
      });

  @override
  Future<void> playChime() => methodChannel.invokeMethod('playChime');

  @override
  Future<bool> answerCall() async =>
      (await methodChannel.invokeMethod<bool>('answerCall')) ?? false;

  @override
  Future<bool> hangupCall() async =>
      (await methodChannel.invokeMethod<bool>('hangupCall')) ?? false;

  @override
  Future<bool> skipNext() async => (await methodChannel.invokeMethod<bool>('skipNext')) ?? false;

  @override
  Future<bool> skipPrevious() async =>
      (await methodChannel.invokeMethod<bool>('skipPrevious')) ?? false;

  @override
  Future<bool> isNotificationAccessGranted() async =>
      (await methodChannel.invokeMethod<bool>('isNotificationAccessGranted')) ?? false;

  @override
  Future<void> openNotificationAccessSettings() =>
      methodChannel.invokeMethod('openNotificationAccessSettings');
}
