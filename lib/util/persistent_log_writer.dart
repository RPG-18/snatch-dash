import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';
import 'package:talker/talker.dart';

/// Persists every [Talker] entry to disk so a session survives app restarts
/// and can be pulled off the device without the app running (phone screen is
/// usually off mid-ride, so the in-app log screen isn't reachable then).
///
/// Two rotating files capped at 1 MB each (2 MB total): once the active file
/// would exceed 1 MB, it becomes the backup and a fresh one starts. Writes
/// are chained through [_queue] so concurrent log calls never interleave.
class PersistentLogWriter extends TalkerObserver {
  PersistentLogWriter._(this.currentFile, this.previousFile);

  static const _maxFileBytes = 1 * 1024 * 1024;

  final File currentFile;
  final File previousFile;

  Future<void> _queue = Future.value();

  static Future<PersistentLogWriter> create() async {
    final dir = await getApplicationSupportDirectory();
    return PersistentLogWriter._(
      File('${dir.path}/app_log.txt'),
      File('${dir.path}/app_log.old.txt'),
    );
  }

  @override
  void onLog(TalkerData log) => _enqueue(log);

  @override
  void onError(TalkerError err) => _enqueue(err);

  @override
  void onException(TalkerException err) => _enqueue(err);

  void _enqueue(TalkerData data) {
    final line = '${data.generateTextMessage()}\n';
    _queue = _queue.then((_) => _append(line));
  }

  Future<void> _append(String line) async {
    try {
      final bytes = utf8.encode(line);
      final file = await currentFile.create(recursive: true);
      final size = await file.length();
      if (size + bytes.length > _maxFileBytes) {
        await file.copy(previousFile.path);
        await file.writeAsBytes(bytes);
      } else {
        await file.writeAsBytes(bytes, mode: FileMode.append);
      }
    } catch (_) {
      // Disk full/unwritable — never let logging itself crash the app.
    }
  }
}
