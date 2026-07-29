import 'dart:io';

import 'package:opendash_dash_engine/opendash_dash_engine.dart';
import 'package:talker_flutter/talker_flutter.dart';

import 'persistent_log_writer.dart';

/// Single app-wide [Talker] instance: general debug logging plus, via
/// [TalkerRiverpodObserver] in `main.dart`, provider add/update/dispose
/// events. Viewable in-app on the "Logs" screen (`/more/logs`) — useful
/// since the phone screen is often off while streaming to the dash and
/// logcat isn't reachable mid-ride.
final talker = TalkerFlutter.init();

PersistentLogWriter? _persistentLog;

/// Wires up on-disk persistence (see [PersistentLogWriter]) so the log
/// survives app restarts, capped at 2 MB total. Call once from `main()`,
/// before anything else logs, so nothing is missed.
Future<void> attachPersistentLog() async {
  final writer = await PersistentLogWriter.create();
  _persistentLog = writer;
  talker.configure(observer: writer);
}

/// The on-disk log files (current + rotated-out backup, oldest first),
/// only the ones that actually exist. Used by the "share log file" action
/// on the Logs screen — unlike Talker's own in-memory export, this includes
/// entries from before the current app launch.
Future<List<File>> persistedLogFiles() async {
  final writer = _persistentLog;
  if (writer == null) return const [];
  final files = [writer.previousFile, writer.currentFile];
  final existing = await Future.wait(files.map((f) => f.exists()));
  return [for (var i = 0; i < files.length; i++) if (existing[i]) files[i]];
}

/// Pipes the native dash-protocol log (`DashSession`/`DashSocket`/…, see
/// `opendash_dash_engine`'s `DebugLog`) into [talker] so a protocol capture
/// shows up in the same in-app log as everything else, not only in logcat.
/// Call once from `main()`. `D`-level lines are raw per-packet TX/RX hex
/// dumps — routed to `verbose` so they're filterable out in the log screen
/// without losing them.
void attachNativeLogBridge() {
  DashEngine.instance.logStream.listen((entry) {
    final tag = entry['tag'] as String? ?? 'Native';
    final message = '[$tag] ${entry['message']}';
    switch (entry['level']) {
      case 'E':
        talker.error(message);
      case 'W':
        talker.warning(message);
      case 'D':
        talker.verbose(message);
      default:
        talker.info(message);
    }
  });
}
