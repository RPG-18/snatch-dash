import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:snatch_dash/data/app_database.dart';
import 'package:snatch_dash/data/installed_packs_repository.dart';
import 'package:snatch_dash/models/offline_map.dart';

InstalledPack _pack(String code, {String sha = 'aa', String generatedAt = 'T1', int size = 10}) =>
    InstalledPack(
      code: code,
      sha256: sha,
      generatedAt: generatedAt,
      sizeBytes: size,
      installedAtMs: 1700000000000,
    );

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  });

  late SqliteInstalledPacksRepository repo;
  late String dbPath;

  setUp(() async {
    dbPath = p.join(
      Directory.systemTemp.path,
      'opendash_packs_test_${DateTime.now().microsecondsSinceEpoch}.db',
    );
    repo = SqliteInstalledPacksRepository(db: AppDatabase.forTesting(dbPath));
  });

  tearDown(() async {
    await databaseFactory.deleteDatabase(dbPath);
  });

  test('starts empty — the default state of a fresh install', () async {
    expect(await repo.list(), isEmpty);
  });

  test('put/list/remove round-trip', () async {
    await repo.put(_pack('ru-ad'));
    await repo.put(_pack('ru-len-spe', size: 118000000));

    final packs = await repo.list();
    expect(packs.map((x) => x.code), ['ru-ad', 'ru-len-spe']);
    expect(packs.last.sizeBytes, 118000000);

    await repo.remove('ru-ad');
    expect((await repo.list()).map((x) => x.code), ['ru-len-spe']);
  });

  test('re-installing a pack replaces its row instead of duplicating it', () async {
    await repo.put(_pack('ru-ad', sha: 'old', generatedAt: 'T1'));
    await repo.put(_pack('ru-ad', sha: 'new', generatedAt: 'T2', size: 999));

    final packs = await repo.list();
    expect(packs, hasLength(1));
    expect(packs.single.sha256, 'new');
    expect(packs.single.generatedAt, 'T2');
    expect(packs.single.sizeBytes, 999);
  });

  test('removing an unknown code is a no-op, not an error', () async {
    await repo.remove('ru-nope');
    expect(await repo.list(), isEmpty);
  });

  test('in-memory implementation matches the sqlite one', () async {
    final memory = InMemoryInstalledPacksRepository([_pack('ru-zab'), _pack('ru-ad')]);

    expect((await memory.list()).map((x) => x.code), ['ru-ad', 'ru-zab']);

    await memory.put(_pack('ru-ad', sha: 'new'));
    final afterUpdate = await memory.list();
    expect(afterUpdate, hasLength(2), reason: 'update replaces, not appends');
    expect(afterUpdate.firstWhere((x) => x.code == 'ru-ad').sha256, 'new');

    await memory.remove('ru-zab');
    expect((await memory.list()).map((x) => x.code), ['ru-ad']);
  });
}
