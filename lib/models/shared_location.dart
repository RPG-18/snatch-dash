/// The destination currently being previewed or navigated to. Ported from the
/// original app's `data/SharedLocation.kt` — the name is inherited from that
/// port (there it was filled from a share intent); here it is always resolved
/// in-app, through Yandex search or a saved place.
class SharedLocation {
  const SharedLocation({
    required this.name,
    required this.lat,
    required this.lng,
  });

  final String name;

  /// Non-null by construction: a destination only ever comes from Yandex
  /// search or a saved place, both of which resolve coordinates before this
  /// object exists. "Destination without coordinates" was a share-intent
  /// state (a short URL awaiting a network resolve) and no longer occurs.
  final double lat;
  final double lng;
}

/// A persisted destination the rider saved for later. Ported from
/// `data/SharedLocation.kt`'s `SavedLocation`.
class SavedLocation {
  const SavedLocation({
    this.id,
    required this.name,
    required this.lat,
    required this.lng,
  });

  final int? id;
  final String name;
  final double lat;
  final double lng;
}
