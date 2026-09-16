import 'dart:async';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

import '../dash_screen.dart';

/// The frame that is actually going to the dash, blown up to fit, plus the
/// numbers behind it.
///
/// See `spec/dash_screen_debug.md`. Four things here are decisions, not
/// incidentals:
///
/// - **It is a bench instrument, not a ride screen.** The whole app exists so the
///   phone can ride with its display off; drawing the frame here is work that
///   does not happen on a ride, so nothing measured with this open measures the
///   ride.
/// - **Subscribing is what turns frame production on.** `frameStream` is inert
///   until listened to, so the cost lands only while this screen is mounted.
/// - **It does not replace the real dash screen, it stands in front of it.** In a
///   debug build `/home/dash` lands here, and every control a ride needs —
///   disconnect, exit navigation, answering a call — lives on `DashScreen`. The
///   button in the app bar is the way back to them, and it is not optional
///   garnish: without it a debug build cannot end a session.
/// - **The frame is enlarged, and says by how much.** It used to be drawn pixel
///   for pixel, because an interpolated copy cannot answer a question about
///   sharpness. That was reversed on 2026-09-16 for readability — 526x300 on a
///   2.75x screen is a matchbox — and the two things that keep the enlargement
///   honest are `FilterQuality.none` and the scale in the caption below it.
class DashDebugScreen extends StatefulWidget {
  const DashDebugScreen({super.key});

  @override
  State<DashDebugScreen> createState() => _DashDebugScreenState();
}

class _DashDebugScreenState extends State<DashDebugScreen> {
  /// Frames stop arriving when the link drops, and a stream that merely goes
  /// quiet never tells its listener so. Without this the screen would sit on the
  /// last frame and frozen counters looking exactly like a live feed — the one
  /// thing the spec says it must never do.
  static const _staleAfter = Duration(seconds: 3);

  StreamSubscription<Map<String, dynamic>>? _sub;
  Timer? _staleTimer;

  ui.Image? _frame;
  Map<String, dynamic>? _data;
  DateTime? _lastFrameAt;

  @override
  void initState() {
    super.initState();
    _sub = DashEngine.instance.frameStream.listen(_onFrame);
    _staleTimer = Timer.periodic(const Duration(seconds: 1), (_) => setState(() {}));
  }

  Future<void> _onFrame(Map<String, dynamic> data) async {
    final png = data['png'] as Uint8List?;
    if (png == null) return;
    // Decoded by hand rather than with Image.memory: every frame is a fresh
    // byte list, so every frame would be a distinct ImageCache key — at 2-4 fps
    // that is megabytes a second of decoded bitmaps piling up in a cache that
    // will never see a hit. Decoding here means exactly one image is alive, and
    // the previous one is disposed the moment it is replaced.
    final decoded = await ui.instantiateImageCodec(png);
    final frame = await decoded.getNextFrame();
    if (!mounted) {
      frame.image.dispose();
      return;
    }
    setState(() {
      _frame?.dispose();
      _frame = frame.image;
      _data = data;
      _lastFrameAt = DateTime.now();
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    _staleTimer?.cancel();
    _frame?.dispose();
    super.dispose();
  }

  bool get _stale {
    final at = _lastFrameAt;
    return at == null || DateTime.now().difference(at) > _staleAfter;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Dash debug'),
        actions: [
          IconButton(
            tooltip: 'Обычный экран Dash',
            icon: const Icon(Icons.dashboard_outlined),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute<void>(builder: (_) => const DashScreen()),
            ),
          ),
        ],
      ),
      body: _frame == null
          ? const Center(child: Text('Нет кадров — стрим не запущен'))
          : SingleChildScrollView(
              // Vertical, and scrolling because the two panes below are now
              // stacked rather than side by side: on a short screen the
              // telemetry is what goes under the fold, and it is the half that
              // can be scrolled to without losing sight of the frame.
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _FramePane(frame: _frame!, stale: _stale),
                  const SizedBox(height: 16),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    child: _Telemetry(data: _data ?? const {}, stale: _stale),
                  ),
                  const SizedBox(height: 16),
                ],
              ),
            ),
    );
  }
}

class _FramePane extends StatelessWidget {
  const _FramePane({required this.frame, required this.stale});

  final ui.Image frame;
  final bool stale;

  @override
  Widget build(BuildContext context) {
    // Full width, and the height follows the frame's own ratio rather than a
    // constant: 526x300 is what the dash gets, and a distorted copy would
    // misrepresent what fits inside the panel's frame.
    //
    // No devicePixelRatio arithmetic here any more, and that is worth saying
    // because it used to be the whole point of this method: while the frame was
    // drawn pixel for pixel the width had to be `frame.width / dpr`, or Flutter
    // — which measures in logical pixels — would paint it magnified by the
    // density. That formula comes back the day a 1:1 mode does.
    final dpr = MediaQuery.devicePixelRatioOf(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        // What the enlargement actually is: screen pixels per frame pixel.
        // Printed rather than assumed: the caption is the only thing standing
        // between an enlarged picture and someone reading sharpness off it.
        final scale = constraints.maxWidth * dpr / frame.width;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Opacity(
              opacity: stale ? 0.35 : 1,
              child: AspectRatio(
                aspectRatio: frame.width / frame.height,
                child: RawImage(
                  image: frame,
                  fit: BoxFit.fill,
                  // Nearest neighbour, deliberately. Bilinear smoothing would
                  // blur exactly the blockiness this screen exists to show and
                  // would flatter the encoder; blunt square pixels are the only
                  // enlargement that invents nothing.
                  filterQuality: FilterQuality.none,
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 4, left: 12, right: 12),
              child: Text(
                stale
                    ? 'Кадров нет — показан последний, приглушён'
                    : '${frame.width}×${frame.height}, '
                        '×${scale.toStringAsFixed(1)} — увеличено, '
                        'по резкости не судить',
                style: const TextStyle(fontSize: 11),
              ),
            ),
          ],
        );
      },
    );
  }
}

class _Telemetry extends StatelessWidget {
  const _Telemetry({required this.data, required this.stale});

  final Map<String, dynamic> data;
  final bool stale;

  @override
  Widget build(BuildContext context) {
    final zoom = (data['zoom'] as num?)?.toDouble();
    final scale = (data['renderScale'] as num?)?.toDouble();
    final rows = <(String, String)>[
      ('Зум', zoom == null ? '—' : zoom.toStringAsFixed(2)),
      ('Рендер', scale == null ? '—' : '×$scale'),
      ('FPS', '${data['fps'] ?? '—'}'),
      ('Кадр после H.264', '${data['encodedBytes'] ?? '—'} Б'),
      ('Отправлено', '${data['framesSent'] ?? '—'}'),
      ('Открытий декодера', '${data['decoderOpens'] ?? '—'}'),
    ];
    return Opacity(
      opacity: stale ? 0.35 : 1,
      child: DefaultTextStyle.merge(
        style: const TextStyle(fontSize: 13),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final (label, value) in rows)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 3),
                child: SizedBox(
                  width: 260,
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [Text(label), Text(value)],
                  ),
                ),
              ),
            const SizedBox(height: 8),
            // The label above says "decoder opens" and this says why: the obvious
            // reading of that number is wrong and has cost two investigations
            // already (spec/video.md).
            const SizedBox(
              width: 260,
              child: Text(
                'Открытий декодера — разовый сигнал «декодер открылся», '
                'а не подтверждение кадров. Норма за сессию 1–3.',
                style: TextStyle(fontSize: 11, color: Colors.grey),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
