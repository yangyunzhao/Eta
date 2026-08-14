package fuck.andes.agent.model

import fuck.andes.agent.runtime.AgentRunController
import fuck.andes.data.model.OpenAiEndpointMode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object OpenAiResponsesProvider : AgentProviderClient {
    private const val MAX_ERROR_CHARS = 600
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override val id: String = "openai_responses"

    override val capabilities = ProviderCapabilities(
        endpoint = EndpointKind.RESPONSES,
        streamingText = true,
        streamingToolCalls = true,
        imageInput = true,
        toolResultImages = false,
        strictTools = false,
        parallelToolCalls = true,
    )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        val config = request.config
        require(config.openAiEndpointMode == OpenAiEndpointMode.RESPONSES) {
            "当前 Provider 未配置为 Responses API"
        }
        val body = buildRequestJson(config, request.messages, request.tools)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val headers = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json; charset=utf-8")
            .add("Accept", "text/event-stream")
            .apply {
                if (config.apiKey.isNotBlank()) add("Authorization", "Bearer ${config.apiKey}")
            }
            .also { CustomHeaderFilter.mergeInto(it, config.customHeaders) }
            .build()
        val httpRequest = Request.Builder()
            .url(ProviderUrls.openAiResponsesUrl(config.baseUrl))
            .headers(headers)
            .post(body)
            .build()
        val call = AgentHttpClient.client.newCall(httpRequest)
        val binding = runController.register(call::cancel)

        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            call.execute().use { response ->
                onEvent(ProviderEvent.ResponseHeaders(response.code))
                runController.throwIfCancelled()
                if (!response.isSuccessful) {
                    error("模型接口返回 HTTP ${response.code}：${response.body.string().compactError()}")
                }
                val assistant = ResponsesSseParser.parse(
                    stream = response.body.byteStream(),
                    runController = runController,
                    onEvent = onEvent,
                )
                onEvent(ProviderEvent.Completed(assistant.optString("finish_reason").ifBlank { null }))
                return ProviderResponse(assistant)
            }
        } catch (throwable: Throwable) {
            runCatching { runController.throwIfCancelled() }
                .getOrElse { interruption -> throw interruption }
            throw throwable
        } finally {
            binding.close()
        }
    }

    internal fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
    ): JSONObject = ResponsesRequestBuilder.build(config, messages, tools)

    private fun String.compactError(): String =
        replace('\n', ' ').replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }
}
