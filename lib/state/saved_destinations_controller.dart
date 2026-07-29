import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/saved_location_repository.dart';
import '../models/shared_location.dart';

final savedLocationRepositoryProvider =
    Provider<SavedLocationRepository>((ref) => SqliteSavedLocationRepository());

/// Saved destinations shown on Home / offered for quick re-routing. Ports
/// the saved-locations half of `RouteViewModel.kt`, now backed by `sqflite`
/// (`saved_location` table) via [SavedLocationRepository].
class SavedDestinationsController extends Notifier<List<SavedLocation>> {
  // Guards against an overlapping, earlier-started `_reload` overwriting a
  // later one's fresher result if it happens to finish last (see the same
  // guard in GarageController for the full rationale).
  int _reloadGeneration = 0;

  @override
  List<SavedLocation> build() {
    Future.microtask(_reload);
    return const [];
  }

  Future<void> _reload() async {
    final generation = ++_reloadGeneration;
    final locations = await ref.read(savedLocationRepositoryProvider).list();
    // The provider can be disposed (e.g. widget unmounted) while this async
    // gap is open — writing `state` after that throws `UnmountedRefException`.
    if (!ref.mounted) return;
    if (generation != _reloadGeneration) return; // superseded by a newer reload
    state = locations;
  }

  Future<void> save(String name, double lat, double lng) async {
    await ref.read(savedLocationRepositoryProvider).add(name, lat, lng);
    await _reload();
  }

  Future<void> remove(SavedLocation location) async {
    await ref.read(savedLocationRepositoryProvider).remove(location);
    await _reload();
  }
}

final savedDestinationsControllerProvider =
    NotifierProvider<SavedDestinationsController, List<SavedLocation>>(
  SavedDestinationsController.new,
);
