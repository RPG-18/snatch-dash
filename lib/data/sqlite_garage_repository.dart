import 'package:sqflite/sqflite.dart';

import '../models/garage_models.dart';
import 'app_database.dart';
import 'garage_repository.dart';

const _defaultOdometer = 325;

const _seeds = [
  ('Engine oil', 'drop', 10000),
  ('Oil filter', 'fuel', 10000),
  ('Brake pads - front', 'gauge', 10000),
  ('Brake pads - rear', 'gauge', 10000),
  ('Front tyre', 'gauge', 10000),
  ('Rear tyre', 'gauge', 10000),
  ('Air filter', 'wrench', 10000),
  ('Drive chain', 'chain', 500),
];

/// `sqflite`-backed [GarageRepository] — real persistence on top of
/// [AppDatabase], replacing the in-memory Phase 3 placeholder behind the
/// same interface (screens/controllers are unchanged).
class SqliteGarageRepository implements GarageRepository {
  SqliteGarageRepository({AppDatabase? db}) : _db = db ?? AppDatabase.instance;

  final AppDatabase _db;

  @override
  Future<List<FuelFillup>> fuelFills(String vehicleId) async {
    final db = await _db.database;
    final rows = await db.query(
      'fuel_fillup',
      where: 'vehicle_id = ?',
      whereArgs: [vehicleId],
      orderBy: 'odometer_km DESC',
    );
    return rows.map(_fuelFromRow).toList();
  }

  @override
  Future<FuelFillup> addFuel(
      double litres, double cost, int odometerKm, String location, String vehicleId) async {
    final db = await _db.database;
    final fill = FuelFillup(
      dateMs: DateTime.now().millisecondsSinceEpoch,
      litres: litres,
      cost: cost,
      odometerKm: odometerKm,
      location: location,
      vehicleId: vehicleId,
    );
    final id = await db.insert('fuel_fillup', _fuelToRow(fill));
    final current = await odometer(vehicleId);
    if (odometerKm > current) await setOdometer(odometerKm, vehicleId);
    return fill.copyWith(id: id);
  }

  @override
  Future<void> deleteFuel(FuelFillup fill) async {
    final db = await _db.database;
    await db.delete('fuel_fillup', where: 'id = ?', whereArgs: [fill.id]);
  }

  @override
  Future<int> odometer(String vehicleId) async {
    final db = await _db.database;
    final rows = await db.query('vehicle_state', where: 'vehicle_id = ?', whereArgs: [vehicleId]);
    if (rows.isEmpty) return _defaultOdometer;
    return rows.first['odometer_km'] as int;
  }

  @override
  Future<void> setOdometer(int km, String vehicleId) async {
    final db = await _db.database;
    await db.insert(
      'vehicle_state',
      {'vehicle_id': vehicleId, 'odometer_km': km},
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  @override
  Future<List<Expense>> expenses(String vehicleId) async {
    final db = await _db.database;
    final rows = await db.query(
      'expense',
      where: 'vehicle_id = ?',
      whereArgs: [vehicleId],
      orderBy: 'date_ms DESC',
    );
    return rows.map(_expenseFromRow).toList();
  }

  @override
  Future<Expense> addExpense(
      String category, double amount, String note, int dateMs, String vehicleId) async {
    final db = await _db.database;
    final expense = Expense(
      dateMs: dateMs,
      category: category,
      amount: amount,
      note: note,
      vehicleId: vehicleId,
    );
    final id = await db.insert('expense', _expenseToRow(expense));
    return expense.copyWith(id: id);
  }

  @override
  Future<void> deleteExpense(Expense expense) async {
    final db = await _db.database;
    await db.delete('expense', where: 'id = ?', whereArgs: [expense.id]);
  }

  @override
  Future<List<MaintenanceItem>> maintenanceItems(String vehicleId) async {
    final db = await _db.database;
    final rows = await db.query('maintenance_item', where: 'vehicle_id = ?', whereArgs: [vehicleId]);
    return rows.map(_maintenanceFromRow).toList();
  }

  /// The count and the seed inserts have to be atomic. `GarageController`
  /// schedules two calls for the same vehicle back to back on a cold start —
  /// its `build()` re-runs once `vehicleStoreProvider` finishes loading from
  /// prefs — and sqflite runs both `COUNT`s before either insert lands, so
  /// unguarded both saw an empty table and seeded, leaving 16 permanent
  /// maintenance rows instead of 8. A transaction takes the write lock for the
  /// whole check-then-insert, so the second call sees the first one's rows.
  @override
  Future<void> ensureMaintenance(String vehicleId) async {
    final db = await _db.database;
    await db.transaction((txn) async {
      final existing = Sqflite.firstIntValue(
        await txn.rawQuery('SELECT COUNT(*) FROM maintenance_item WHERE vehicle_id = ?', [vehicleId]),
      );
      if ((existing ?? 0) > 0) return;
      final now = DateTime.now().millisecondsSinceEpoch;
      final batch = txn.batch();
      for (final (name, iconKey, intervalKm) in _seeds) {
        batch.insert('maintenance_item', {
          'name': name,
          'icon_key': iconKey,
          'interval_km': intervalKm,
          'last_done_odo_km': 0,
          'last_done_date_ms': now,
          'vehicle_id': vehicleId,
        });
      }
      await batch.commit(noResult: true);
    });
  }

  @override
  Future<void> markServiceDone(MaintenanceItem item, int odoKm) async {
    final db = await _db.database;
    await db.update(
      'maintenance_item',
      {'last_done_odo_km': odoKm, 'last_done_date_ms': DateTime.now().millisecondsSinceEpoch},
      where: 'id = ?',
      whereArgs: [item.id],
    );
  }

  @override
  Future<void> logService(MaintenanceItem item, int odoKm, int intervalKm) async {
    final db = await _db.database;
    await db.update(
      'maintenance_item',
      {
        'last_done_odo_km': odoKm,
        'last_done_date_ms': DateTime.now().millisecondsSinceEpoch,
        'interval_km': intervalKm,
      },
      where: 'id = ?',
      whereArgs: [item.id],
    );
  }

  @override
  Future<MaintenanceItem> addService(
      String name, String iconKey, int intervalKm, String vehicleId, int currentOdo) async {
    final db = await _db.database;
    final item = MaintenanceItem(
      name: name,
      iconKey: iconKey,
      intervalKm: intervalKm,
      lastDoneOdoKm: currentOdo,
      lastDoneDateMs: DateTime.now().millisecondsSinceEpoch,
      vehicleId: vehicleId,
    );
    final id = await db.insert('maintenance_item', _maintenanceToRow(item));
    return item.copyWith(id: id);
  }

  @override
  Future<void> deleteService(MaintenanceItem item) async {
    final db = await _db.database;
    await db.delete('maintenance_item', where: 'id = ?', whereArgs: [item.id]);
  }

  @override
  Future<List<Ride>> rides() async {
    final db = await _db.database;
    final rows = await db.query('ride', orderBy: 'start_ms DESC');
    return rows.map(_rideFromRow).toList();
  }

  @override
  Future<void> deleteRide(Ride ride) async {
    final db = await _db.database;
    await db.delete('ride', where: 'id = ?', whereArgs: [ride.id]);
  }

  Future<Ride> addRide(Ride ride) async {
    final db = await _db.database;
    final id = await db.insert('ride', _rideToRow(ride));
    return Ride(
      id: id,
      startMs: ride.startMs,
      endMs: ride.endMs,
      distanceMeters: ride.distanceMeters,
      durationSec: ride.durationSec,
      avgSpeedMps: ride.avgSpeedMps,
      maxSpeedMps: ride.maxSpeedMps,
      trackPolyline: ride.trackPolyline,
      startLat: ride.startLat,
      startLng: ride.startLng,
      endLat: ride.endLat,
      endLng: ride.endLng,
    );
  }

  // ── Row mapping ──────────────────────────────────────────────────────

  FuelFillup _fuelFromRow(Map<String, Object?> row) => FuelFillup(
        id: row['id'] as int,
        dateMs: row['date_ms'] as int,
        litres: (row['litres'] as num).toDouble(),
        cost: (row['cost'] as num).toDouble(),
        odometerKm: row['odometer_km'] as int,
        location: row['location'] as String,
        vehicleId: row['vehicle_id'] as String,
      );

  Map<String, Object?> _fuelToRow(FuelFillup f) => {
        'date_ms': f.dateMs,
        'litres': f.litres,
        'cost': f.cost,
        'odometer_km': f.odometerKm,
        'location': f.location,
        'vehicle_id': f.vehicleId,
      };

  Expense _expenseFromRow(Map<String, Object?> row) => Expense(
        id: row['id'] as int,
        dateMs: row['date_ms'] as int,
        category: row['category'] as String,
        amount: (row['amount'] as num).toDouble(),
        note: row['note'] as String,
        vehicleId: row['vehicle_id'] as String,
      );

  Map<String, Object?> _expenseToRow(Expense e) => {
        'date_ms': e.dateMs,
        'category': e.category,
        'amount': e.amount,
        'note': e.note,
        'vehicle_id': e.vehicleId,
      };

  MaintenanceItem _maintenanceFromRow(Map<String, Object?> row) => MaintenanceItem(
        id: row['id'] as int,
        name: row['name'] as String,
        iconKey: row['icon_key'] as String,
        intervalKm: row['interval_km'] as int,
        lastDoneOdoKm: row['last_done_odo_km'] as int,
        lastDoneDateMs: row['last_done_date_ms'] as int,
        vehicleId: row['vehicle_id'] as String,
      );

  Map<String, Object?> _maintenanceToRow(MaintenanceItem m) => {
        'name': m.name,
        'icon_key': m.iconKey,
        'interval_km': m.intervalKm,
        'last_done_odo_km': m.lastDoneOdoKm,
        'last_done_date_ms': m.lastDoneDateMs,
        'vehicle_id': m.vehicleId,
      };

  Ride _rideFromRow(Map<String, Object?> row) => Ride(
        id: row['id'] as int,
        startMs: row['start_ms'] as int,
        endMs: row['end_ms'] as int,
        distanceMeters: (row['distance_meters'] as num).toDouble(),
        durationSec: row['duration_sec'] as int,
        avgSpeedMps: (row['avg_speed_mps'] as num).toDouble(),
        maxSpeedMps: (row['max_speed_mps'] as num).toDouble(),
        trackPolyline: row['track_polyline'] as String,
        startLat: (row['start_lat'] as num).toDouble(),
        startLng: (row['start_lng'] as num).toDouble(),
        endLat: (row['end_lat'] as num).toDouble(),
        endLng: (row['end_lng'] as num).toDouble(),
      );

  Map<String, Object?> _rideToRow(Ride r) => {
        'start_ms': r.startMs,
        'end_ms': r.endMs,
        'distance_meters': r.distanceMeters,
        'duration_sec': r.durationSec,
        'avg_speed_mps': r.avgSpeedMps,
        'max_speed_mps': r.maxSpeedMps,
        'track_polyline': r.trackPolyline,
        'start_lat': r.startLat,
        'start_lng': r.startLng,
        'end_lat': r.endLat,
        'end_lng': r.endLng,
      };
}
