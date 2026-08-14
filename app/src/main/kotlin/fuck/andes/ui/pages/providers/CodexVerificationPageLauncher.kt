package fuck.andes.ui.pages.providers

import android.content.Context
import android.content.Intent
import android.net.Uri

internal const val CODEX_VERIFICATION_PAGE_URL = "https://auth.openai.com/codex/device"

internal fun interface CodexVerificationPageLauncher {
    fun open(): Boolean
}

/** Opens the fixed Codex verification page in an external activity. */
internal class AndroidCodexVerificationPageLauncher(
    private val context: Context,
) : CodexVerificationPageLauncher {
    override fun open(): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(CODEX_VERIFICATION_PAGE_URL)).apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.isSuccess
}
