import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/garage_models.dart';
import 'garage_controller.dart';

/// Recorded rides for RidesScreen/HomeScreen. Ports `RidesViewModel.kt`.
/// Rides are written by the ride recorder on dash disconnect (Phase 4/5) —
/// for now this just exposes the (currently always-empty) repository list so
/// the screens have somewhere real to read from once that lands.
class RidesController extends Notifier<List<Ride>> {
  // Guards against an overlapping, earlier-started `_reload` overwriting a
  // later one's fresher result if it happens to finish last (see the same
  // guard in GarageController for the full rationale).
  int _reloadGeneration = 0;

  @override
  List<Ride> build() {
    Future.microtask(_reload);
    return const [];
  }

  Future<void> _reload() async {
    final generation = ++_reloadGeneration;
    final rides = await ref.read(garageRepositoryProvider).rides();
    // The provider can be disposed while this async gap is open — writing
    // `state` after that throws `UnmountedRefException`.
    if (!ref.mounted) return;
    if (generation != _reloadGeneration) return; // superseded by a newer reload
    state = rides;
  }

  Future<void> deleteRide(Ride ride) async {
    await ref.read(garageRepositoryProvider).deleteRide(ride);
    await _reload();
  }
}

final ridesControllerProvider = NotifierProvider<RidesController, List<Ride>>(RidesController.new);
