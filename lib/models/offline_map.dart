/// One downloadable tile pack — a row of `index.json`'s `regions`.
///
/// [code] is the pack's identifier and its filename without extension. It is
/// **not** an ISO 3166-2 subject code and must never be parsed apart: two of
/// the 80 packs are merged agglomerations (`ru-len-spe`, `ru-mos-mow`), and the
/// set of codes can change between corpus builds. See spec/remote_map_server.md,
/// "Один пак ≠ один субъект".
class MapRegion {
  const MapRegion({
    required this.code,
    required this.path,
    required this.sizeBytes,
    required this.sha256,
  });

  final String code;

  /// Path relative to the server base — never an absolute URL.
  final String path;
  final int sizeBytes;

  /// Content hash; doubles as the version token of this particular pack.
  final String sha256;
}

/// Parsed `index.json`. [generatedAt] is the version token of the corpus as a
/// whole — the cheap "did anything change at all" check.
class MapManifest {
  const MapManifest({
    required this.schemaVersion,
    required this.generatedAt,
    required this.fileCount,
    required this.totalSizeBytes,
    required this.regions,
  });

  /// Version of the *manifest schema*, not of the data.
  final int schemaVersion;
  final String generatedAt;
  final int fileCount;
  final int totalSizeBytes;
  final List<MapRegion> regions;
}

/// A pack that passed sha256 verification and was renamed into place. The
/// registry of these is what the UI trusts; the engine instead enumerates
/// `maps/*.pmtiles` on the disk (see spec/drawing_from_local_tiles.md).
///
/// **The two can disagree, and the code has to expect it.** The rename is atomic
/// and the sqlite write is durable, but they are two operations either side of a
/// method channel, not one: a process killed between them leaves a file with no
/// row (the engine draws a map the interface denies) or, if a row survives a file
/// that went away with the volume, the reverse. Both directions are repaired at
/// startup — see `OfflineMapsController._reconcileRegistryAgainstDisk`.
class InstalledPack {
  const InstalledPack({
    required this.code,
    required this.sha256,
    required this.generatedAt,
    required this.sizeBytes,
    required this.installedAtMs,
  });

  final String code;

  /// Hash of the installed bytes — compared against the manifest to decide
  /// whether an update is available.
  final String sha256;

  /// `generated_at` of the manifest this pack came from.
  final String generatedAt;
  final int sizeBytes;
  final int installedAtMs;
}
