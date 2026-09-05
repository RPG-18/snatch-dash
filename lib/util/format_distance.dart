import '../l10n/app_localizations.dart';

/// Distance in the localised unit — kilometres with one decimal from 1 km up,
/// whole metres below that.
///
/// Shared by Home, Route and Route preview: the same saved place is shown on
/// more than one of them, and three private copies of this rule drifted apart
/// the moment one of them was touched.
String formatDistance(AppLocalizations l10n, double meters) {
  return meters >= 1000
      ? l10n.unitKm((meters / 1000).toStringAsFixed(1))
      : l10n.unitM(meters.round().toString());
}
