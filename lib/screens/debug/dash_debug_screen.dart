import 'dart:async';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

import '../dash_screen.dart';

/// The frame that is actually going to the dash, 1:1, plus the numbers behind it.
///
/// See `spec/dash_screen_debug.md`. Three things here are decisions, not
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
              scrollDirection: Axis.horizontal,
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _FramePane(frame: _frame!, stale: _stale),
                  const SizedBox(width: 16),
                  _Telemetry(data: _data ?? const {}, stale: _stale),
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
    // Sized in DEVICE pixels, not logical ones. Flutter measures in logical
    // pixels, so asking for 526 wide on this phone would paint the frame ~2.75x
    // magnified — and the point of this pane is to look at sharpness, which a
    // magnified copy cannot show. Dividing by the device ratio makes one frame
    // pixel land on one screen pixel.
    final dpr = MediaQuery.devicePixelRatioOf(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Opacity(
          opacity: stale ? 0.35 : 1,
          child: SizedBox(
            width: frame.width / dpr,
            height: frame.height / dpr,
            child: RawImage(image: frame, filterQuality: FilterQuality.none),
          ),
        ),
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text(
            stale
                ? 'Кадров нет — показан последний, приглушён'
                : '${frame.width}×${frame.height}, пиксель в пиксель',
            style: const TextStyle(fontSize: 11),
          ),
        ),
      ],
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
