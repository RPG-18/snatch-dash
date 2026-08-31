import 'dart:async';

import 'package:flutter/material.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

/// Dev tool for the maneuver-glyph reverse-engineering project (see
/// `lib/nav/route.dart`'s `Maneuver.dashCode`): cycles the raw byte the K1G
/// protocol writes into `activeNavPacket`'s `05 02` field, so its rendered
/// icon can be read off a physical dash and matched to a [ManeuverType].
/// Hardware-confirmed so far (2026-08-15 sweep): the plain straight/left/
/// right/sharp/slight turns (`0x09`, `0x14`..`0x19`) and the whole clockwise
/// roundabout row (`0x0A`..`0x13`, `0x46`..`0x4F`) — see `spec/glyph.md`.
/// Note `0x0B` is "roundabout, clockwise, exit 1", NOT the neutral
/// straight-ahead glyph the upstream open-dash project assumed it was.
///
/// Reuses the existing `setDestination`/`setNavState` plugin API end to
/// end — no native code needed, this just drives it with a throwaway
/// destination and whatever byte is currently selected.
///
/// Only ever mounted behind `kDebugMode` by the caller (see
/// [DashScreen]) — kept indefinitely rather than stripped after the
/// glyph table is filled in, in case dash firmware changes or a new
/// glyph needs re-verifying later.
class ManeuverGlyphProbe extends StatefulWidget {
  const ManeuverGlyphProbe({super.key, this.riderLat, this.riderLng});

  /// Used as the throwaway destination's coordinates — its exact position
  /// doesn't matter, only that it's non-null so the dash's nav chrome
  /// (instruction bubble) turns on. Falls back to a fixed point if the GPS
  /// fix isn't in yet.
  final double? riderLat;
  final double? riderLng;

  @override
  State<ManeuverGlyphProbe> createState() => _ManeuverGlyphProbeState();
}

class _ManeuverGlyphProbeState extends State<ManeuverGlyphProbe> {
  int _byte = 0x09; // straight ahead — the neutral glyph, a sane starting point
  bool _navStarted = false;

  Future<void> _startTestNav() async {
    await DashEngine.instance.setDestination(
      name: 'Glyph probe',
      lat: widget.riderLat ?? 0,
      lng: widget.riderLng ?? 0,
    );
    setState(() => _navStarted = true);
    await _push();
  }

  Future<void> _push() => DashEngine.instance.setNavState(
        remainingMeters: 500,
        nextTurnMeters: 200,
        maneuver: _byte,
        etaHHMM: '1200',
        offRoute: false,
        points: const [],
      );

  void _step(int delta) {
    setState(() => _byte = (_byte + delta) & 0xFF);
    if (_navStarted) unawaited(_push());
  }

  @override
  Widget build(BuildContext context) {
    final hex = _byte.toRadixString(16).padLeft(2, '0').toUpperCase();
    return Card(
      color: Colors.black87,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('glyph probe', style: TextStyle(color: Colors.white54, fontSize: 10)),
            Text(
              '0x$hex',
              style: const TextStyle(color: Colors.white, fontSize: 20, fontFamily: 'monospace'),
            ),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                IconButton(
                  color: Colors.white,
                  iconSize: 20,
                  onPressed: () => _step(-1),
                  icon: const Icon(Icons.remove),
                ),
                IconButton(
                  color: Colors.white,
                  iconSize: 20,
                  onPressed: () => _step(1),
                  icon: const Icon(Icons.add),
                ),
              ],
            ),
            SizedBox(
              width: 96,
              child: TextButton(
                onPressed: _navStarted ? _push : _startTestNav,
                child: Text(
                  _navStarted ? 'resend' : 'start',
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
