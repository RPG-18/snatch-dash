import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';

/// Downloads a release APK into `cache/updates/` (declared in
/// `res/xml/file_paths.xml`, shared with `ApkInstaller`'s FileProvider) so it
/// can be handed to the system installer afterwards. Old files in that
/// directory are cleared first — release APKs run tens of MB and nothing
/// else needs them once installed, unlike `exports/`'s user-facing CSVs.
///
/// [onProgress] receives `(receivedBytes, totalBytes)`; `totalBytes` is null
/// if the server didn't send a `Content-Length`.
Future<File> downloadApk(
  String url,
  String fileName, {
  void Function(int received, int? total)? onProgress,
}) async {
  final dir = Directory('${(await getTemporaryDirectory()).path}/updates');
  await dir.create(recursive: true);

  final file = File('${dir.path}/$fileName');
  // Sweep the old APKs one by one instead of deleting the directory wholesale: a
  // recursive delete also unlinked the file *this* call is about to stream into
  // if one was already there, and on Linux a writer's descriptor stays valid on
  // an unlinked inode — the download then runs to 100% while `file.path`, which
  // is what gets handed to the package installer, points at nothing.
  //
  // Listed to completion before deleting anything: mutating a directory while
  // its own readdir is still open is unspecified and can skip entries.
  //
  // This is not concurrency protection — a second download of a *different*
  // release would still sweep this one's file away. `AppUpdateController` is what
  // keeps two runs from overlapping at all.
  for (final entry in await dir.list().toList()) {
    if (entry.path == file.path) continue;
    await entry.delete(recursive: true);
  }
  final request = http.Request('GET', Uri.parse(url));
  final response = await http.Client().send(request);
  if (response.statusCode != 200) {
    throw HttpException('Download failed: HTTP ${response.statusCode}', uri: Uri.parse(url));
  }

  final sink = file.openWrite();
  var received = 0;
  try {
    await for (final chunk in response.stream) {
      sink.add(chunk);
      received += chunk.length;
      onProgress?.call(received, response.contentLength);
    }
  } finally {
    await sink.close();
  }
  return file;
}
