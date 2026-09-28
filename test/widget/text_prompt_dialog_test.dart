import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:snatch_dash/l10n/app_localizations.dart';
import 'package:snatch_dash/widgets/text_prompt_dialog.dart';

/// The one-field dialog behind "set SSID" and "set WiFi password".
///
/// **What is NOT covered, and why it matters.** The bug this dialog was
/// extracted for — a `TextEditingController` disposed from
/// `showDialog(...).whenComplete(...)`, i.e. at pop, while the route is still
/// playing its exit transition — could not be reproduced in a widget test.
/// Two attempts are in the history of this file; both passed against the
/// broken shape. The evidence is from the device instead: `flutter run`
/// attached on 2026-09-28 named it exactly —
/// `A TextEditingController was used after being disposed`, error-causing
/// widget `TextField` at `settings_screen.dart:437`. So treat what is below
/// as a description of the dialog, not as a guard on that defect; the guard
/// is that the controller now belongs to a `State`, which the framework
/// disposes after unmount by construction.
///
/// Mutation-checked 2026-09-28: dropping the `true` from `Navigator.pop` and
/// skipping the `_saving` reset on failure both fail here. **Deleting
/// `_controller.dispose()` does not** — a leaked controller is invisible to a
/// widget test, which is precisely why `garage_screen.dart` and
/// `expenses_screen.dart` have leaked theirs unnoticed all along.
void main() {
  /// Opens [dialog] and hands back the future `showDialog` returns, so a test
  /// can assert on the popped value.
  ///
  /// Returning the RESULT instead of the future was the first version's
  /// mistake: `result` was read before the dialog had closed, so it was always
  /// null and the `true`/`false` contract the Settings tile depends on was
  /// asserted nowhere — dropping the `true` from `Navigator.pop` kept every
  /// test green while the tile silently stopped refreshing. Review, 2026-09-28.
  Future<Future<bool?>> open(
    WidgetTester tester, {
    required Widget dialog,
  }) async {
    late Future<bool?> result;
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () {
                result = showDialog<bool>(
                  context: context,
                  builder: (_) => dialog,
                );
              },
              child: const Text('open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    return result;
  }

  testWidgets('seeds the field and reports a save', (tester) async {
    var saved = '';
    final result = await open(
      tester,
      dialog: TextPromptDialog(
        title: 'title',
        label: 'label',
        saveFailed: 'could not save',
        initial: 'RE_OLD',
        onSave: (v) async => saved = v,
      ),
    );
    expect(find.text('RE_OLD'), findsOneWidget, reason: 'seeded from initial');

    await tester.enterText(find.byType(TextField), 'RE_NEW');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    expect(saved, 'RE_NEW');
    expect(find.byType(TextField), findsNothing, reason: 'the dialog closed');
    // The callers key their config reload on this. Without it the tile keeps
    // showing the old SSID until something else rebuilds it.
    expect(await result, isTrue, reason: 'a save must pop true');
  });

  testWidgets('cancel does not call onSave, and pops false', (tester) async {
    var calls = 0;
    final result = await open(
      tester,
      dialog: TextPromptDialog(
        title: 'title',
        label: 'label',
        saveFailed: 'could not save',
        initial: 'RE_OLD',
        onSave: (_) async => calls++,
      ),
    );
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(calls, 0);
    expect(await result, isFalse, reason: 'cancel must not trigger a reload');
  });

  testWidgets('a failing save leaves the dialog open and usable', (
    tester,
  ) async {
    // The engine answers NO_ENGINE while its controller is between lives. The
    // first version of the fix left `_saving` true forever here, disabling
    // BOTH buttons — the rider was locked in with the value they had typed.
    await open(
      tester,
      dialog: TextPromptDialog(
        title: 'title',
        label: 'label',
        saveFailed: 'could not save',
        initial: 'RE_OLD',
        onSave: (_) async => throw Exception('NO_ENGINE'),
      ),
    );

    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    expect(find.byType(TextField), findsOneWidget, reason: 'still open');
    expect(find.text('RE_OLD'), findsOneWidget, reason: 'value not lost');
    expect(
      find.text('could not save'),
      findsOneWidget,
      reason: 'and it said so',
    );
    expect(
      tester
          .widget<TextButton>(
            find.ancestor(
              of: find.text('Save'),
              matching: find.byType(TextButton),
            ),
          )
          .onPressed,
      isNotNull,
      reason: 'a retry must be one tap away',
    );
  });

  testWidgets('the save button is dead while the platform call is in flight', (
    tester,
  ) async {
    // The old dialogs left it live across the await, so a second tap queued a
    // second write and a second pop.
    var calls = 0;
    final gate = Completer<void>();
    await open(
      tester,
      dialog: TextPromptDialog(
        title: 'title',
        label: 'label',
        saveFailed: 'could not save',
        initial: '',
        onSave: (_) async {
          calls++;
          await gate.future;
        },
      ),
    );
    await tester.tap(find.text('Save'));
    await tester.pump();
    await tester.tap(find.text('Save'), warnIfMissed: false);
    await tester.pump();
    expect(calls, 1);
    gate.complete();
    await tester.pumpAndSettle();
  });
}
