package fuck.andes.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import fuck.andes.R
import fuck.andes.data.model.TtsProvider
import fuck.andes.data.repository.SpeechSettingsRepository
import fuck.andes.ui.components.EtaArrowPreference
import fuck.andes.ui.components.EtaPreferenceColors
import fuck.andes.ui.components.EtaPreferenceDivider
import fuck.andes.ui.components.EtaPreferenceGroup
import fuck.andes.ui.components.EtaPreferenceGroupTitle
import fuck.andes.ui.components.EtaPreferenceIcon
import fuck.andes.ui.components.EtaSwitchPreference
import fuck.andes.ui.components.MiuixScaffoldPage
import fuck.andes.ui.navigation.AppRoute
import kotlinx.coroutines.launch

/**
 * 语音设置总览：识别与播报各有独立子页面（预测性返回与滑动返回由 miuix-nav 提供），
 * 这里的开关直接读写持久化配置，不涉及凭据草稿。
 */
@Composable
internal fun SpeechSettingsScreen(onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by SpeechSettingsRepository.settingsFlow().collectAsState(initial = null)
    val current = settings

    MiuixScaffoldPage(
        title = stringResource(R.string.speech_settings_title),
        onBack = onBack,
    ) {
        if (current == null) {
            item(key = "loading") {
                SpeechNote(stringResource(R.string.speech_loading))
            }
            return@MiuixScaffoldPage
        }
        item(key = "service_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.speech_group_service))
        }
        item(key = "service") {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = stringResource(R.string.speech_recognition_title),
                    summary = current.asr.label(),
                    startAction = {
                        EtaPreferenceIcon(icon = Icons.Rounded.Mic, tint = EtaPreferenceColors.Blue)
                    },
                    onClick = { onNavigate(AppRoute.SpeechRecognition) },
                )
                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = stringResource(R.string.speech_synthesis_title),
                    summary = current.tts.label(),
                    startAction = {
                        EtaPreferenceIcon(
                            icon = Icons.AutoMirrored.Rounded.VolumeUp,
                            tint = EtaPreferenceColors.Orange,
                        )
                    },
                    onClick = { onNavigate(AppRoute.SpeechSynthesis) },
                )
            }
        }
        item(key = "assistant_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.speech_group_assistant))
        }
        item(key = "assistant") {
            Column {
                EtaPreferenceGroup {
                    EtaSwitchPreference(
                        title = stringResource(R.string.speech_auto_speak),
                        summary = stringResource(R.string.speech_auto_speak_summary),
                        checked = current.autoSpeak,
                        enabled = current.tts != TtsProvider.NONE,
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.RecordVoiceOver,
                                tint = EtaPreferenceColors.Green,
                            )
                        },
                        onCheckedChange = { enabled ->
                            scope.launch {
                                SpeechSettingsRepository.save(current.copy(autoSpeak = enabled))
                            }
                        },
                    )
                }
                SpeechNote(stringResource(R.string.speech_overview_note))
            }
        }
    }
}
