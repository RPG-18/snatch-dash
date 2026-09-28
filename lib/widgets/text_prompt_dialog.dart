import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';

/// One-field prompt used by the SSID and WiFi-password dialogs.
///
/// **Exists because the controller has to outlive the pop and die with the
/// widget.** Both dialogs used to build a `TextEditingController` in the
/// caller and dispose it from `showDialog(...).whenComplete(...)`. That future
/// completes the moment the route is popped — while the dialog is still
/// mounted and still playing its exit transition — so the next rebuild reached
/// a `TextField` whose controller was already disposed:
///
/// ```
/// A TextEditingController was used after being disposed.
/// The relevant error-causing widget was: TextField
/// ```
///
/// `EditableText` re-subscribes to the controller in `didUpdateWidget`, the
/// throw wedged the update, and what the rider actually saw was the SECOND
/// failure it caused — a full red screen reading
/// `'_dependents.isEmpty': is not true`, with the first error nowhere in
/// logcat or `app_log.txt`. Reproduced on both phones 2026-09-28; found with
/// `flutter run` attached, because that is the only place the first error
/// printed.
///
/// A `State` owning the controller disposes it when the element unmounts,
/// which is after the transition — the moment that is actually safe.
class TextPromptDialog extends StatefulWidget {
  const TextPromptDialog({
    super.key,
    required this.title,
    required this.label,
    required this.initial,
    required this.onSave,
    required this.saveFailed,
    this.maxLength,
  });

  final String title;
  final String label;
  final String initial;
  final int? maxLength;

  /// Pushes the value to the engine. Awaited before the dialog closes.
  final Future<void> Function(String value) onSave;

  /// Shown when [onSave] throws; the dialog stays open so the typed value is
  /// not lost.
  final String saveFailed;

  @override
  State<TextPromptDialog> createState() => TextPromptDialogState();
}

class TextPromptDialogState extends State<TextPromptDialog> {
  late final TextEditingController _controller = TextEditingController(
    text: widget.initial,
  );
  bool _saving = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      await widget.onSave(_controller.text);
    } catch (_) {
      // The engine answers NO_ENGINE whenever its controller is between
      // lives, and nothing in this app installs a zone handler — so without
      // this the throw escaped, `_saving` stayed true, and BOTH buttons were
      // dead for good. The old dialogs at least kept Cancel alive across the
      // await; leaving the rider locked in with their typed value would be a
      // regression, not a fix. Review, 2026-09-28.
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(widget.saveFailed)));
      // Deliberately still open: the value the rider typed is here and
      // nowhere else, and a retry costs one tap.
      return;
    }
    if (!mounted) return;
    // `true` is the callers' signal to re-read the config — see the `.then`
    // in `_showSsidDialog`. Popping without it leaves the tile showing the
    // old value until something else rebuilds it.
    Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return AlertDialog(
      title: Text(widget.title),
      content: TextField(
        controller: _controller,
        maxLength: widget.maxLength,
        decoration: InputDecoration(labelText: widget.label),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.pop(context, false),
          child: Text(l10n.actionCancel),
        ),
        TextButton(
          // Disabled while the platform call is in flight: the old code left
          // the button live across that await, so a second tap queued a second
          // write and a second pop.
          onPressed: _saving ? null : _save,
          child: Text(l10n.actionSave),
        ),
      ],
    );
  }
}
