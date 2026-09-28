import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:snatch_dash/widgets/text_controller_scope.dart';

/// The holder behind the multi-field dialogs in the garage and expenses
/// screens, where the controllers used to be created in a plain method and
/// never released at all.
void main() {
  testWidgets('hands out one seeded controller per named field', (
    tester,
  ) async {
    late Map<String, TextEditingController> seen;
    await tester.pumpWidget(
      MaterialApp(
        home: TextControllerScope(
          initial: const {'name': 'first', 'note': '', 'interval': '1000'},
          builder: (context, f) {
            seen = f;
            return const SizedBox();
          },
        ),
      ),
    );

    expect(seen.keys, ['name', 'note', 'interval']);
    expect(seen['name']!.text, 'first');
    expect(seen['note']!.text, '');
    expect(seen['interval']!.text, '1000');
  });

  testWidgets('disposes every controller when the subtree goes', (
    tester,
  ) async {
    // The one thing this widget exists for, and the first version of this
    // file did not check it: deleting the dispose loop kept all the other
    // tests green. A disposed ChangeNotifier throws on addListener, which is
    // how disposal becomes observable from outside.
    late Map<String, TextEditingController> seen;
    await tester.pumpWidget(
      MaterialApp(
        home: TextControllerScope(
          initial: const {'a': '', 'b': ''},
          builder: (context, f) {
            seen = f;
            return const SizedBox();
          },
        ),
      ),
    );

    await tester.pumpWidget(const SizedBox());

    for (final entry in seen.entries) {
      expect(
        () => entry.value.addListener(() {}),
        throwsFlutterError,
        reason: '${entry.key} was left alive',
      );
    }
  });

  testWidgets('the same controllers survive a rebuild', (tester) async {
    // The expenses dialog rebuilds through a StatefulBuilder every time the
    // category dropdown changes. New controllers there would clear the
    // amount and note the rider had already entered.
    late StateSetter rebuild;
    final captured = <Map<String, TextEditingController>>[];
    await tester.pumpWidget(
      MaterialApp(
        home: StatefulBuilder(
          builder: (context, setState) {
            rebuild = setState;
            return TextControllerScope(
              initial: const {'amount': '', 'note': ''},
              builder: (context, f) {
                captured.add(f);
                return const SizedBox();
              },
            );
          },
        ),
      ),
    );

    captured.first['amount']!.text = 'typed';
    rebuild(() {});
    await tester.pump();

    expect(captured, hasLength(2));
    expect(identical(captured[0], captured[1]), isTrue, reason: 'same map');
    expect(
      captured.last['amount']!.text,
      'typed',
      reason: 'and the text is still there',
    );
  });

  testWidgets('an empty scope is legal', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: TextControllerScope(
          initial: const {},
          builder: (context, f) => Text('${f.length}'),
        ),
      ),
    );
    expect(find.text('0'), findsOneWidget);
  });
}
