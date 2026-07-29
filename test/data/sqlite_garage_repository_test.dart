import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:snatch_dash/data/app_database.dart';
import 'package:snatch_dash/data/saved_location_repository.dart';
import 'package:snatch_dash/data/sqlite_garage_repository.dart';

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  });

  late SqliteGarageRepository garageRepo;
  late SqliteSavedLocationRepository savedRepo;
  late String dbPath;

  setUp(() async {
    // `databaseFactoryFfi` caches open connections by path, so every test
    // needs a distinct file (an in-memory `:memory:` path would be silently
    // shared across tests, letting rows leak between them) —
    // `AppDatabase.forTesting` isolates it from the app-wide singleton too.
    dbPath = p.join(
      Directory.systemTemp.path,
      'opendash_test_${DateTime.now().microsecondsSinceEpoch}.db',
    );
    final db = AppDatabase.forTesting(dbPath);
    garageRepo = SqliteGarageRepository(db: db);
    savedRepo = SqliteSavedLocationRepository(db: db);
  });

  tearDown(() async {
    await databaseFactory.deleteDatabase(dbPath);
  });

  group('SqliteGarageRepository — fuel', () {
    test('addFuel persists and fuelFills returns newest-odometer-first', () async {
      await garageRepo.addFuel(10, 900, 1000, 'Shell', 'default');
      await garageRepo.addFuel(9.5, 850, 1300, 'IOCL', 'default');

      final fills = await garageRepo.fuelFills('default');
      expect(fills.length, 2);
      expect(fills.first.odometerKm, 1300);
      expect(fills.last.odometerKm, 1000);
    });

    test('addFuel raises the vehicle odometer when higher than stored', () async {
      await garageRepo.setOdometer(500, 'default');
      await garageRepo.addFuel(10, 900, 1200, '', 'default');

      expect(await garageRepo.odometer('default'), 1200);
    });

    test('deleteFuel removes the row', () async {
      final fill = await garageRepo.addFuel(10, 900, 1000, '', 'default');
      await garageRepo.deleteFuel(fill);

      expect(await garageRepo.fuelFills('default'), isEmpty);
    });
  });

  group('SqliteGarageRepository — maintenance', () {
    test('ensureMaintenance seeds the 8 default intervals once', () async {
      await garageRepo.ensureMaintenance('default');
      await garageRepo.ensureMaintenance('default'); // second call must be a no-op

      final items = await garageRepo.maintenanceItems('default');
      expect(items.length, 8);
      expect(items.map((i) => i.name), contains('Engine oil'));
      expect(items.map((i) => i.name), contains('Drive chain'));
    });

    test('concurrent ensureMaintenance calls still seed only once', () async {
      // `GarageController.build()` re-runs when `vehicleStoreProvider` finishes
      // loading from prefs, firing a second `ensureMaintenance` for the same
      // vehicle while the first is still in flight. Without the transaction
      // both counts came back 0 and both seeded — 16 rows, permanently.
      await Future.wait([
        garageRepo.ensureMaintenance('default'),
        garageRepo.ensureMaintenance('default'),
      ]);

      expect((await garageRepo.maintenanceItems('default')).length, 8);
    });

    test('markServiceDone updates the last-done odometer', () async {
      await garageRepo.ensureMaintenance('default');
      final item = (await garageRepo.maintenanceItems('default')).first;

      await garageRepo.markServiceDone(item, 5000);

      final updated = (await garageRepo.maintenanceItems('default'))
          .firstWhere((i) => i.id == item.id);
      expect(updated.lastDoneOdoKm, 5000);
    });
  });

  group('SqliteGarageRepository — expenses', () {
    test('addExpense/deleteExpense round-trip', () async {
      final expense = await garageRepo.addExpense('Fuel', 500, 'Top-up', 1000, 'default');
      expect((await garageRepo.expenses('default')).length, 1);

      await garageRepo.deleteExpense(expense);
      expect(await garageRepo.expenses('default'), isEmpty);
    });
  });

  group('SqliteSavedLocationRepository', () {
    test('add/list/remove round-trip', () async {
      await savedRepo.add('Home', 12.9716, 77.5946);
      final saved = await savedRepo.list();
      expect(saved, hasLength(1));
      expect(saved.first.name, 'Home');

      await savedRepo.remove(saved.first);
      expect(await savedRepo.list(), isEmpty);
    });
  });
}
