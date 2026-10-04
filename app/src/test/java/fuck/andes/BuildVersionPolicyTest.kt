package fuck.andes

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildVersionPolicyTest {
    @Test
    fun `修复候选递增下游版本以覆盖安装故障候选`() {
        assertEquals("3.1.0.znmlr.2", BuildConfig.VERSION_NAME)
        assertEquals(2_026_100_202, BuildConfig.VERSION_CODE)
    }

    @Test
    fun `下游版本由已验证的上游基线和发布序号计算`() {
        val upstreamVersionName = requiredProperty("eta.test.upstreamVersionName")
        val upstreamVersionCode = requiredProperty("eta.test.upstreamVersionCode").toInt()
        val downstreamReleaseSequence =
            requiredProperty("eta.test.downstreamReleaseSequence").toInt()

        assertEquals("3.1.0", upstreamVersionName)
        assertEquals(2_026_100_201, upstreamVersionCode)
        assertEquals(2, downstreamReleaseSequence)
        assertEquals(
            "$upstreamVersionName.znmlr.$downstreamReleaseSequence",
            BuildConfig.VERSION_NAME,
        )
        assertEquals(
            upstreamVersionCode + downstreamReleaseSequence - 1,
            BuildConfig.VERSION_CODE,
        )
    }

    private fun requiredProperty(name: String): String =
        checkNotNull(System.getProperty(name)) {
            "Gradle must expose $name to unit tests"
        }
}
