import 'package:flutter/material.dart';

import 'colors.dart';

/// Material 3 dark theme matching the original app's gold-on-black look.
ThemeData buildOpenDashTheme() {
  final colorScheme = ColorScheme.fromSeed(
    seedColor: OpenDashColors.gold,
    brightness: Brightness.dark,
  ).copyWith(
    primary: OpenDashColors.gold,
    onPrimary: OpenDashColors.onGold,
    primaryContainer: OpenDashColors.goldTint,
    secondary: OpenDashColors.goldBright,
    surface: OpenDashColors.surf1,
    surfaceContainerHigh: OpenDashColors.surf2,
    onSurface: OpenDashColors.textHi,
    onSurfaceVariant: OpenDashColors.textMid,
    error: OpenDashColors.alert,
  );

  return ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    colorScheme: colorScheme,
    scaffoldBackgroundColor: OpenDashColors.bg0,
    appBarTheme: const AppBarTheme(
      backgroundColor: OpenDashColors.bg0,
      foregroundColor: OpenDashColors.textHi,
      elevation: 0,
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: OpenDashColors.surf2,
      indicatorColor: OpenDashColors.goldTint,
      labelTextStyle: WidgetStateProperty.resolveWith((states) {
        final selected = states.contains(WidgetState.selected);
        return TextStyle(
          fontSize: 10.5,
          fontWeight: FontWeight.w600,
          color: selected ? OpenDashColors.gold : OpenDashColors.textMid,
        );
      }),
      iconTheme: WidgetStateProperty.resolveWith((states) {
        final selected = states.contains(WidgetState.selected);
        return IconThemeData(
          color: selected ? OpenDashColors.gold : OpenDashColors.textMid,
        );
      }),
    ),
    cardTheme: const CardThemeData(
      color: OpenDashColors.surf1,
      elevation: 0,
    ),
    dividerColor: OpenDashColors.line,
  );
}
