import '../l10n/app_localizations.dart';

const _kb = 1024;
const _mb = _kb * 1024;
const _gb = _mb * 1024;

/// Human-readable byte size using the localised unit words.
///
/// Shared by the map-cache card in Settings and the offline-maps screen — the
/// two would otherwise round differently, which reads as a bug when the same
/// pack shows two sizes on two screens.
String formatByteSize(AppLocalizations l10n, int bytes) {
  if (bytes >= _gb) return '${(bytes / _gb).toStringAsFixed(1)} ${l10n.settingsMapCacheUnitGb}';
  if (bytes >= _mb) return '${(bytes / _mb).toStringAsFixed(1)} ${l10n.settingsMapCacheUnitMb}';
  if (bytes >= _kb) return '${(bytes / _kb).toStringAsFixed(0)} ${l10n.settingsMapCacheUnitKb}';
  return '$bytes ${l10n.settingsMapCacheUnitBytes}';
}
