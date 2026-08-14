package fuck.andes.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexProtocolDebugLoggerTest {
    @Test
    fun `redacts every sensitive field with fixed marker without changing source JSON`() {
        val lines = mutableListOf<String>()
        val request = JSONObject()
            .put("access_token", "access-secret")
            .put("idToken", "id-secret")
            .put("account-id", "account-secret")
            .put("device_code", "device-secret")
            .put("device_auth_id", "device-auth-secret")
            .put("user_code", "user-code-secret")
            .put("pkceVerifier", "pkce-secret")
            .put("code_verifier", "verifier-secret")
            .put("code_challenge", "challenge-secret")
            .put("client_secret", "client-secret")
            .put("auth_state", "auth-secret")
            .put("headers", JSONObject().put("Authorization", "Bearer header-secret"))
            .put(
                "nested",
                JSONObject()
                    .put("encrypted_content", "ciphertext-secret")
                    .put("prompt", "normal prompt")
                    .put("output", "normal output")
                    .put("bearer_text", "Bearer value-secret")
                    .put("jwt_text", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1In0.signature"),
            )
        val original = request.toString()

        val trace = CodexProtocolDebugLogger(enabled = true) { _, line -> lines += line }
            .beginRequest("gpt-5.5", request)
        trace.logJson("response", request)

        val redacted = reassembledRecords(lines)
            .single { it.getString("stage") == "request_started" }
            .getJSONObject("fields")
            .getJSONObject("request")
        listOf(
            "access_token",
            "idToken",
            "account-id",
            "device_code",
            "device_auth_id",
            "user_code",
            "pkceVerifier",
            "code_verifier",
            "code_challenge",
            "client_secret",
            "auth_state",
            "headers",
        ).forEach { key -> assertEquals("[REDACTED]", redacted.getString(key)) }
        val nested = redacted.getJSONObject("nested")
        assertEquals("[REDACTED]", nested.getString("encrypted_content"))
        assertEquals("[REDACTED]", nested.getString("bearer_text"))
        assertEquals("[REDACTED]", nested.getString("jwt_text"))
        assertEquals("normal prompt", nested.getString("prompt"))
        assertEquals("normal output", nested.getString("output"))
        assertEquals(original, request.toString())
    }

    @Test
    fun `redacts compact JWT and JWS strings with valid alg header`() {
        val lines = mutableListOf<String>()
        val trace = CodexProtocolDebugLogger(enabled = true) { _, line -> lines += line }
            .beginRequest("gpt-5.5", JSONObject())

        trace.log(
            "response",
            JSONObject().put(
                "output",
                "signed eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1In0.signature value",
            ),
        )

        val record = reassembledRecords(lines).single { it.getString("stage") == "response" }
        assertEquals("signed [REDACTED] value", record.getJSONObject("fields").getString("output"))
    }

    @Test
    fun `redacts alg none compact JWT with empty signature but keeps versions and class names`() {
        val lines = mutableListOf<String>()
        val trace = CodexProtocolDebugLogger(enabled = true) { _, line -> lines += line }
            .beginRequest("gpt-5.5", JSONObject())

        trace.log(
            "response",
            JSONObject().put(
                "output",
                "token eyJhbGciOiJub25lIn0.eyJzdWIiOiJ1In0. version 2.6.0 type com.example.Type",
            ),
        )

        val output = reassembledRecords(lines)
            .single { it.getString("stage") == "response" }
            .getJSONObject("fields")
            .getString("output")
        assertEquals("token [REDACTED] version 2.6.0 type com.example.Type", output)
    }

    @Test
    fun `chunks carry correlation metadata and reconstruct a complete record`() {
        val lines = mutableListOf<String>()
        val trace = CodexProtocolDebugLogger(enabled = true) { _, line -> lines += line }
            .beginRequest("gpt-5.5", JSONObject())

        trace.log("large_fields", JSONObject().put("output", "y".repeat(7_000)), attempt = 3)

        val chunks = lines.map(::JSONObject).filter { it.getString("stage") == "large_fields" }
        assertTrue(chunks.size > 1)
        assertTrue(lines.all { it.length <= 3_000 })
        assertTrue(chunks.all { it.has("request_id") && it.getInt("attempt") == 3 })
        assertTrue(chunks.all { it.has("chunk_index") && it.has("chunk_total") && it.has("payload") })
        assertEquals((0 until chunks.size).toList(), chunks.map { it.getInt("chunk_index") })
        assertTrue(chunks.all { it.getInt("chunk_total") == chunks.size })
        assertEquals(
            "y".repeat(7_000),
            JSONObject(chunks.joinToString("") { it.getString("payload") })
                .getJSONObject("fields")
                .getString("output"),
        )
    }

    @Test
    fun `oversized JSON logs a complete truncated record instead of partial JSON`() {
        val lines = mutableListOf<String>()
        val trace = CodexProtocolDebugLogger(enabled = true) { _, line -> lines += line }
            .beginRequest("gpt-5.5", JSONObject())

        trace.logJson("oversized", JSONObject().put("output", "x".repeat(64 * 1_024)))

        val record = reassembledRecords(lines).single { it.getString("stage") == "oversized" }
        val fields = record.getJSONObject("fields")
        assertTrue(fields.getBoolean("truncated"))
        assertTrue(fields.getInt("original_characters") > 64 * 1_024)
        assertEquals(64 * 1_024, fields.getInt("limit_characters"))
        assertFalse(fields.has("payload"))
    }

    @Test
    fun `metadata cannot push a near limit JSON record beyond 64 KiB`() {
        val lines = mutableListOf<String>()
        val trace = CodexProtocolDebugLogger(enabled = true) { _, line -> lines += line }
            .beginRequest("gpt-5.5", JSONObject())

        trace.log("near_limit", JSONObject().put("output", "x".repeat(64 * 1_024 - 20)))

        val record = reassembledRecords(lines).single { it.getString("stage") == "near_limit" }
        assertTrue(record.toString().length <= 64 * 1_024)
        assertTrue(record.getJSONObject("fields").getBoolean("truncated"))
    }

    @Test
    fun `emits one budget exhaustion record after request logs exceed 512 KiB`() {
        val lines = mutableListOf<String>()
        val trace = CodexProtocolDebugLogger(enabled = true) { _, line -> lines += line }
            .beginRequest("gpt-5.5", JSONObject())

        repeat(220) { index ->
            trace.log("payload_$index", JSONObject().put("output", "x".repeat(3_000)))
        }

        assertEquals(1, lines.count { JSONObject(it).getString("stage") == "log_budget_exhausted" })
        assertTrue(lines.sumOf { it.length } <= 512 * 1_024)
    }

    @Test
    fun `logs exception class and stack elements without exception message`() {
        val lines = mutableListOf<String>()
        val trace = CodexProtocolDebugLogger(enabled = true) { _, line -> lines += line }
            .beginRequest("gpt-5.5", JSONObject())

        trace.logException("request_failed", IllegalStateException("secret exception message"))

        val fields = reassembledRecords(lines)
            .single { it.getString("stage") == "request_failed" }
            .getJSONObject("fields")
        assertEquals("java.lang.IllegalStateException", fields.getString("exception_class"))
        assertTrue(fields.getJSONArray("stack").getString(0).contains("CodexProtocolDebugLoggerTest"))
        assertFalse(fields.toString().contains("secret exception message"))
    }

    @Test
    fun `does not call sink when disabled`() {
        var sinkCalls = 0
        val trace = CodexProtocolDebugLogger(enabled = false) { _, _ -> sinkCalls += 1 }
            .beginRequest("gpt-5.5", JSONObject().put("prompt", "normal prompt"))

        trace.log("stage", JSONObject().put("output", "normal output"))
        trace.logJson("json", JSONObject().put("access_token", "secret"))
        trace.logException("exception", IllegalStateException("secret message"))

        assertEquals(0, sinkCalls)
    }

    private fun reassembledRecords(lines: List<String>): List<JSONObject> = lines
        .map(::JSONObject)
        .groupBy { chunk ->
            listOf(
                chunk.getLong("request_id"),
                chunk.getInt("attempt"),
                chunk.getString("stage"),
            )
        }
        .values
        .map { chunks ->
            val sorted = chunks.sortedBy { it.getInt("chunk_index") }
            assertEquals((0 until sorted.size).toList(), sorted.map { it.getInt("chunk_index") })
            assertTrue(sorted.all { it.getInt("chunk_total") == sorted.size })
            JSONObject(sorted.joinToString("") { it.getString("payload") })
        }
}
