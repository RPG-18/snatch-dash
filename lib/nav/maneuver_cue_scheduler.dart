import 'route.dart';

enum ManeuverCue { advance300, advance150, now, arrived }

/// Selects the next voice cue from navigation distances without performing I/O.
class ManeuverCueScheduler {
  static const _arrivalDistanceM = 30.0;
  static const _nowDistanceM = 60.0;
  static const _advance150DistanceM = 150.0;
  static const _advance300DistanceM = 300.0;

  double? _maneuverKey;
  bool _advance300Done = false;
  bool _advance150Done = false;
  bool _nowDone = false;
  bool _arrived = false;

  ManeuverCue? next({
    required Maneuver? maneuver,
    required double distanceM,
    required double remainingM,
  }) {
    if (remainingM >= 0 && remainingM <= _arrivalDistanceM) {
      if (_arrived) return null;
      _arrived = true;
      return ManeuverCue.arrived;
    }
    if (remainingM > _arrivalDistanceM) _arrived = false;

    if (maneuver == null) return null;
    if (maneuver.cumulativeMeters != _maneuverKey) {
      _maneuverKey = maneuver.cumulativeMeters;
      _advance300Done = false;
      _advance150Done = false;
      _nowDone = false;
    }
    if (distanceM < 0) return null;

    if (distanceM <= _nowDistanceM && !_nowDone) {
      _advance300Done = true;
      _advance150Done = true;
      _nowDone = true;
      return ManeuverCue.now;
    }
    if (distanceM <= _advance150DistanceM && !_advance150Done) {
      _advance300Done = true;
      _advance150Done = true;
      return ManeuverCue.advance150;
    }
    if (distanceM <= _advance300DistanceM && !_advance300Done) {
      _advance300Done = true;
      return ManeuverCue.advance300;
    }
    return null;
  }

  void reset() {
    _maneuverKey = null;
    _advance300Done = false;
    _advance150Done = false;
    _nowDone = false;
    _arrived = false;
  }
}
