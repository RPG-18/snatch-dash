import 'package:flutter/material.dart';

/// Owns a set of [TextEditingController]s for the life of its subtree.
///
/// **Why this exists.** A dialog built from a plain method — `showDialog(...)`
/// inside `_showAddFuel(...)` — has nowhere to put a `dispose()`, so the
/// controllers it creates in that method are simply never released. Eight of
/// them were leaking that way across the garage and expenses screens: every
/// opening of the dialog added another `ChangeNotifier` with its listener list
/// and nothing ever took them back.
///
/// The obvious fix is to make each dialog a `StatefulWidget`, and for a
/// one-field prompt that is what `TextPromptDialog` does. These dialogs have
/// four and five fields and a lot of layout, so wrapping the builder is the
/// smaller change for the same guarantee: a `State` owns the controllers and
/// the framework disposes them when the element unmounts.
///
/// **Unmount, not pop** — that distinction is the whole point. Disposing from
/// `showDialog(...).whenComplete(...)` fires at pop, while the route is still
/// mounted and playing its exit transition, and the next rebuild reaches a
/// `TextField` whose controller is already dead. That is the crash this
/// screen's sibling shipped with until 2026-09-28; see `TextPromptDialog`.
class TextControllerScope extends StatefulWidget {
  const TextControllerScope({
    required this.initial,
    required this.builder,
    super.key,
  });

  /// One entry per field: name to initial text. Use an empty string for a
  /// field that starts blank.
  ///
  /// **Named, not positional.** An earlier draft handed the builder a `List`
  /// indexed by position, where a call site that added a field without
  /// extending the list got a `RangeError` on opening the dialog, and one
  /// that reordered the seeds silently put the rider's litres in the cost
  /// box. Neither the analyzer nor a test sees either. A map cannot be
  /// misordered, and a missing key fails by name.
  final Map<String, String> initial;

  final Widget Function(
    BuildContext context,
    Map<String, TextEditingController> fields,
  )
  builder;

  @override
  State<TextControllerScope> createState() => _TextControllerScopeState();
}

class _TextControllerScopeState extends State<TextControllerScope> {
  late final Map<String, TextEditingController> _controllers = {
    for (final e in widget.initial.entries)
      e.key: TextEditingController(text: e.value),
  };

  @override
  void dispose() {
    for (final c in _controllers.values) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.builder(context, _controllers);
}
