/// A location parsed out of a share-intent text/URL. Ported from the
/// original app's `data/SharedLocation.kt`.
class SharedLocation {
  const SharedLocation({
    required this.name,
    this.lat,
    this.lng,
    this.url,
    this.needsExpansion = false,
  });

  final String name;
  final double? lat;
  final double? lng;
  final String? url;

  /// True when a short URL (maps.app.goo.gl, goo.gl/maps, …) still needs a
  /// network resolve (`LocationParser.resolve`) before coordinates are known.
  final bool needsExpansion;
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
