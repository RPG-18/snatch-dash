import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

/// Local SQLite store — ports the table shapes from the original app's
/// `data/OpenDashDb.kt` (a hand-rolled `SQLiteOpenHelper`, no Room there
/// either). Dropped vs. the original: the `sid` UUID column on every table,
/// which existed only to key Firestore documents for cloud sync — this port
/// has no cloud sync (see the migration plan's Firebase decision), so plain
/// autoincrement integer primary keys are the source of truth here.
class AppDatabase {
  AppDatabase._([this._testPath]);
  static final AppDatabase instance = AppDatabase._();

  /// A fresh, independent instance pointed at an isolated (e.g. in-memory)
  /// database file — for repository tests, which must not share state with
  /// the app-wide [instance] singleton or each other.
  factory AppDatabase.forTesting(String path) => AppDatabase._(path);

  static const _dbName = 'opendash.db';

  /// v2 adds `installed_pack` (offline map registry).
  static const _dbVersion = 2;

  final String? _testPath;

  /// Cached as the pending *future*, not the resolved handle: `_db ??= await
  /// _open()` re-entered `_open()` for every caller that arrived during the
  /// await, and on a cold start the garage, rides and saved-destination loads
  /// all hit this at once — so the database was opened several times over.
  Future<Database>? _opening;

  Future<Database> get database async {
    final opening = _opening ??= _open();
    try {
      return await opening;
    } catch (_) {
      // Never cache a failed open — the next caller should get a fresh attempt.
      if (identical(_opening, opening)) _opening = null;
      rethrow;
    }
  }

  Future<Database> _open() async {
    final path = _testPath ?? p.join(await getDatabasesPath(), _dbName);
    return openDatabase(
      path,
      version: _dbVersion,
      onCreate: (db, version) async {
        await db.execute('''
          CREATE TABLE fuel_fillup (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            date_ms INTEGER NOT NULL,
            litres REAL NOT NULL,
            cost REAL NOT NULL,
            odometer_km INTEGER NOT NULL,
            location TEXT NOT NULL DEFAULT '',
            vehicle_id TEXT NOT NULL
          )
        ''');
        await db.execute('''
          CREATE TABLE expense (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            date_ms INTEGER NOT NULL,
            category TEXT NOT NULL,
            amount REAL NOT NULL,
            note TEXT NOT NULL DEFAULT '',
            vehicle_id TEXT NOT NULL
          )
        ''');
        await db.execute('''
          CREATE TABLE maintenance_item (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            icon_key TEXT NOT NULL,
            interval_km INTEGER NOT NULL,
            last_done_odo_km INTEGER NOT NULL,
            last_done_date_ms INTEGER NOT NULL,
            vehicle_id TEXT NOT NULL
          )
        ''');
        await db.execute('''
          CREATE TABLE vehicle_state (
            vehicle_id TEXT PRIMARY KEY,
            odometer_km INTEGER NOT NULL
          )
        ''');
        await db.execute('''
          CREATE TABLE saved_location (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            lat REAL NOT NULL,
            lng REAL NOT NULL
          )
        ''');
        await db.execute(_createInstalledPack);
        await db.execute('''
          CREATE TABLE ride (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            start_ms INTEGER NOT NULL,
            end_ms INTEGER NOT NULL,
            distance_meters REAL NOT NULL,
            duration_sec INTEGER NOT NULL,
            avg_speed_mps REAL NOT NULL,
            max_speed_mps REAL NOT NULL,
            track_polyline TEXT NOT NULL DEFAULT '',
            start_lat REAL NOT NULL DEFAULT 0,
            start_lng REAL NOT NULL DEFAULT 0,
            end_lat REAL NOT NULL DEFAULT 0,
            end_lng REAL NOT NULL DEFAULT 0
          )
        ''');
      },
      onUpgrade: (db, oldVersion, newVersion) async {
        if (oldVersion < 2) await db.execute(_createInstalledPack);
      },
    );
  }

  /// Registry of verified, installed tile packs. `code` is the primary key:
  /// one pack per code, and re-installing an updated pack replaces the row.
  ///
  /// This is the truth the *interface* reads. The native engine has its own —
  /// it enumerates `maps/*.pmtiles` at stream start — and the two cannot
  /// disagree, because a file gets its `.pmtiles` name in the same atomic move
  /// that writes the row here (spec/drawing_from_local_tiles.md).
  static const _createInstalledPack = '''
    CREATE TABLE installed_pack (
      code TEXT PRIMARY KEY,
      sha256 TEXT NOT NULL,
      generated_at TEXT NOT NULL,
      size_bytes INTEGER NOT NULL,
      installed_at_ms INTEGER NOT NULL
    )
  ''';

  /// Test/dev helper — closes and clears the cached handle so a fresh
  /// `database` re-opens (used by repository tests with an in-memory DB).
  Future<void> close() async {
    final opening = _opening;
    _opening = null;
    if (opening != null) await (await opening).close();
  }
}
