import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../l10n/app_localizations.dart';
import '../map/opendash_map.dart';
import '../models/route_preview_args.dart';
import '../nav/geo_point.dart';
import '../nav/place.dart';
import '../nav/suggest_result.dart';
import '../state/route_search_controller.dart';
import '../state/saved_destinations_controller.dart';

/// Destination search screen. Live suggestions via the Yandex Geosuggest
/// HTTP API (`SuggestApi`), from 3 characters. The suggest endpoint returns
/// no coordinates, so picking a suggestion resolves it on-device
/// (`PlaceSearch.search`, via `RouteSearchController.resolve`) — if that
/// turns up several places sharing the same name (e.g. a chain store), a
/// bottom sheet lets the rider pick the exact one before opening
/// `RoutePreviewScreen`.
class RouteScreen extends ConsumerStatefulWidget {
  const RouteScreen({super.key});

  @override
  ConsumerState<RouteScreen> createState() => _RouteScreenState();
}

class _RouteScreenState extends ConsumerState<RouteScreen> {
  final _controller = TextEditingController();

  // Non-null switches the body from the search state to the destination
  // preview state — a stack transition kept as local widget state rather
  // than a pushed route, per the screen's spec.
  PlaceResult? _preview;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _selectResult(SuggestResult item, GeoPoint? origin) async {
    final notifier = ref.read(routeSearchControllerProvider.notifier);
    final l10n = AppLocalizations.of(context)!;

    final candidates = await notifier.resolve(item);
    if (!mounted) return;
    if (candidates.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l10n.routeResolveError)));
      return;
    }

    final chosen = candidates.length == 1 ? candidates.first : await _pickCandidate(candidates, l10n);
    if (chosen == null || !mounted) return; // dismissed the picker without choosing

    setState(() {
      _preview = PlaceResult(
        name: chosen.name.isEmpty ? item.title : chosen.name,
        address: chosen.address,
        point: chosen.point,
        distanceMeters: chosen.distanceMeters,
      );
    });
  }

  void _closePreview() => setState(() => _preview = null);

  void _goToRoutePreview(GeoPoint? origin) {
    final preview = _preview;
    if (preview == null) return;
    context.push(
      '/home/route-preview',
      extra: RoutePreviewArgs(
        destinationName: preview.name,
        destination: preview.point,
        origin: origin,
      ),
    );
  }

  Future<void> _saveDestination() async {
    final preview = _preview;
    if (preview == null) return;
    final l10n = AppLocalizations.of(context)!;
    await ref
        .read(savedDestinationsControllerProvider.notifier)
        .save(preview.name, preview.point.lat, preview.point.lng);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l10n.routeDestinationSaved)));
  }

  Future<PlaceResult?> _pickCandidate(List<PlaceResult> candidates, AppLocalizations l10n) {
    return showModalBottomSheet<PlaceResult>(
      context: context,
      showDragHandle: true,
      builder: (sheetContext) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Text(l10n.routeResolvePickTitle, style: Theme.of(sheetContext).textTheme.titleMedium),
            ),
            for (final candidate in candidates)
              ListTile(
                title: Text(candidate.name, style: const TextStyle(fontWeight: FontWeight.bold)),
                subtitle: candidate.address.isEmpty ? null : Text(candidate.address),
                trailing: candidate.distanceMeters == null
                    ? null
                    : Text(_formatDistance(l10n, candidate.distanceMeters!)),
                onTap: () => Navigator.of(sheetContext).pop(candidate),
              ),
          ],
        ),
      ),
    );
  }

  String _formatDistance(AppLocalizations l10n, double meters) {
    return meters >= 1000
        ? l10n.unitKm((meters / 1000).toStringAsFixed(1))
        : l10n.unitM(meters.round().toString());
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(routeSearchControllerProvider);
    final notifier = ref.read(routeSearchControllerProvider.notifier);
    final l10n = AppLocalizations.of(context)!;
    final preview = _preview;

    return Scaffold(
      appBar: AppBar(title: Text(l10n.routeTitle)),
      body: preview != null
          ? _buildPreview(l10n, preview, state.origin)
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: TextField(
                    controller: _controller,
                    autofocus: true,
                    decoration: InputDecoration(
                      hintText: l10n.routeSearchHint,
                      prefixIcon: const Icon(Icons.search),
                      border: const OutlineInputBorder(),
                    ),
                    onChanged: notifier.setQuery,
                  ),
                ),
                if (state.searching || state.resolving) const LinearProgressIndicator(),
                Expanded(
                  child: AbsorbPointer(
                    absorbing: state.resolving,
                    child: _buildBody(context, l10n, state),
                  ),
                ),
              ],
            ),
    );
  }

  Widget _buildPreview(AppLocalizations l10n, PlaceResult preview, GeoPoint? origin) {
    return Stack(
      children: [
        Positioned.fill(child: OpenDashMap(dest: preview.point)),
        Align(
          alignment: Alignment.bottomCenter,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(preview.name, style: const TextStyle(fontWeight: FontWeight.bold)),
                              if (preview.address.isNotEmpty) Text(preview.address),
                            ],
                          ),
                        ),
                        IconButton(
                          tooltip: l10n.routeDestinationCloseTooltip,
                          icon: const Icon(Icons.close),
                          onPressed: _closePreview,
                        ),
                      ],
                    ),
                    Row(
                      children: [
                        Expanded(
                          child: FilledButton(
                            onPressed: () => _goToRoutePreview(origin),
                            child: Text(l10n.routeDestinationGo),
                          ),
                        ),
                        IconButton(
                          tooltip: l10n.routeSaveDestinationTooltip,
                          icon: const Icon(Icons.bookmark_border),
                          onPressed: _saveDestination,
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildBody(BuildContext context, AppLocalizations l10n, RouteSearchState state) {
    if (state.error) {
      return Center(child: Text(l10n.routeSearchError));
    }
    if (state.results.isEmpty) {
      final prompt = state.query.trim().length < 3 ? l10n.routeSearchPrompt : l10n.routeSearchNoResults;
      return Center(child: Text(prompt, textAlign: TextAlign.center));
    }
    return ListView.separated(
      itemCount: state.results.length,
      separatorBuilder: (_, _) => const Divider(height: 1),
      itemBuilder: (context, i) {
        final result = state.results[i];
        return ListTile(
          title: Text(result.title, style: const TextStyle(fontWeight: FontWeight.bold)),
          subtitle: result.subtitle.isEmpty ? null : Text(result.subtitle),
          trailing: result.distanceMeters == null ? null : Text(_formatDistance(l10n, result.distanceMeters!)),
          onTap: () => _selectResult(result, state.origin),
        );
      },
    );
  }
}
