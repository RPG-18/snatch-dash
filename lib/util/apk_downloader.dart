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
  if (await dir.exists()) {
    await dir.delete(recursive: true);
  }
  await dir.create(recursive: true);

  final file = File('${dir.path}/$fileName');
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
