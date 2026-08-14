package fuck.andes.data.auth

import android.content.Context
import android.content.SharedPreferences
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CodexCredentialStoreTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var store: AndroidCodexCredentialStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val key = SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
        store = AndroidCodexCredentialStore(
            packageName = context.packageName,
            preferences = preferences,
            secretKeyProvider = SecretKeyProvider { key },
        )
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun credentialRoundTripsAndRepeatedSavesUseDifferentCiphertext() {
        store.save(PROVIDER_A, CREDENTIAL)
        val firstCiphertext = onlyStoredValue()

        store.save(PROVIDER_A, CREDENTIAL)
        val secondCiphertext = onlyStoredValue()

        assertNotEquals(firstCiphertext, secondCiphertext)
        assertEquals(CREDENTIAL, store.load(PROVIDER_A))
    }

    @Test
    fun persistedEnvelopeAndCredentialStringDoNotExposeSecrets() {
        store.save(PROVIDER_A, CREDENTIAL)

        val persistedEnvelope = onlyStoredValue()
        val renderedCredential = CREDENTIAL.toString()

        CREDENTIAL.secretValues().forEach { secret ->
            assertFalse(persistedEnvelope.contains(secret))
            assertFalse(renderedCredential.contains(secret))
        }
    }

    @Test
    fun ciphertextCannotBeLoadedUnderAnotherProviderId() {
        store.save(PROVIDER_A, CREDENTIAL)
        val providerAEntry = preferences.all.entries.single()
        val providerACiphertext = providerAEntry.value as String

        store.save(PROVIDER_B, CREDENTIAL)
        val providerBKey = preferences.all.keys.single { it != providerAEntry.key }
        preferences.edit().putString(providerBKey, providerACiphertext).commit()

        assertNull(store.load(PROVIDER_B))
        assertFalse(preferences.contains(providerBKey))
        assertEquals(CREDENTIAL, store.load(PROVIDER_A))
    }

    @Test
    fun tamperedCiphertextFailsClosedAndIsDeleted() {
        store.save(PROVIDER_A, CREDENTIAL)
        val entry = preferences.all.entries.single()
        val envelope = Base64.getDecoder().decode(entry.value as String)
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 0x01).toByte()
        preferences.edit().putString(entry.key, Base64.getEncoder().encodeToString(envelope)).commit()

        assertNull(store.load(PROVIDER_A))
        assertFalse(preferences.contains(entry.key))
    }

    @Test
    fun clearRemovesPersistedCredential() {
        store.save(PROVIDER_A, CREDENTIAL)

        store.clear(PROVIDER_A)

        assertNull(store.load(PROVIDER_A))
        assertFalse(preferences.all.isNotEmpty())
    }

    private fun onlyStoredValue(): String = preferences.all.values.single() as String

    private fun CodexOAuthCredential.secretValues(): List<String> =
        listOfNotNull(accessToken, refreshToken, idToken, accountId)

    private companion object {
        const val PREFERENCES_NAME = "eta_codex_oauth_credentials_v1_unit_test"
        const val PROVIDER_A = "provider-a"
        const val PROVIDER_B = "provider-b"

        val CREDENTIAL = CodexOAuthCredential(
            accessToken = "synthetic-access-token-for-store-tests",
            refreshToken = "synthetic-refresh-token-for-store-tests",
            idToken = "synthetic-id-token-for-store-tests",
            accountId = "synthetic-account-for-store-tests",
            expiresAtEpochMillis = 1_900_000_000_000L,
        )
    }
}
