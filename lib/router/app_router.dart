import 'package:go_router/go_router.dart';
import 'package:talker_flutter/talker_flutter.dart';

import '../screens/dash_screen.dart';
import '../screens/expenses_screen.dart';
import '../screens/garage_screen.dart';
import '../screens/home_screen.dart';
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
                  builder: (context, state) => const DashScreen(),
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
            ],
          ),
        ]),
      ],
    ),
  ],
);
