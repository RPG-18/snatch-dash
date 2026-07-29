import 'dart:async' show unawaited;
import 'dart:math';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/garage_repository.dart';
import '../data/sqlite_garage_repository.dart';
import '../models/garage_models.dart';
import '../models/vehicle_profile.dart';
import 'maintenance_notifier.dart';
import 'vehicle_store.dart';

class FuelRow {
  const FuelRow(this.fill, this.kmpl);
  final FuelFillup fill;
  final double? kmpl;
}

class MaintRow {
  const MaintRow(this.item, this.remainingKm, this.remainingDays, this.tone, this.officialSchedule);
  final MaintenanceItem item;
  final int remainingKm;
  final int? remainingDays;
  final String tone; // "ok" | "warn" | "alert"
  final OfficialMaintenanceSchedule? officialSchedule;
}

class GarageUi {
  const GarageUi({
    this.activeVehicleId = VehicleStore.defaultVehicleId,
    this.activeVehicleName = 'Himalayan 450',
    this.odometerKm = 0,
    this.fuel = const [],
    this.maint = const [],
    this.expenses = const [],
    this.avgKmpl30,
    this.avgKmplLast5,
    this.spent30 = 0,
    this.litres30 = 0,
    this.fills30 = 0,
    this.expensesTotal = 0,
    this.expenses30 = 0,
  });

  final String activeVehicleId;
  final String activeVehicleName;
  final int odometerKm;
  final List<FuelRow> fuel;
  final List<MaintRow> maint;
  final List<Expense> expenses;
  final double? avgKmpl30;
  final double? avgKmplLast5;
  final double spent30;
  final double litres30;
  final int fills30;
  final double expensesTotal;
  final double expenses30;
}

final garageRepositoryProvider = Provider<GarageRepository>((ref) => SqliteGarageRepository());

/// Ports `GarageViewModel.kt`'s `compute()` (fuel efficiency, maintenance due
/// status, 30-day stats) on top of [GarageRepository].
class GarageController extends Notifier<GarageUi> {
  // `_reload` can end up running concurrently — `build()` reschedules it on
  // every `vehicleStoreProvider` mutation (a new `VehicleState` object, even
  // for a same-vehicle edit, since it doesn't override `==`), and every
  // mutating method below also calls it directly. Without this guard the
  // reload that happens to *finish* last wins, which can flash the UI back
  // to data older than what's actually on screen; bumping this at the start
  // of each `_reload` call and checking it again at the end makes the reload
  // that was *started* last win instead.
  int _reloadGeneration = 0;

  @override
  GarageUi build() {
    final vehicle = ref.watch(vehicleStoreProvider).active;
    ref.listen(vehicleStoreProvider, (prev, next) {
      if (prev?.activeVehicleId != next.activeVehicleId) _reload();
    });
    Future.microtask(() async {
      final repo = ref.read(garageRepositoryProvider);
      await repo.ensureMaintenance(vehicle.id);
      await _reload();
    });
    return GarageUi(activeVehicleId: vehicle.id, activeVehicleName: vehicle.title);
  }

  Future<void> _reload() async {
    final generation = ++_reloadGeneration;
    final repo = ref.read(garageRepositoryProvider);
    final vehicle = ref.read(vehicleStoreProvider).active;

    final fills = await repo.fuelFills(vehicle.id); // newest (highest odometer) first
    final storedOdo = await repo.odometer(vehicle.id);
    final fillsMaxOdo = fills.isEmpty ? 0 : fills.map((f) => f.odometerKm).reduce(max);
    final odo = max(storedOdo, fillsMaxOdo);
    final expenses = await repo.expenses(vehicle.id);

    final fuelRows = <FuelRow>[];
    for (var i = 0; i < fills.length; i++) {
      final f = fills[i];
      final prev = i + 1 < fills.length ? fills[i + 1] : null;
      final kmpl = (prev != null && f.litres > 0 && f.odometerKm > prev.odometerKm)
          ? (f.odometerKm - prev.odometerKm) / f.litres
          : null;
      fuelRows.add(FuelRow(f, kmpl));
    }

    final cutoff = DateTime.now().millisecondsSinceEpoch - 30 * 24 * 3600 * 1000;
    final recent = fuelRows.where((r) => r.fill.dateMs >= cutoff).toList();
    final kmpls = recent.map((r) => r.kmpl).whereType<double>().toList();

    final maintItems = await repo.maintenanceItems(vehicle.id);
    final maint = maintItems.map((m) {
      final official = Himalayan450MaintenanceSchedule.forItem(m);
      final remaining = m.lastDoneOdoKm + m.intervalKm - odo;
      final intervalMonths = official?.intervalMonths;
      int? remainingDays;
      if (intervalMonths != null) {
        final lastDone = DateTime.fromMillisecondsSinceEpoch(m.lastDoneDateMs);
        final dueAt = DateTime(lastDone.year, lastDone.month + intervalMonths, lastDone.day);
        remainingDays = (dueAt.difference(DateTime.now()).inMilliseconds / 86400000).ceil();
      }
      final timeWarning =
          intervalMonths != null && remainingDays != null && remainingDays < intervalMonths * 30 * 0.25;
      final tone = (remaining < 0 || (remainingDays != null && remainingDays < 0))
          ? 'alert'
          : (remaining < m.intervalKm * 0.25 || timeWarning)
              ? 'warn'
              : 'ok';
      return MaintRow(m, remaining, remainingDays, tone, official);
    }).toList();

    // The provider can be disposed while the awaits above are pending —
    // writing `state` after that throws `UnmountedRefException`.
    if (!ref.mounted) return;
    if (generation != _reloadGeneration) return; // superseded by a newer reload
    state = GarageUi(
      activeVehicleId: vehicle.id,
      activeVehicleName: vehicle.title,
      odometerKm: odo,
      fuel: fuelRows,
      maint: maint,
      expenses: expenses,
      avgKmpl30: kmpls.isEmpty ? null : kmpls.reduce((a, b) => a + b) / kmpls.length,
      avgKmplLast5: () {
        final top5 = fuelRows.map((r) => r.kmpl).whereType<double>().take(5).toList();
        return top5.isEmpty ? null : top5.reduce((a, b) => a + b) / top5.length;
      }(),
      spent30: recent.fold(0.0, (sum, r) => sum + r.fill.cost),
      litres30: recent.fold(0.0, (sum, r) => sum + r.fill.litres),
      fills30: recent.length,
      expensesTotal: expenses.fold(0.0, (sum, e) => sum + e.amount),
      expenses30: expenses.where((e) => e.dateMs >= cutoff).fold(0.0, (sum, e) => sum + e.amount),
    );
    // Buzz if a service just crossed into "due" (de-duped inside the notifier).
    unawaited(MaintenanceNotifier.instance.check(maintItems, odo));
  }

  Future<void> addFuel(double litres, double cost, int odometerKm, String location) async {
    final repo = ref.read(garageRepositoryProvider);
    await repo.addFuel(litres, cost, odometerKm, location, state.activeVehicleId);
    await _reload();
  }

  Future<void> deleteFuel(FuelFillup fill) async {
    await ref.read(garageRepositoryProvider).deleteFuel(fill);
    await _reload();
  }

  Future<void> addExpense(String category, double amount, String note, {int? dateMs}) async {
    final repo = ref.read(garageRepositoryProvider);
    await repo.addExpense(
      category, amount, note, dateMs ?? DateTime.now().millisecondsSinceEpoch, state.activeVehicleId,
    );
    await _reload();
  }

  Future<void> deleteExpense(Expense expense) async {
    await ref.read(garageRepositoryProvider).deleteExpense(expense);
    await _reload();
  }

  Future<void> markServiceDone(MaintenanceItem item, int odoKm) async {
    await ref.read(garageRepositoryProvider).markServiceDone(item, odoKm);
    await _reload();
  }

  Future<void> logService(MaintenanceItem item, int odoKm, int intervalKm) async {
    await ref.read(garageRepositoryProvider).logService(item, odoKm, intervalKm);
    await _reload();
  }

  Future<void> addService(String name, String iconKey, int intervalKm) async {
    final repo = ref.read(garageRepositoryProvider);
    await repo.addService(name, iconKey, intervalKm, state.activeVehicleId, state.odometerKm);
    await _reload();
  }

  Future<void> deleteService(MaintenanceItem item) async {
    await ref.read(garageRepositoryProvider).deleteService(item);
    await _reload();
  }

  Future<void> setOdometer(int km) async {
    await ref.read(garageRepositoryProvider).setOdometer(km, state.activeVehicleId);
    await _reload();
  }
}

final garageControllerProvider = NotifierProvider<GarageController, GarageUi>(GarageController.new);
