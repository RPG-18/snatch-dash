import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../l10n/app_localizations.dart';
import '../map/route_options_map.dart';
import '../models/route_preview_args.dart';
import '../models/shared_location.dart';
import '../nav/route.dart' as nav;
import '../nav/router.dart' as nav;
import '../state/route_controller.dart';
import '../util/current_position.dart';

const _routesCount = 3;

enum _LoadError { noGpsFix, routingFailed }

/// Route preview — reachable from [RouteScreen] after picking a destination.
/// Shows every alternative driving route on the map (selected one green,
/// the rest purple) with a horizontal picker of duration/distance cards
/// below it, defaulting to the fastest route; "Поехали" hands the pick off
/// to [RouteController] and opens the dash screen.
class RoutePreviewScreen extends ConsumerStatefulWidget {
  const RoutePreviewScreen({super.key, required this.args});

  final RoutePreviewArgs args;

  @override
  ConsumerState<RoutePreviewScreen> createState() => _RoutePreviewScreenState();
}

class _RoutePreviewScreenState extends ConsumerState<RoutePreviewScreen> {
  bool _loading = true;
  _LoadError? _error;
  List<nav.Route> _routes = const [];
  int _selectedIndex = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final origin = widget.args.origin ?? await currentPosition();
    if (!mounted) return;
    if (origin == null) {
      setState(() {
        _loading = false;
        _error = _LoadError.noGpsFix;
      });
      return;
    }

    final routes = await nav.Router.routes(origin, widget.args.destination, routesCount: _routesCount);
    if (!mounted) return;
    if (routes == null || routes.isEmpty) {
      setState(() {
        _loading = false;
        _error = _LoadError.routingFailed;
      });
      return;
    }

    var fastest = 0;
    for (var i = 1; i < routes.length; i++) {
      if (routes[i].totalSeconds < routes[fastest].totalSeconds) fastest = i;
    }
    setState(() {
      _loading = false;
      _error = null;
      _routes = routes;
      _selectedIndex = fastest;
    });
  }

  void _selectRoute(int index) => setState(() => _selectedIndex = index);

  Future<void> _go() async {
    if (_routes.isEmpty) return;
    final notifier = ref.read(routeControllerProvider.notifier);
    notifier.selectRoute(
      SharedLocation(
        name: widget.args.destinationName,
        lat: widget.args.destination.lat,
        lng: widget.args.destination.lng,
      ),
      _routes[_selectedIndex],
    );
    await notifier.sendToDash();
    if (!mounted) return;
    context.go('/home/dash');
  }

  String _formatDuration(AppLocalizations l10n, double seconds) {
    final totalMinutes = (seconds / 60).round();
    final days = totalMinutes ~/ (24 * 60);
    final hours = (totalMinutes % (24 * 60)) ~/ 60;
    final minutes = totalMinutes % 60;
    if (days > 0) {
      return l10n.unitDayHourMin(days.toString(), hours.toString(), minutes.toString());
    }
    if (hours > 0) {
      return l10n.unitHourMin(hours.toString(), minutes.toString());
    }
    return l10n.unitMin(minutes.toString());
  }

  String _formatDistance(AppLocalizations l10n, double meters) {
    return meters >= 1000
        ? l10n.unitKm((meters / 1000).toStringAsFixed(1))
        : l10n.unitM(meters.round().toString());
  }

  String _errorText(AppLocalizations l10n) {
    return switch (_error!) {
      _LoadError.noGpsFix => l10n.routeErrorNoGpsFix,
      _LoadError.routingFailed => l10n.routeErrorRoutingFailedConnection,
    };
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;

    return Scaffold(
      appBar: AppBar(title: Text(l10n.routePreviewTitle)),
      body: Column(
        children: [
          // Only the map gets a flex share of the height — the cards row and
          // button below size to their own content instead of a fixed
          // fraction of screen height, which used to overflow (card text
          // needing more room than its slice) on shorter screens or with
          // larger system font scaling.
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : _error != null
                ? Center(child: Text(_errorText(l10n), textAlign: TextAlign.center))
                : RouteOptionsMap(
                    routes: _routes,
                    selectedIndex: _selectedIndex,
                    onRouteTap: _selectRoute,
                    origin: widget.args.origin,
                    destination: widget.args.destination,
                  ),
          ),
          if (_routes.isNotEmpty)
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              child: Row(
                children: [
                  for (var i = 0; i < _routes.length; i++) ...[
                    if (i > 0) const SizedBox(width: 12),
                    _RouteCard(
                      selected: i == _selectedIndex,
                      duration: _formatDuration(l10n, _routes[i].totalSeconds),
                      distance: _formatDistance(l10n, _routes[i].totalMeters),
                      onTap: () => _selectRoute(i),
                    ),
                  ],
                ],
              ),
            ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            child: SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: _routes.isEmpty ? null : _go,
                child: Text(l10n.routePreviewGo),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _RouteCard extends StatelessWidget {
  const _RouteCard({
    required this.selected,
    required this.duration,
    required this.distance,
    required this.onTap,
  });

  final bool selected;
  final String duration;
  final String distance;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        width: 140,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          // Width stays fixed across selection — varying it (as the Material
          // outlined-vs-selected convention usually does) shrinks the text
          // area by a couple of px, enough to flip the longest duration
          // string (day, hour, min) onto a second line and change the
          // card's height depending on which one is selected.
          border: Border.all(
            color: selected ? theme.colorScheme.primary : theme.colorScheme.outlineVariant,
            width: 2,
          ),
          color: selected ? theme.colorScheme.primaryContainer : null,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(duration, style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
            const SizedBox(height: 4),
            Text(distance, style: theme.textTheme.bodyMedium),
          ],
        ),
      ),
    );
  }
}
