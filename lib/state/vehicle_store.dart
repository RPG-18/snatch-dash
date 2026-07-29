import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/vehicle_profile.dart';

const _prefsVehicles = 'vehicles';
const _prefsActive = 'active_vehicle';

class VehicleState {
  const VehicleState({this.vehicles = const [], this.activeVehicleId = VehicleStore.defaultVehicleId});

  final List<VehicleProfile> vehicles;
  final String activeVehicleId;

  VehicleProfile get active =>
      vehicles.firstWhere((v) => v.id == activeVehicleId, orElse: () => vehicles.first);
}

final _defaultVehicle = const VehicleProfile(
  id: VehicleStore.defaultVehicleId,
  title: 'Himalayan 450',
  nickname: 'Default vehicle',
  puc: 'Not set',
  insurance: 'Not set',
  service: 'Not set',
);

/// Vehicle profiles (add/edit/select active vehicle), persisted as JSON in
/// `SharedPreferences` — a direct port of `data/VehicleStore.kt`, which used
/// the same storage (not SQLite, so no Phase-4 dependency here).
class VehicleStoreController extends Notifier<VehicleState> {
  static const _prefsKeyVehicles = _prefsVehicles;
  static const _prefsKeyActive = _prefsActive;

  // Bumped by every mutating method (add/update/select) so a slow initial
  // `_load()` can detect it was superseded and skip writing its now-stale
  // disk snapshot over a mutation that already landed — otherwise `_load()`
  // would silently overwrite (and re-persist, permanently losing) a vehicle
  // added/edited while the load was still in flight.
  int _generation = 0;

  @override
  VehicleState build() {
    _load(_generation);
    return VehicleState(vehicles: [_defaultVehicle]);
  }

  Future<void> _load(int generation) async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_prefsKeyVehicles);
    List<VehicleProfile> vehicles = [_defaultVehicle];
    if (raw != null) {
      try {
        final decoded = jsonDecode(raw) as List<dynamic>;
        final loaded = decoded
            .map((e) => VehicleProfile.fromJson(e as Map<String, dynamic>))
            .toList();
        if (loaded.isNotEmpty) vehicles = loaded;
      } catch (_) {
        // Keep the default vehicle on corrupt prefs.
      }
    }
    final savedActive = prefs.getString(_prefsKeyActive);
    final activeId = vehicles.any((v) => v.id == savedActive) ? savedActive! : vehicles.first.id;
    // The provider can be disposed while the awaits above are pending —
    // writing `state` after that throws `UnmountedRefException`.
    if (!ref.mounted) return;
    if (generation != _generation) return; // superseded by a mutation — don't clobber it
    state = VehicleState(vehicles: vehicles, activeVehicleId: activeId);
    await _persist();
  }

  Future<void> add(VehicleProfile profile) async {
    final created = profile.id.isEmpty
        ? VehicleProfile(
            id: DateTime.now().microsecondsSinceEpoch.toString(),
            title: profile.title,
            nickname: profile.nickname,
            puc: profile.puc,
            insurance: profile.insurance,
            service: profile.service,
          )
        : profile;
    _generation++;
    state = VehicleState(vehicles: [...state.vehicles, created], activeVehicleId: created.id);
    await _persist();
  }

  Future<void> update(VehicleProfile profile) async {
    _generation++;
    state = VehicleState(
      vehicles: [for (final v in state.vehicles) if (v.id == profile.id) profile else v],
      activeVehicleId: state.activeVehicleId,
    );
    await _persist();
  }

  Future<void> select(String vehicleId) async {
    if (state.vehicles.every((v) => v.id != vehicleId)) return;
    _generation++;
    state = VehicleState(vehicles: state.vehicles, activeVehicleId: vehicleId);
    await _persist();
  }

  Future<void> _persist() async {
    final prefs = await SharedPreferences.getInstance();
    final encoded = jsonEncode(state.vehicles.map((v) => v.toJson()).toList());
    await prefs.setString(_prefsKeyVehicles, encoded);
    await prefs.setString(_prefsKeyActive, state.activeVehicleId);
  }
}

final vehicleStoreProvider = NotifierProvider<VehicleStoreController, VehicleState>(
  VehicleStoreController.new,
);
