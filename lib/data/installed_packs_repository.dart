import 'package:sqflite/sqflite.dart' show ConflictAlgorithm;

import '../models/offline_map.dart';
import 'app_database.dart';

abstract class InstalledPacksRepository {
  Future<List<InstalledPack>> list();

  /// Records a pack as installed, replacing any earlier row for the same code
  /// (that is what a pack *update* is).
  Future<void> put(InstalledPack pack);

  Future<void> remove(String code);
}

/// `sqflite`-backed registry of installed tile packs — the `installed_pack`
/// table added in database v2.
class SqliteInstalledPacksRepository implements InstalledPacksRepository {
  SqliteInstalledPacksRepository({AppDatabase? db}) : _db = db ?? AppDatabase.instance;

  final AppDatabase _db;

  @override
  Future<List<InstalledPack>> list() async {
    final db = await _db.database;
    final rows = await db.query('installed_pack', orderBy: 'code');
    return rows.map(_fromRow).toList();
  }

  @override
  Future<void> put(InstalledPack pack) async {
    final db = await _db.database;
    await db.insert(
      'installed_pack',
      {
        'code': pack.code,
        'sha256': pack.sha256,
        'generated_at': pack.generatedAt,
        'size_bytes': pack.sizeBytes,
        'installed_at_ms': pack.installedAtMs,
      },
      // Same code = same pack; an update overwrites rather than duplicates.
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  @override
  Future<void> remove(String code) async {
    final db = await _db.database;
    await db.delete('installed_pack', where: 'code = ?', whereArgs: [code]);
  }

  static InstalledPack _fromRow(Map<String, Object?> row) => InstalledPack(
        code: row['code'] as String,
        sha256: row['sha256'] as String,
        generatedAt: row['generated_at'] as String,
        sizeBytes: (row['size_bytes'] as num).toInt(),
        installedAtMs: (row['installed_at_ms'] as num).toInt(),
      );
}

/// In-memory implementation for widget tests, which have no sqflite binding —
/// mirrors [SqliteSavedLocationRepository]'s companion in
/// `saved_location_repository.dart`.
class InMemoryInstalledPacksRepository implements InstalledPacksRepository {
  InMemoryInstalledPacksRepository([List<InstalledPack> seed = const []]) {
    for (final pack in seed) {
      _packs[pack.code] = pack;
    }
  }

  final Map<String, InstalledPack> _packs = {};

  @override
  Future<List<InstalledPack>> list() async {
    final packs = _packs.values.toList()..sort((a, b) => a.code.compareTo(b.code));
    return packs;
  }

  @override
  Future<void> put(InstalledPack pack) async => _packs[pack.code] = pack;

  @override
  Future<void> remove(String code) async => _packs.remove(code);
}
