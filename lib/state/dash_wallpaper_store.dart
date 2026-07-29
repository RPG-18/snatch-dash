import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image/image.dart' as img;
import 'package:image_picker/image_picker.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../dash/dash_constants.dart';
import '../models/dash_wallpaper.dart';

const _maxSlots = 5;
const _directory = 'dash_wallpaper';
const _prefsFile = 'dash_wallpaper_prefs';
// Shares the `_prefsFile` prefix so `clear()`'s key sweep wipes it too.
const _prefsKeyActiveSlot = '${_prefsFile}_active_slot';

/// Idle-mode dash background: up to 5 slots, cropped/fit to the dash frame
/// on-device before being handed to the native idle renderer. Ported from
/// `data/DashWallpaperStore.kt` — same slot/crop/fit model, `image_picker` +
/// the `image` package standing in for `ImageDecoder`/`Canvas`.
class DashWallpaperStore extends Notifier<List<DashWallpaperInfo>> {
  // Guards against an overlapping, earlier-started `_reload` overwriting a
  // later one's fresher result if it happens to finish last (see the same
  // guard in GarageController for the full rationale) — several methods here
  // (clear/clearSlot/saveFromXFile/...) all end in `_reload()`.
  int _reloadGeneration = 0;

  @override
  List<DashWallpaperInfo> build() {
    Future.microtask(_reload);
    return const [];
  }

  Future<Directory> _dir() async {
    final docs = await getApplicationDocumentsDirectory();
    final dir = Directory(p.join(docs.path, _directory));
    if (!dir.existsSync()) dir.createSync(recursive: true);
    return dir;
  }

  Future<void> _reload() async {
    final generation = ++_reloadGeneration;
    final infos = <DashWallpaperInfo>[];
    final prefs = await SharedPreferences.getInstance();
    for (var slot = 0; slot < _maxSlots; slot++) {
      final info = await _infoForSlot(prefs, slot);
      if (info != null) infos.add(info);
    }
    if (!ref.mounted) return;
    if (generation != _reloadGeneration) return; // superseded by a newer reload
    // The active slot lives in prefs, so the rider's pick survives a restart
    // instead of snapping back to slot 0 — every mutator persists it via
    // [_setActiveSlot] before calling here. Falls back to the first populated
    // slot if the remembered one no longer has an image.
    final saved = prefs.getInt(_prefsKeyActiveSlot) ?? 0;
    _activeSlot = infos.any((i) => i.slot == saved) ? saved : (infos.isEmpty ? 0 : infos.first.slot);
    state = infos;
    await _pushCurrentToEngine(prefs);
  }

  Future<void> _setActiveSlot(int slot, SharedPreferences prefs) async {
    _activeSlot = slot;
    await prefs.setInt(_prefsKeyActiveSlot, slot);
  }

  Future<DashWallpaperInfo?> _infoForSlot(SharedPreferences prefs, int slot) async {
    final path = prefs.getString('${_prefsFile}_path_$slot');
    if (path == null || !File(path).existsSync()) return null;
    final kindName = prefs.getString('${_prefsFile}_kind_$slot') ?? DashWallpaperKind.image.name;
    final fitName = prefs.getString('${_prefsFile}_fit_$slot') ?? DashWallpaperFit.crop.name;
    return DashWallpaperInfo(
      slot: slot,
      path: path,
      kind: DashWallpaperKind.values.firstWhere((k) => k.name == kindName, orElse: () => DashWallpaperKind.image),
      horizontalBias: prefs.getDouble('${_prefsFile}_biasx_$slot') ?? 0,
      verticalBias: prefs.getDouble('${_prefsFile}_biasy_$slot') ?? 0,
      fit: DashWallpaperFit.values.firstWhere((f) => f.name == fitName, orElse: () => DashWallpaperFit.crop),
    );
  }

  DashWallpaperInfo? get current => state.isEmpty
      ? null
      : (state.firstWhere((i) => i.slot == _activeSlot, orElse: () => state.first));

  int get activeSlot => _activeSlot;
  int _activeSlot = 0;

  /// Make [slot] the active wallpaper directly (e.g. tapping a thumbnail).
  Future<void> selectSlot(int slot) async {
    if (state.every((i) => i.slot != slot)) return;
    final prefs = await SharedPreferences.getInstance();
    await _setActiveSlot(slot, prefs);
    await _pushCurrentToEngine(prefs);
  }

  /// Cycle the active slot by [delta] (±1) through the populated gallery.
  Future<void> cycle(int delta) async {
    if (state.isEmpty) return;
    final idx = state.indexWhere((i) => i.slot == _activeSlot);
    final nextIdx = ((idx == -1 ? 0 : idx) + delta) % state.length;
    final prefs = await SharedPreferences.getInstance();
    await _setActiveSlot(state[(nextIdx + state.length) % state.length].slot, prefs);
    await _pushCurrentToEngine(prefs);
  }

  Future<void> updateCurrentOptions({
    required double horizontalBias,
    required double verticalBias,
    required DashWallpaperFit fit,
  }) async {
    final c = current;
    if (c == null) return;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setDouble('${_prefsFile}_biasx_${c.slot}', horizontalBias);
    await prefs.setDouble('${_prefsFile}_biasy_${c.slot}', verticalBias);
    await prefs.setString('${_prefsFile}_fit_${c.slot}', fit.name);
    await _reload();
  }

  Future<void> clear() async {
    final dir = await _dir();
    if (dir.existsSync()) {
      for (final f in dir.listSync()) {
        f.deleteSync();
      }
    }
    final prefs = await SharedPreferences.getInstance();
    for (final key in prefs.getKeys().where((k) => k.startsWith(_prefsFile))) {
      await prefs.remove(key);
    }
    _activeSlot = 0;
    await _reload();
  }

  Future<void> clearSlot(int slot) async {
    final prefs = await SharedPreferences.getInstance();
    final path = prefs.getString('${_prefsFile}_path_$slot');
    if (path != null) {
      final f = File(path);
      if (f.existsSync()) f.deleteSync();
    }
    for (final suffix in ['path', 'kind', 'biasx', 'biasy', 'fit']) {
      await prefs.remove('${_prefsFile}_${suffix}_$slot');
    }
    if (_activeSlot == slot) await _setActiveSlot(0, prefs);
    await _reload();
  }

  Future<void> saveFromXFile(
    XFile file, {
    double horizontalBias = 0,
    double verticalBias = 0,
    DashWallpaperFit fit = DashWallpaperFit.crop,
  }) async {
    final slot = await _firstEmptySlot() ?? _activeSlot;
    await _saveToSlot(slot, file, horizontalBias, verticalBias, fit);
    await _setActiveSlot(slot, await SharedPreferences.getInstance());
    await _reload();
  }

  Future<void> saveManyFromXFiles(List<XFile> files) async {
    for (final file in files.take(_maxSlots)) {
      final slot = await _firstEmptySlot() ?? _activeSlot;
      await _saveToSlot(slot, file, 0, 0, DashWallpaperFit.crop);
    }
    await _reload();
  }

  Future<int?> _firstEmptySlot() async {
    final prefs = await SharedPreferences.getInstance();
    for (var slot = 0; slot < _maxSlots; slot++) {
      if (prefs.getString('${_prefsFile}_path_$slot') == null) return slot;
    }
    return null;
  }

  Future<void> _saveToSlot(
    int slot,
    XFile file,
    double horizontalBias,
    double verticalBias,
    DashWallpaperFit fit,
  ) async {
    await clearSlot(slot);
    final isGif = (file.mimeType == 'image/gif') || file.path.toLowerCase().endsWith('.gif');
    final dir = await _dir();
    final prefs = await SharedPreferences.getInstance();

    if (isGif) {
      final out = File(p.join(dir.path, 'source_wallpaper_$slot.gif'));
      await out.writeAsBytes(await file.readAsBytes());
      await prefs.setString('${_prefsFile}_path_$slot', out.path);
      await prefs.setString('${_prefsFile}_kind_$slot', DashWallpaperKind.gif.name);
    } else {
      final bytes = await file.readAsBytes();
      final decoded = img.decodeImage(bytes);
      if (decoded == null) return;
      final rendered = _renderToDash(decoded, dashFrameWidth, dashFrameHeight, horizontalBias, verticalBias, fit);
      final out = File(p.join(dir.path, 'idle_wallpaper_$slot.png'));
      await out.writeAsBytes(img.encodePng(rendered));
      await prefs.setString('${_prefsFile}_path_$slot', out.path);
      await prefs.setString('${_prefsFile}_kind_$slot', DashWallpaperKind.image.name);
    }
    await prefs.setDouble('${_prefsFile}_biasx_$slot', horizontalBias);
    await prefs.setDouble('${_prefsFile}_biasy_$slot', verticalBias);
    await prefs.setString('${_prefsFile}_fit_$slot', fit.name);
  }

  img.Image _renderToDash(
    img.Image source,
    int width,
    int height,
    double horizontalBias,
    double verticalBias,
    DashWallpaperFit fit,
  ) {
    return switch (fit) {
      DashWallpaperFit.crop => _cropToDash(source, width, height, horizontalBias, verticalBias),
      DashWallpaperFit.fitHeight => _fitToDash(source, width, height, fitHeight: true),
      DashWallpaperFit.fitWidth => _fitToDash(source, width, height, fitHeight: false),
    };
  }

  img.Image _cropToDash(img.Image source, int width, int height, double hBias, double vBias) {
    final srcRatio = source.width / source.height;
    final dstRatio = width / height;
    int left, top, cropW, cropH;
    if (srcRatio > dstRatio) {
      cropH = source.height;
      cropW = (source.height * dstRatio).round().clamp(1, source.width);
      final extra = source.width - cropW;
      left = ((extra / 2) + (extra / 2) * hBias.clamp(-1, 1)).round();
      top = 0;
    } else {
      cropW = source.width;
      cropH = (source.width / dstRatio).round().clamp(1, source.height);
      final extra = source.height - cropH;
      top = ((extra / 2) + (extra / 2) * vBias.clamp(-1, 1)).round();
      left = 0;
    }
    final cropped = img.copyCrop(source, x: left, y: top, width: cropW, height: cropH);
    return img.copyResize(cropped, width: width, height: height);
  }

  img.Image _fitToDash(img.Image source, int width, int height, {required bool fitHeight}) {
    final scale = fitHeight ? height / source.height : width / source.width;
    final drawW = (source.width * scale).round().clamp(1, 1 << 16);
    final drawH = (source.height * scale).round().clamp(1, 1 << 16);
    final resized = img.copyResize(source, width: drawW, height: drawH);
    final canvas = img.Image(width: width, height: height);
    img.fill(canvas, color: img.ColorRgb8(13, 15, 16));
    img.compositeImage(canvas, resized, dstX: (width - drawW) ~/ 2, dstY: (height - drawH) ~/ 2);
    return canvas;
  }

  Future<void> _pushCurrentToEngine(SharedPreferences prefs) async {
    final c = current;
    await DashEngine.instance.setWallpaper(
      path: c?.path,
      kind: c?.kind.name.toUpperCase(),
      fit: c?.fit.name == 'fitHeight'
          ? 'FIT_HEIGHT'
          : c?.fit.name == 'fitWidth'
              ? 'FIT_WIDTH'
              : 'CROP',
      biasX: c?.horizontalBias ?? 0,
      biasY: c?.verticalBias ?? 0,
    );
  }
}

final dashWallpaperStoreProvider =
    NotifierProvider<DashWallpaperStore, List<DashWallpaperInfo>>(DashWallpaperStore.new);
