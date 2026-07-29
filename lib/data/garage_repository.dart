import '../models/garage_models.dart';

/// Storage interface for fuel/expenses/maintenance/odometer/rides. Phase 3
/// uses [InMemoryGarageRepository]; Phase 4 swaps in a `sqflite`-backed
/// implementation behind this same interface (mirrors the original's
/// `SyncRepository.kt` sitting on top of `OpenDashDb`) — screens and
/// controllers don't change.
abstract class GarageRepository {
  Future<List<FuelFillup>> fuelFills(String vehicleId); // newest (highest odometer) first
  Future<FuelFillup> addFuel(double litres, double cost, int odometerKm, String location, String vehicleId);
  Future<void> deleteFuel(FuelFillup fill);

  Future<int> odometer(String vehicleId);
  Future<void> setOdometer(int km, String vehicleId);

  Future<List<Expense>> expenses(String vehicleId);
  Future<Expense> addExpense(String category, double amount, String note, int dateMs, String vehicleId);
  Future<void> deleteExpense(Expense expense);

  Future<List<MaintenanceItem>> maintenanceItems(String vehicleId);
  Future<void> ensureMaintenance(String vehicleId);
  Future<void> markServiceDone(MaintenanceItem item, int odoKm);
  Future<void> logService(MaintenanceItem item, int odoKm, int intervalKm);
  Future<MaintenanceItem> addService(String name, String iconKey, int intervalKm, String vehicleId, int currentOdo);
  Future<void> deleteService(MaintenanceItem item);

  Future<List<Ride>> rides();
  Future<void> deleteRide(Ride ride);
}

class _Seed {
  const _Seed(this.name, this.iconKey, this.intervalKm);
  final String name;
  final String iconKey;
  final int intervalKm;
}

const _seeds = [
  _Seed('Engine oil', 'drop', 10000),
  _Seed('Oil filter', 'fuel', 10000),
  _Seed('Brake pads - front', 'gauge', 10000),
  _Seed('Brake pads - rear', 'gauge', 10000),
  _Seed('Front tyre', 'gauge', 10000),
  _Seed('Rear tyre', 'gauge', 10000),
  _Seed('Air filter', 'wrench', 10000),
  _Seed('Drive chain', 'chain', 500),
];

/// In-memory implementation — real behavior, no persistence yet (Phase 4).
class InMemoryGarageRepository implements GarageRepository {
  final Map<String, List<FuelFillup>> _fuel = {};
  final Map<String, List<Expense>> _expenses = {};
  final Map<String, List<MaintenanceItem>> _maintenance = {};
  final Map<String, int> _odometer = {};
  final List<Ride> _rides = [];
  int _nextId = 1;

  static const _defaultOdometer = 325;

  int _id() => _nextId++;

  @override
  Future<List<FuelFillup>> fuelFills(String vehicleId) async {
    final list = List<FuelFillup>.from(_fuel[vehicleId] ?? const []);
    list.sort((a, b) => b.odometerKm.compareTo(a.odometerKm));
    return list;
  }

  @override
  Future<FuelFillup> addFuel(
      double litres, double cost, int odometerKm, String location, String vehicleId) async {
    final fill = FuelFillup(
      id: _id(),
      dateMs: DateTime.now().millisecondsSinceEpoch,
      litres: litres,
      cost: cost,
      odometerKm: odometerKm,
      location: location,
      vehicleId: vehicleId,
    );
    (_fuel[vehicleId] ??= []).add(fill);
    if (odometerKm > (_odometer[vehicleId] ?? 0)) _odometer[vehicleId] = odometerKm;
    return fill;
  }

  @override
  Future<void> deleteFuel(FuelFillup fill) async {
    _fuel[fill.vehicleId]?.removeWhere((f) => f.id == fill.id);
  }

  @override
  Future<int> odometer(String vehicleId) async => _odometer[vehicleId] ?? _defaultOdometer;

  @override
  Future<void> setOdometer(int km, String vehicleId) async {
    _odometer[vehicleId] = km;
  }

  @override
  Future<List<Expense>> expenses(String vehicleId) async {
    final list = List<Expense>.from(_expenses[vehicleId] ?? const []);
    list.sort((a, b) => b.dateMs.compareTo(a.dateMs));
    return list;
  }

  @override
  Future<Expense> addExpense(
      String category, double amount, String note, int dateMs, String vehicleId) async {
    final expense = Expense(
      id: _id(),
      dateMs: dateMs,
      category: category,
      amount: amount,
      note: note,
      vehicleId: vehicleId,
    );
    (_expenses[vehicleId] ??= []).add(expense);
    return expense;
  }

  @override
  Future<void> deleteExpense(Expense expense) async {
    _expenses[expense.vehicleId]?.removeWhere((e) => e.id == expense.id);
  }

  @override
  Future<List<MaintenanceItem>> maintenanceItems(String vehicleId) async =>
      List<MaintenanceItem>.from(_maintenance[vehicleId] ?? const []);

  @override
  Future<void> ensureMaintenance(String vehicleId) async {
    if (_maintenance[vehicleId]?.isNotEmpty == true) return;
    final now = DateTime.now().millisecondsSinceEpoch;
    _maintenance[vehicleId] = [
      for (final s in _seeds)
        MaintenanceItem(
          id: _id(),
          name: s.name,
          iconKey: s.iconKey,
          intervalKm: s.intervalKm,
          lastDoneOdoKm: 0,
          lastDoneDateMs: now,
          vehicleId: vehicleId,
        ),
    ];
  }

  @override
  Future<void> markServiceDone(MaintenanceItem item, int odoKm) async {
    _replaceMaintenance(
      item.copyWith(lastDoneOdoKm: odoKm, lastDoneDateMs: DateTime.now().millisecondsSinceEpoch),
    );
  }

  @override
  Future<void> logService(MaintenanceItem item, int odoKm, int intervalKm) async {
    _replaceMaintenance(
      item.copyWith(
        lastDoneOdoKm: odoKm,
        lastDoneDateMs: DateTime.now().millisecondsSinceEpoch,
        intervalKm: intervalKm,
      ),
    );
  }

  @override
  Future<MaintenanceItem> addService(
      String name, String iconKey, int intervalKm, String vehicleId, int currentOdo) async {
    final item = MaintenanceItem(
      id: _id(),
      name: name,
      iconKey: iconKey,
      intervalKm: intervalKm,
      lastDoneOdoKm: currentOdo,
      lastDoneDateMs: DateTime.now().millisecondsSinceEpoch,
      vehicleId: vehicleId,
    );
    (_maintenance[vehicleId] ??= []).add(item);
    return item;
  }

  @override
  Future<void> deleteService(MaintenanceItem item) async {
    _maintenance[item.vehicleId]?.removeWhere((m) => m.id == item.id);
  }

  void _replaceMaintenance(MaintenanceItem updated) {
    final list = _maintenance[updated.vehicleId];
    if (list == null) return;
    final idx = list.indexWhere((m) => m.id == updated.id);
    if (idx != -1) list[idx] = updated;
  }

  @override
  Future<List<Ride>> rides() async {
    final list = List<Ride>.from(_rides);
    list.sort((a, b) => b.startMs.compareTo(a.startMs));
    return list;
  }

  @override
  Future<void> deleteRide(Ride ride) async {
    _rides.removeWhere((r) => r.id == ride.id);
  }
}
