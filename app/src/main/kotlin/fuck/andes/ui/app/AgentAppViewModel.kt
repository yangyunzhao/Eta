package fuck.andes.ui.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fuck.andes.agent.terminal.DetachedTaskSupervisor
import fuck.andes.agent.terminal.LinuxEnvironmentPaths
import fuck.andes.agent.terminal.LinuxPackageProfiles
import fuck.andes.agent.terminal.SharedFolderMounts
import fuck.andes.agent.terminal.linuxPackageProfileReady
import fuck.andes.agent.terminal.terminalEnvironment
import fuck.andes.core.AndroidAgentLogger
import fuck.andes.data.repository.LinuxEnvironmentSettingsRepository
import kotlinx.coroutines.launch

/** Activity 级状态所有者；配置变更只重建 UI，不替换正在运行的 Agent 会话。 */
internal class AgentAppViewModel(application: Application) : AndroidViewModel(application) {
    val state = AgentAppState(
        context = application,
        scope = viewModelScope,
    )
    val terminalStore = UserTerminalStore(
        context = application,
        scope = viewModelScope,
    )
    val consoleStore = ConsoleStore(
        context = application,
        scope = viewModelScope,
    )
    val kimiWebLauncher = KimiWebLauncher(
        context = application,
        daemonSupervisor = DetachedTaskSupervisor(
            logger = AndroidAgentLogger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(application),
            linuxRootfsPathProvider = { environment ->
                environment.linuxDistribution?.let { distribution ->
                    LinuxEnvironmentPaths.rootfsDir(application, distribution).absolutePath
                }
            },
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
        ),
    )

    /** Kimi Code profile 是否已就绪；未就绪时入口应引导到安装页。 */
    fun kimiWebReady(): Boolean {
        val distribution = LinuxEnvironmentSettingsRepository.current(getApplication())
        val rootfs = LinuxEnvironmentPaths.rootfsDir(getApplication(), distribution)
        return linuxPackageProfileReady(rootfs, LinuxPackageProfiles.KIMI)
    }

    fun launchKimiWeb(onFinished: (KimiWebLaunchResult) -> Unit) {
        viewModelScope.launch {
            val distribution = LinuxEnvironmentSettingsRepository.current(getApplication())
            onFinished(kimiWebLauncher.launch(distribution.terminalEnvironment))
        }
    }

    override fun onCleared() {
        terminalStore.close()
        consoleStore.close()
        super.onCleared()
    }
}
