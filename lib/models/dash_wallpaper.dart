/// Ported from the original app's `data/DashWallpaperStore.kt`. VIDEO isn't
/// carried over — `image_picker`'s image/GIF flow (`pickMultiImage`) has no
/// single-call equivalent that also offers video, and video was the least
/// commonly used of the three; the native idle renderer
/// (`DashIdleRenderer.kt`) still has the video-frame-extraction path if this
/// gets revisited.
enum DashWallpaperKind { image, gif }

enum DashWallpaperFit { crop, fitHeight, fitWidth }

class DashWallpaperInfo {
  const DashWallpaperInfo({
    required this.slot,
    required this.path,
    required this.kind,
    required this.horizontalBias,
    required this.verticalBias,
    required this.fit,
  });

  final int slot;
  final String path;
  final DashWallpaperKind kind;
  final double horizontalBias;
  final double verticalBias;
  final DashWallpaperFit fit;

  DashWallpaperInfo copyWith({
    double? horizontalBias,
    double? verticalBias,
    DashWallpaperFit? fit,
  }) =>
      DashWallpaperInfo(
        slot: slot,
        path: path,
        kind: kind,
        horizontalBias: horizontalBias ?? this.horizontalBias,
        verticalBias: verticalBias ?? this.verticalBias,
        fit: fit ?? this.fit,
      );
}
