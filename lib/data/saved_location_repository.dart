import '../models/shared_location.dart';
import 'app_database.dart';

abstract class SavedLocationRepository {
  Future<List<SavedLocation>> list();
  Future<SavedLocation> add(String name, double lat, double lng);
  Future<void> remove(SavedLocation location);
}

/// `sqflite`-backed saved destinations — ports the `saved_location` table
/// from `data/OpenDashDb.kt`.
class SqliteSavedLocationRepository implements SavedLocationRepository {
  SqliteSavedLocationRepository({AppDatabase? db}) : _db = db ?? AppDatabase.instance;

  final AppDatabase _db;

  @override
  Future<List<SavedLocation>> list() async {
    final db = await _db.database;
    final rows = await db.query('saved_location', orderBy: 'id DESC');
    return rows
        .map((row) => SavedLocation(
              id: row['id'] as int,
              name: row['name'] as String,
              lat: (row['lat'] as num).toDouble(),
              lng: (row['lng'] as num).toDouble(),
            ))
        .toList();
  }

  @override
  Future<SavedLocation> add(String name, double lat, double lng) async {
    final db = await _db.database;
    final id = await db.insert('saved_location', {'name': name, 'lat': lat, 'lng': lng});
    return SavedLocation(id: id, name: name, lat: lat, lng: lng);
  }

  @override
  Future<void> remove(SavedLocation location) async {
    final db = await _db.database;
    await db.delete('saved_location', where: 'id = ?', whereArgs: [location.id]);
  }
}

/// In-memory implementation — used by widget tests, which shouldn't need a
/// real database (the `sqflite` behavior itself is covered separately by
/// `test/data/sqlite_garage_repository_test.dart`, run on the plain Dart VM).
class InMemorySavedLocationRepository implements SavedLocationRepository {
  final List<SavedLocation> _locations = [];
  int _nextId = 1;

  @override
  Future<List<SavedLocation>> list() async => List.unmodifiable(_locations);

  @override
  Future<SavedLocation> add(String name, double lat, double lng) async {
    final location = SavedLocation(id: _nextId++, name: name, lat: lat, lng: lng);
    _locations.add(location);
    return location;
  }

  @override
  Future<void> remove(SavedLocation location) async {
    _locations.removeWhere((l) => l.id == location.id);
  }
}
