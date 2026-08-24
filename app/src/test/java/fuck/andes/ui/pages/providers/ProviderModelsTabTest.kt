package fuck.andes.ui.pages.providers

import fuck.andes.data.model.ReasoningEffort
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelsTabTest {
    @Test
    fun editableReasoningOverridesRetainCodexUltra() {
        assertTrue(ReasoningEffort.ULTRA in editableReasoningEfforts)
    }
}
