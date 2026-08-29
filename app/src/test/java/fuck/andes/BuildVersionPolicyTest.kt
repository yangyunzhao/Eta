package fuck.andes

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildVersionPolicyTest {
    @Test
    fun `当前构建使用首个 znmlr 下游候选版本`() {
        assertEquals("2.6.5.znmlr.1", BuildConfig.VERSION_NAME)
        assertEquals(26_501, BuildConfig.VERSION_CODE)
    }

    @Test
    fun `下游版本由已验证的上游基线和发布序号计算`() {
        val upstreamVersionName = requiredProperty("eta.test.upstreamVersionName")
        val upstreamVersionCode = requiredProperty("eta.test.upstreamVersionCode").toInt()
        val downstreamReleaseSequence =
            requiredProperty("eta.test.downstreamReleaseSequence").toInt()

        assertEquals("2.6.5", upstreamVersionName)
        assertEquals(265, upstreamVersionCode)
        assertEquals(1, downstreamReleaseSequence)
        assertEquals(
            "$upstreamVersionName.znmlr.$downstreamReleaseSequence",
            BuildConfig.VERSION_NAME,
        )
        assertEquals(
            upstreamVersionCode * 100 + downstreamReleaseSequence,
            BuildConfig.VERSION_CODE,
        )
    }

    private fun requiredProperty(name: String): String =
        checkNotNull(System.getProperty(name)) {
            "Gradle must expose $name to unit tests"
        }
}
