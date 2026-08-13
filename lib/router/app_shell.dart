import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../l10n/app_localizations.dart';
import '../state/app_update_controller.dart';
import '../util/github_release.dart';

/// Bottom nav shell — 5 tabs matching the original `AppNavigation.kt`
/// (`bottomTabs`): Home, Vehicles, Expenses, Garage, More.
class AppShell extends ConsumerStatefulWidget {
  const AppShell({
    super.key,
    required this.navigationShell,
  });

  final StatefulNavigationShell navigationShell;

  @override
  ConsumerState<AppShell> createState() => _AppShellState();
}

class _AppShellState extends ConsumerState<AppShell> {
  @override
  void initState() {
    super.initState();
    // Once per app launch, not on every tab switch — AppShell is built once
    // and persists across the bottom-nav branches. A network error here is
    // silent (see AppUpdateController.checkOnLaunch's doc); the Settings
    // screen's own "Проверить обновления" is where an explicit check reports
    // failures.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(appUpdateControllerProvider.notifier).checkOnLaunch();
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;

    ref.listen(appUpdateControllerProvider, (previous, next) {
      if (next.promptOnLaunch && next.release != null) {
        ref.read(appUpdateControllerProvider.notifier).acknowledgePrompt();
        _showUpdateDialog(context, l10n, next.release!);
      }
    });

    return Scaffold(
      body: widget.navigationShell,
      bottomNavigationBar: NavigationBar(
        selectedIndex: widget.navigationShell.currentIndex,
        onDestinationSelected: (index) => widget.navigationShell.goBranch(
          index,
          initialLocation: index == widget.navigationShell.currentIndex,
        ),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.home_outlined), selectedIcon: const Icon(Icons.home), label: l10n.navHome),
          NavigationDestination(icon: const Icon(Icons.two_wheeler_outlined), selectedIcon: const Icon(Icons.two_wheeler), label: l10n.navVehicles),
          NavigationDestination(icon: const Icon(Icons.bar_chart_outlined), selectedIcon: const Icon(Icons.bar_chart), label: l10n.navExpenses),
          NavigationDestination(icon: const Icon(Icons.build_outlined), selectedIcon: const Icon(Icons.build), label: l10n.navGarage),
          NavigationDestination(icon: const Icon(Icons.more_horiz), selectedIcon: const Icon(Icons.more_horiz), label: l10n.navMore),
        ],
      ),
    );
  }

  void _showUpdateDialog(BuildContext context, AppLocalizations l10n, AppRelease release) {
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(l10n.updateAvailableTitle(release.name)),
        content: SingleChildScrollView(
          child: Text(release.body?.trim().isNotEmpty == true ? release.body! : l10n.updateAvailableBody),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(dialogContext), child: Text(l10n.updateLater)),
          FilledButton(
            onPressed: () {
              Navigator.pop(dialogContext);
              ref.read(appUpdateControllerProvider.notifier).downloadAndInstall();
              GoRouter.of(context).go('/more');
            },
            child: Text(l10n.updateDownload),
          ),
        ],
      ),
    );
  }
}
