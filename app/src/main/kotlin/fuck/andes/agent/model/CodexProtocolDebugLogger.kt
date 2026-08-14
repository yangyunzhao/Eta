package fuck.andes.agent.model

import android.util.Log
import fuck.andes.BuildConfig
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/** 仅供 Debug 构建诊断 Codex Responses 协议的脱敏日志。 */
class CodexProtocolDebugLogger(
    private val enabled: Boolean,
    private val sink: (String, String) -> Unit,
) {
    fun beginRequest(model: String, request: JSONObject): RequestTrace {
        val trace = RequestTrace(requestIds.incrementAndGet())
        trace.log(
            stage = "request_started",
            fields = JSONObject()
                .put("model", model)
                .put("request", request),
        )
        return trace
    }

    inner class RequestTrace internal constructor(
        private val requestId: Long,
    ) {
        private var writtenCharacters = 0
        private var budgetExhausted = false

        fun log(stage: String, fields: JSONObject = JSONObject(), attempt: Int = 0) {
            emit(stage, fields, attempt)
        }

        fun logJson(stage: String, payload: JSONObject, attempt: Int = 0) {
            emit(stage, JSONObject().put("payload", payload), attempt)
        }

        fun logException(stage: String, throwable: Throwable, attempt: Int = 0) {
            val stack = JSONArray()
            throwable.stackTrace.forEach { stack.put(it.toString()) }
            emit(
                stage = stage,
                fields = JSONObject()
                    .put("exception_class", throwable.javaClass.name)
                    .put("stack", stack),
                attempt = attempt,
                redactFields = false,
            )
        }

        @Synchronized
        private fun emit(
            stage: String,
            fields: JSONObject,
            attempt: Int,
            redactFields: Boolean = true,
        ) {
            if (!enabled || budgetExhausted) return

            val safeFields = if (redactFields) redact(fields) as JSONObject else fields
            val record = recordFor(stage, attempt, safeFields)
            val chunks = chunksFor(stage, attempt, record)
            val exhaustionChunks = chunksFor(
                stage = "log_budget_exhausted",
                attempt = attempt,
                payload = JSONObject().put("truncated", true).toString(),
            )

            if (writtenCharacters + chunks.sumOf(String::length) + exhaustionChunks.sumOf(String::length) <=
                MAX_REQUEST_CHARACTERS
            ) {
                emitChunks(chunks)
                return
            }

            emitChunks(exhaustionChunks)
            budgetExhausted = true
        }

        private fun recordFor(stage: String, attempt: Int, fields: JSONObject): String {
            fun encode(value: JSONObject): String = JSONObject()
                .put("request_id", requestId)
                .put("attempt", attempt)
                .put("stage", stage)
                .put("fields", value)
                .toString()

            val record = encode(fields)
            if (record.length <= MAX_JSON_CHARACTERS) return record
            return encode(
                JSONObject()
                    .put("truncated", true)
                    .put("original_characters", fields.toString().length)
                    .put("limit_characters", MAX_JSON_CHARACTERS),
            )
        }

        private fun chunksFor(stage: String, attempt: Int, payload: String): List<String> {
            val fragments = payload.chunked(MAX_CHUNK_PAYLOAD_CHARACTERS)
            return fragments.mapIndexed { index, fragment ->
                JSONObject()
                    .put("request_id", requestId)
                    .put("attempt", attempt)
                    .put("stage", stage)
                    .put("chunk_index", index)
                    .put("chunk_total", fragments.size)
                    .put("payload", fragment)
                    .toString()
            }
        }

        private fun emitChunks(chunks: List<String>) {
            chunks.forEach { chunk ->
                sink(TAG, chunk)
                writtenCharacters += chunk.length
            }
        }
    }

    private fun redact(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { copy ->
            value.keys().forEach { key ->
                val child = value.get(key)
                copy.put(key, if (isSensitiveKey(key)) REDACTED else redact(child))
            }
        }

        is JSONArray -> JSONArray().also { copy ->
            (0 until value.length()).forEach { index -> copy.put(redact(value.get(index))) }
        }

        is String -> redactString(value)
        else -> value
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().replace("_", "").replace("-", "")
        return SENSITIVE_KEY_PARTS.any(normalized::contains)
    }

    private fun redactString(value: String): String = value
        .replace(BEARER_PATTERN, REDACTED)
        .replace(COMPACT_TOKEN_CANDIDATE) { candidate ->
            if (hasCompactJwtHeader(candidate.groupValues[1])) REDACTED else candidate.value
        }

    private fun hasCompactJwtHeader(encodedHeader: String): Boolean = runCatching {
        JSONObject(String(Base64.getUrlDecoder().decode(encodedHeader), StandardCharsets.UTF_8))
            .optString("alg")
            .isNotBlank()
    }.getOrDefault(false)

    companion object {
        private const val TAG = "EtaCodexProtocol"
        private const val REDACTED = "[REDACTED]"
        private const val MAX_CHUNK_CHARACTERS = 3_000
        private const val MAX_CHUNK_PAYLOAD_CHARACTERS = 1_300
        private const val MAX_JSON_CHARACTERS = 64 * 1_024
        private const val MAX_REQUEST_CHARACTERS = 512 * 1_024
        private val requestIds = AtomicLong()
        private val SENSITIVE_KEY_PARTS = listOf(
            "token",
            "account",
            "auth",
            "devicecode",
            "usercode",
            "pkce",
            "codeverifier",
            "codechallenge",
            "secret",
            "header",
            "encryptedcontent",
            "authorization",
            "cookie",
            "credential",
        )
        private val BEARER_PATTERN = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/-]+={0,2}""")
        private val COMPACT_TOKEN_CANDIDATE = Regex(
            """(?<![A-Za-z0-9_-])([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]*)(?![A-Za-z0-9_-])""",
        )

        fun default(): CodexProtocolDebugLogger = CodexProtocolDebugLogger(
            enabled = BuildConfig.DEBUG,
            // Local JVM tests do not provide an Android Log implementation.
            sink = { tag, line -> runCatching { Log.d(tag, line) } },
        )
    }
}
