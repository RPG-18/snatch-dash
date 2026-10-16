import 'package:flutter_test/flutter_test.dart';
import 'package:snatch_dash/nav/geo_point.dart';
import 'package:snatch_dash/nav/maneuver_cue_scheduler.dart';
import 'package:snatch_dash/nav/route.dart';

void main() {
  const firstManeuver = Maneuver(
    type: ManeuverType.left,
    instruction: 'Turn left',
    location: GeoPoint(0, 0),
    cumulativeMeters: 1000,
  );
  const secondManeuver = Maneuver(
    type: ManeuverType.right,
    instruction: 'Turn right',
    location: GeoPoint(0, 0),
    cumulativeMeters: 2000,
  );

  ManeuverCue? next(
    ManeuverCueScheduler scheduler,
    double distanceM, {
    Maneuver? maneuver = firstManeuver,
    double remainingM = 5000,
  }) => scheduler.next(
    maneuver: maneuver,
    distanceM: distanceM,
    remainingM: remainingM,
  );

  group('ManeuverCueScheduler', () {
    test('emits exact threshold sequence once', () {
      final scheduler = ManeuverCueScheduler();
      final cues = <ManeuverCue?>[
        for (final distance in [301.0, 300.0, 299.0, 150.0, 149.0, 60.0, 59.0])
          next(scheduler, distance),
      ];

      expect(cues, [
        null,
        ManeuverCue.advance300,
        null,
        ManeuverCue.advance150,
        null,
        ManeuverCue.now,
        null,
      ]);
    });

    test('skips stale cues after crossing multiple thresholds', () {
      final jumpTo150 = ManeuverCueScheduler();
      expect(next(jumpTo150, 320), isNull);
      expect(next(jumpTo150, 140), ManeuverCue.advance150);
      expect(next(jumpTo150, 250), isNull);
      expect(next(jumpTo150, 50), ManeuverCue.now);

      final jumpToNow = ManeuverCueScheduler();
      expect(next(jumpToNow, 250), ManeuverCue.advance300);
      expect(next(jumpToNow, 160), isNull);
      expect(next(jumpToNow, 50), ManeuverCue.now);
      expect(next(jumpToNow, 140), isNull);
      expect(next(jumpToNow, 250), isNull);
    });

    test('new maneuver and reset allow all stages again', () {
      final scheduler = ManeuverCueScheduler();
      expect(next(scheduler, 250), ManeuverCue.advance300);
      expect(next(scheduler, 140), ManeuverCue.advance150);
      expect(next(scheduler, 50), ManeuverCue.now);

      expect(
        next(scheduler, 250, maneuver: secondManeuver),
        ManeuverCue.advance300,
      );
      expect(
        next(scheduler, 140, maneuver: secondManeuver),
        ManeuverCue.advance150,
      );
      expect(next(scheduler, 50, maneuver: secondManeuver), ManeuverCue.now);

      scheduler.reset();
      expect(
        next(scheduler, 250, maneuver: secondManeuver),
        ManeuverCue.advance300,
      );
    });

    test('ignores missing maneuvers and negative distances', () {
      final scheduler = ManeuverCueScheduler();

      expect(next(scheduler, 250, maneuver: null), isNull);
      expect(next(scheduler, 250), ManeuverCue.advance300);
      expect(next(scheduler, -1, maneuver: secondManeuver), isNull);
      expect(
        next(scheduler, 250, maneuver: secondManeuver),
        ManeuverCue.advance300,
      );
    });

    test('arrival has priority, deduplicates, and rearms outside 30 m', () {
      final scheduler = ManeuverCueScheduler();

      expect(next(scheduler, 20, remainingM: 30), ManeuverCue.arrived);
      expect(next(scheduler, 20, remainingM: 29), isNull);
      expect(next(scheduler, 20, maneuver: null, remainingM: 31), isNull);
      expect(
        next(scheduler, 20, maneuver: null, remainingM: 30),
        ManeuverCue.arrived,
      );
    });
  });
}
