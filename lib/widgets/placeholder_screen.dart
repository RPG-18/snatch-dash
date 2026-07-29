import 'package:flutter/material.dart';

/// Scaffolding stand-in for a screen not yet ported (see the phased plan in
/// the project's migration notes). Shows the title + a note on what still
/// needs building, so the nav shell is fully clickable during Phase 0.
class PlaceholderScreen extends StatelessWidget {
  const PlaceholderScreen({
    super.key,
    required this.title,
    required this.note,
    this.leading,
  });

  final String title;
  final String note;
  final Widget? leading;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title), leading: leading),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            note,
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ),
      ),
    );
  }
}
