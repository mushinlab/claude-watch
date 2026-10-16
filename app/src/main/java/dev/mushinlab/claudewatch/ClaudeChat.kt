package dev.mushinlab.claudewatch

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.WebSearchTool20250305
import dev.mushinlab.claudewatch.data.NotionApi
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.optionals.getOrNull
import org.json.JSONObject

data class ChatMessage(val fromUser: Boolean, val text: String, val id: Long = nextMessageId.getAndIncrement())

private val nextMessageId = AtomicLong()

/** Thin wrapper over the Anthropic Java SDK: streaming requests with a small Notion tool loop per user turn. */
object ClaudeChat {
    private const val SYSTEM_PROMPT =
        "Ты голосовой помощник на умных часах. Отвечай коротко, обычно 1–3 предложения, " +
            "без markdown, ссылок, списков и таблиц: ответ будет зачитан вслух и показан на маленьком круглом экране. " +
            "Отвечай на языке пользователя. Если вопрос про свежие события, погоду, цены или другие актуальные данные, " +
            "используй веб-поиск."

    private val NOW_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE, HH:mm", Locale("ru"))

    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    @Synchronized
    private fun client(apiKey: String): AnthropicClient {
        val existing = client
        if (existing != null && clientKey == apiKey) return existing
        existing?.close()
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build().also {
            client = it
            clientKey = apiKey
        }
    }

    /**
     * Streams the reply to [history] (which ends with the user's turn), running Notion tool calls in between.
     * Returns the text of the final assistant message.
     */
    fun stream(
        settings: Settings,
        history: List<ChatMessage>,
        onStatus: (String?) -> Unit,
        onDelta: (String) -> Unit,
    ): String {
        val model = Settings.MODELS.firstOrNull { it.id == settings.model }
        val notion = settings.notionToken.takeIf { it.isNotBlank() }?.let { NotionApi(it, emptySet()) }
        val system = buildString {
            append(SYSTEM_PROMPT)
            if (notion != null) {
                append(" ").append(NOTION_PROMPT)
                settings.notionPrompt.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
            }
            append(" Сейчас ${LocalDateTime.now().format(NOW_FORMAT)}.")
        }
        val builder = MessageCreateParams.builder()
            .model(settings.model)
            .maxTokens(4000L)
            .system(system)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(settings.effort)).build())
        builder.addTool(WebSearchTool20250305.builder().maxUses(3L).build())
        if (notion != null) NOTION_TOOLS.forEach { builder.addTool(it) }
        history.forEach { if (it.fromUser) builder.addUserMessage(it.text) else builder.addAssistantMessage(it.text) }
        if (model?.supportsFallbacks == true) {
            // Server-side fallback if a safety classifier declines the request on this model.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }

        var message = streamOnce(client(settings.apiKey), builder.build(), onDelta)
        repeat(MAX_TOOL_ROUNDS) {
            when (message.stopReason().getOrNull()) {
                StopReason.TOOL_USE -> {
                    builder.addMessage(message)
                    onStatus("Работаю с Notion…")
                    val results = message.content().mapNotNull { it.toolUse().getOrNull() }.map { runTool(notion, it) }
                    onStatus(null)
                    builder.addUserMessageOfBlockParams(results)
                }
                StopReason.PAUSE_TURN -> builder.addMessage(message)
                else -> return finalText(message)
            }
            message = streamOnce(client(settings.apiKey), builder.build(), onDelta)
        }
        return finalText(message)
    }

    private fun streamOnce(client: AnthropicClient, params: MessageCreateParams, onDelta: (String) -> Unit): Message {
        val accumulator = StreamAccumulator()
        client.messages().createStreaming(params).use { response ->
            response.stream().forEach { event ->
                accumulator.accept(event)
                event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { onDelta(it.text()) }
            }
        }
        return accumulator.message()
    }

    private fun finalText(message: Message): String =
        message.content().mapNotNull { it.text().getOrNull()?.text() }.joinToString("")

    private fun runTool(notion: NotionApi?, call: ToolUseBlock): ContentBlockParam {
        val result = ToolResultBlockParam.builder().toolUseId(call.id())
        try {
            if (notion == null) throw NotionApi.NotionException("Notion не настроен.")
            val input = JSONObject(call._input().convert(Map::class.java))
            val output = when (call.name()) {
                "notion_search" -> notion.search(input.getString("query"))
                "notion_read_page" -> notion.readPage(input.getString("page_id"))
                "notion_create_page" -> notion.createPage(
                    input.getString("parent_page_id"), input.getString("title"), input.getJSONArray("blocks"),
                )
                "notion_append_blocks" -> notion.appendBlocks(
                    input.getString("parent_id"), input.getJSONArray("blocks"), input.getString("after_block_id"),
                )
                "notion_update_block_text" -> notion.updateBlockText(input.getString("block_id"), input.getString("text"))
                else -> throw NotionApi.NotionException("Неизвестный инструмент ${call.name()}")
            }
            result.content(output)
        } catch (e: Exception) {
            result.content(e.message ?: e.javaClass.simpleName).isError(true)
        }
        return ContentBlockParam.ofToolResult(result.build())
    }

    private const val MAX_TOOL_ROUNDS = 8

    private const val NOTION_PROMPT =
        "У тебя есть инструменты Notion: искать, читать, создавать и редактировать страницы. " +
            "Видны только страницы, к которым пользователь открыл доступ интеграции. " +
            "Никогда ничего не удаляй; если меняешь существующий текст, скажи, что именно изменил."

    private val BLOCKS_SCHEMA = mapOf(
        "type" to "array",
        "description" to "Блоки по порядку.",
        "items" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "type" to mapOf(
                    "type" to "string",
                    "enum" to listOf("heading_2", "heading_3", "paragraph", "bulleted_list_item", "to_do", "divider"),
                ),
                "text" to mapOf("type" to "string", "description" to "Текст блока; для divider пустая строка."),
            ),
            "required" to listOf("type", "text"),
            "additionalProperties" to false,
        ),
    )

    private val NOTION_TOOLS = listOf(
        notionTool(
            "notion_search", "Найти страницы Notion по названию. Возвращает строки «id | название | url».",
            "query" to mapOf("type" to "string", "description" to "Текст для поиска по названию."),
        ),
        notionTool(
            "notion_read_page",
            "Прочитать страницу: название и блоки верхнего уровня, по строке «[block_id] тип: текст».",
            "page_id" to mapOf("type" to "string"),
        ),
        notionTool(
            "notion_create_page", "Создать дочернюю страницу внутри другой страницы.",
            "parent_page_id" to mapOf("type" to "string"),
            "title" to mapOf("type" to "string"),
            "blocks" to BLOCKS_SCHEMA,
        ),
        notionTool(
            "notion_append_blocks",
            "Добавить блоки в страницу или блок. Чтобы вставить внутрь раздела, укажи after_block_id — " +
                "блок, после которого вставлять; пустая строка — в конец страницы.",
            "parent_id" to mapOf("type" to "string"),
            "blocks" to BLOCKS_SCHEMA,
            "after_block_id" to mapOf("type" to "string"),
        ),
        notionTool(
            "notion_update_block_text",
            "Заменить текст существующего блока (paragraph, heading, list item, to_do).",
            "block_id" to mapOf("type" to "string"),
            "text" to mapOf("type" to "string"),
        ),
    )

    private fun notionTool(name: String, description: String, vararg props: Pair<String, Any>): Tool {
        val properties = Tool.InputSchema.Properties.builder()
        props.forEach { (key, schema) -> properties.putAdditionalProperty(key, JsonValue.from(schema)) }
        return Tool.builder()
            .name(name)
            .description(description)
            .strict(true)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(properties.build())
                    .required(props.map { it.first })
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            )
            .build()
    }
}
