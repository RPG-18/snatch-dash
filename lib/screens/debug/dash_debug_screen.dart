import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

/// The frame that is actually going to the dash, 1:1, plus the numbers behind it.
///
/// See `spec/dash_screen_debug.md`. Two things about this screen are decisions,
/// not incidentals:
///
/// - **It is a bench instrument, not a ride screen.** The whole app exists so the
///   phone can ride with its display off; drawing the frame here is work that
///   does not happen on a ride, so nothing measured with this open is a
///   measurement of the ride.
/// - **Subscribing is what turns frame production on.** `frameStream` is inert
///   until listened to, so this screen's cost lands only while it is mounted.
class DashDebugScreen extends StatelessWidget {
  const DashDebugScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Dash debug')),
      body: StreamBuilder<Map<String, dynamic>>(
        stream: DashEngine.instance.frameStream,
        builder: (context, snap) {
          final data = snap.data;
          if (data == null) {
            // Deliberately not the last frame of a previous session: an image
            // with no live stream behind it reads as "the link is up".
            return const Center(child: Text('Нет кадров — стрим не запущен'));
          }
          return SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _FramePane(png: data['png'] as Uint8List?),
                const SizedBox(width: 16),
                _Telemetry(data: data),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _FramePane extends StatelessWidget {
  const _FramePane({required this.png});

  final Uint8List? png;

  @override
  Widget build(BuildContext context) {
    final bytes = png;
    if (bytes == null) return const SizedBox(width: 526, height: 300);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 1:1, no scaling. The point of looking at this frame is its sharpness,
        // and a stretched copy would answer that question with interpolation of
        // its own — see spec/dash_screen_debug.md, «Масштаб кадра».
        Image.memory(
          bytes,
          width: 526,
          height: 300,
          gaplessPlayback: true,
          filterQuality: FilterQuality.none,
        ),
        const Padding(
          padding: EdgeInsets.only(top: 4),
          child: Text('526×300, пиксель в пиксель', style: TextStyle(fontSize: 11)),
        ),
      ],
    );
  }
}

class _Telemetry extends StatelessWidget {
  const _Telemetry({required this.data});

  final Map<String, dynamic> data;

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
    return DefaultTextStyle.merge(
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
                  children: [
                    Text(label),
                    Text(value, style: const TextStyle(fontFeatures: [FontFeature.tabularFigures()])),
                  ],
                ),
              ),
            ),
          const SizedBox(height: 8),
          // The label above says "decoder opens" and this says why, because the
          // obvious reading of the number is wrong and has cost two
          // investigations already (spec/video.md).
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
    );
  }
}
