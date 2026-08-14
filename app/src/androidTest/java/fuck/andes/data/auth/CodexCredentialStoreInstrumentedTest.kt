package fuck.andes.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodexCredentialStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = context.getSharedPreferences(TEST_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        deleteTestKey()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
        deleteTestKey()
    }

    @Test
    fun androidKeyStoreRoundTripReloadAndLogoutClear() {
        newStore().save(PROVIDER_ID, CREDENTIAL)

        assertEquals(CREDENTIAL, newStore().load(PROVIDER_ID))

        newStore().clear(PROVIDER_ID)
        assertNull(newStore().load(PROVIDER_ID))
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun tamperedCiphertextFailsClosedAndIsDeleted() {
        newStore().save(PROVIDER_ID, CREDENTIAL)
        val entry = preferences.all.entries.single()
        val envelope = Base64.decode(entry.value as String, Base64.NO_WRAP)
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 0x01).toByte()
        preferences.edit()
            .putString(entry.key, Base64.encodeToString(envelope, Base64.NO_WRAP))
            .commit()

        assertNull(newStore().load(PROVIDER_ID))
        assertFalse(preferences.contains(entry.key))
    }

    private fun newStore(): AndroidCodexCredentialStore =
        AndroidCodexCredentialStore(
            packageName = context.packageName,
            preferences = preferences,
            secretKeyProvider = AndroidKeyStoreSecretKeyProvider(TEST_KEY_ALIAS),
        )

    private fun deleteTestKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_KEY_ALIAS)
    }

    private companion object {
        const val TEST_KEY_ALIAS = "eta_codex_oauth_v1_instrumented_test"
        const val TEST_PREFERENCES_NAME = "eta_codex_oauth_credentials_v1_instrumented_test"
        const val PROVIDER_ID = "instrumented-provider"

        val CREDENTIAL = CodexOAuthCredential(
            accessToken = "synthetic-access-token-for-instrumented-test",
            refreshToken = "synthetic-refresh-token-for-instrumented-test",
            idToken = "synthetic-id-token-for-instrumented-test",
            accountId = "synthetic-account-for-instrumented-test",
            expiresAtEpochMillis = 1_900_000_000_000L,
        )
    }
}
