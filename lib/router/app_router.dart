import 'package:flutter/foundation.dart';
import 'package:go_router/go_router.dart';
import 'package:talker_flutter/talker_flutter.dart';

import '../screens/dash_screen.dart';
import '../screens/debug/dash_debug_screen.dart';
import '../screens/expenses_screen.dart';
import '../screens/garage_screen.dart';
import '../screens/home_screen.dart';
import '../screens/offline_maps_screen.dart';
import '../models/route_preview_args.dart';
import '../screens/rides_screen.dart';
import '../screens/route_preview_screen.dart';
import '../screens/route_screen.dart';
import '../screens/settings_screen.dart';
import '../screens/vehicles_screen.dart';
import '../util/app_logger.dart';
import 'app_shell.dart';

/// Route tree: a 5-tab `StatefulShellRoute` (Home/Vehicles/Expenses/Garage/
/// More, mirroring `AppNavigation.kt`'s `bottomTabs`) plus Route/Dash/Rides
/// pushed on top of the Home branch, same relationship as the original's
/// `homeChildRoutes`.
final appRouter = GoRouter(
  initialLocation: '/home',
  routes: [
    StatefulShellRoute.indexedStack(
      builder: (context, state, navigationShell) => AppShell(navigationShell: navigationShell),
      branches: [
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: '/home',
              builder: (context, state) => const HomeScreen(),
              routes: [
                GoRoute(
                  path: 'route',
                  builder: (context, state) => const RouteScreen(),
                ),
                GoRoute(
                  path: 'route-preview',
                  builder: (context, state) => RoutePreviewScreen(args: state.extra as RoutePreviewArgs),
                ),
                GoRoute(
                  path: 'dash',
                  // The substitution lives HERE, not at the two call sites that
                  // navigate to /home/dash (the Home tile and «Поехали» on route
                  // preview). One point of substitution means a third entry point
                  // cannot miss it by forgetting — see spec/dash_screen.md.
                  builder: (context, state) =>
                      kDebugMode ? const DashDebugScreen() : const DashScreen(),
                ),
                GoRoute(
                  // Reachable directly as well, to open the debug screen without
                  // going through the substitution above.
                  path: 'dash-debug',
                  builder: (context, state) => const DashDebugScreen(),
                ),
                GoRoute(
                  path: 'rides',
                  builder: (context, state) => const RidesScreen(),
                ),
              ],
            ),
          ],
        ),
        StatefulShellBranch(routes: [
          GoRoute(path: '/vehicles', builder: (context, state) => const VehiclesScreen()),
        ]),
        StatefulShellBranch(routes: [
          GoRoute(path: '/expenses', builder: (context, state) => const ExpensesScreen()),
        ]),
        StatefulShellBranch(routes: [
          GoRoute(path: '/garage', builder: (context, state) => const GarageScreen()),
        ]),
        StatefulShellBranch(routes: [
          GoRoute(
            path: '/more',
            builder: (context, state) => const SettingsScreen(),
            routes: [
              GoRoute(
                path: 'logs',
                builder: (context, state) => TalkerScreen(talker: talker),
              ),
              GoRoute(
                path: 'offline-maps',
                builder: (context, state) => const OfflineMapsScreen(),
              ),
            ],
          ),
        ]),
      ],
    ),
  ],
);
