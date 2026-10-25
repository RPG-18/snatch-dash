import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

import '../l10n/app_localizations.dart';
import '../nav/voice_settings.dart';
import '../state/voice_settings.dart';

Future<void> showVoiceSettingsSheet(BuildContext context) =>
    showModalBottomSheet<void>(
      context: context,
      useRootNavigator: true,
      showDragHandle: true,
      enableDrag: true,
      isDismissible: true,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => const VoiceSettingsScreen(),
    );

class VoiceSettingsScreen extends ConsumerWidget {
  const VoiceSettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context)!;
    final settings = ref.watch(voiceSettingsProvider);
    final controller = ref.read(voiceSettingsProvider.notifier);
    final theme = Theme.of(context);

    return SafeArea(
      child: ListView(
        shrinkWrap: true,
        padding: const EdgeInsets.all(16),
        children: [
          Text(l10n.settingsVoiceTitle, style: theme.textTheme.titleLarge),
          const SizedBox(height: 16),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(l10n.voiceSettingsEnabled),
            value: settings.enabled,
            onChanged: controller.setEnabled,
          ),
          const SizedBox(height: 16),
          Text(l10n.voiceSettingsVolume, style: theme.textTheme.labelLarge),
          const SizedBox(height: 8),
          SegmentedButton<VoiceVolume>(
            expandedInsets: EdgeInsets.zero,
            showSelectedIcon: false,
            segments: [
              ButtonSegment(
                value: VoiceVolume.low,
                label: Text(l10n.voiceSettingsVolumeLow),
                tooltip: '25%',
              ),
              ButtonSegment(
                value: VoiceVolume.medium,
                label: Text(l10n.voiceSettingsVolumeMedium),
                tooltip: '50%',
              ),
              ButtonSegment(
                value: VoiceVolume.high,
                label: Text(l10n.voiceSettingsVolumeHigh),
                tooltip: '75%',
              ),
            ],
            selected: {settings.volume},
            onSelectionChanged: (values) => controller.setVolume(values.single),
          ),
          const SizedBox(height: 24),
          Text(
            l10n.voiceSettingsDuringAnnouncements,
            style: theme.textTheme.labelLarge,
          ),
          const SizedBox(height: 8),
          DropdownButton<VoiceMusicPolicy>(
            value: settings.musicPolicy,
            isExpanded: true,
            itemHeight: null,
            items: [
              for (final policy in VoiceMusicPolicy.values)
                DropdownMenuItem(
                  value: policy,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Text(switch (policy) {
                      VoiceMusicPolicy.duck => l10n.voiceSettingsMusicDuck,
                      VoiceMusicPolicy.pauseAndResume =>
                        l10n.voiceSettingsMusicPause,
                      VoiceMusicPolicy.unchanged =>
                        l10n.voiceSettingsMusicUnchanged,
                    }),
                  ),
                ),
            ],
            onChanged: (policy) {
              if (policy != null) controller.setMusicPolicy(policy);
            },
          ),
        ],
      ),
    );
  }
}
