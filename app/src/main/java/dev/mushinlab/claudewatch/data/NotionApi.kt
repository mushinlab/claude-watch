package dev.mushinlab.claudewatch.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NotionApi(
    private val token: String,
    writablePageIds: Set<String>,
) {
    class NotionException(
        message: String,
        val status: Int = 0,
    ) : Exception(message)

    private val writable: Set<String> = writablePageIds.map(::normalizeId).toSet()

    fun search(query: String): String {
        val body = JSONObject()
        body.put("query", query)
        body.put("filter", JSONObject().put("property", "object").put("value", "page"))
        body.put("page_size", 8)
        val results = call("POST", apiUrl("search"), body).getJSONArray("results")
        if (results.length() == 0) return "Ничего не найдено."
        return (0 until results.length()).joinToString("\n") {
            val page = results.getJSONObject(it)
            "${page.getString("id")} | ${pageTitle(page)} | ${page.optString("url")}"
        }
    }

    fun readPage(pageId: String): String {
        requireValidId(pageId)
        val title = pageTitle(call("GET", apiUrl("pages", pageId)))
        val lines = mutableListOf("Страница: $title")
        var cursor: String? = null
        do {
            val params = mutableMapOf("page_size" to "100")
            cursor?.let { params["start_cursor"] = it }
            val res = call("GET", apiUrl("blocks", pageId, "children", params = params))
            val blocks = res.getJSONArray("results")
            for (i in 0 until blocks.length()) {
                val block = blocks.getJSONObject(i)
                val type = block.getString("type")
                val text =
                    when (type) {
                        "child_page" -> block.getJSONObject(type).optString("title")
                        else ->
                            block
                                .optJSONObject(type)
                                ?.optJSONArray("rich_text")
                                ?.let(::plainText)
                                .orEmpty()
                    }
                val done = type == "to_do" && block.getJSONObject(type).optBoolean("checked")
                val checked = if (done) " [x]" else ""
                lines += "[${block.getString("id")}] $type$checked: $text"
            }
            cursor = if (res.optBoolean("has_more")) res.optString("next_cursor") else null
        } while (cursor != null && lines.size < 200)
        return lines.joinToString("\n")
    }

    fun createPage(
        parentPageId: String,
        title: String,
        blocks: JSONArray,
    ): String {
        requireValidId(parentPageId)
        checkCanWrite(parentPageId)
        val body = JSONObject()
        body.put("parent", JSONObject().put("page_id", parentPageId))
        body.put("properties", JSONObject().put("title", JSONObject().put("title", richText(title))))
        body.put("children", toNotionBlocks(blocks))
        val page = call("POST", apiUrl("pages"), body)
        return "Создана страница ${page.getString("id")} ${page.optString("url")}"
    }

    fun appendBlocks(
        parentId: String,
        blocks: JSONArray,
        afterBlockId: String,
    ): String {
        requireValidId(parentId)
        if (afterBlockId.isNotBlank()) requireValidId(afterBlockId)
        checkCanWrite(parentId)
        val body = JSONObject().put("children", toNotionBlocks(blocks))
        if (afterBlockId.isNotBlank()) body.put("after", afterBlockId)
        val added = call("PATCH", apiUrl("blocks", parentId, "children"), body).getJSONArray("results")
        return "Добавлено блоков: ${added.length()}"
    }

    fun updateBlockText(
        blockId: String,
        text: String,
    ): String {
        requireValidId(blockId)
        checkCanWrite(blockId)
        val type = call("GET", apiUrl("blocks", blockId)).getString("type")
        if (type !in EDITABLE_TYPES) {
            throw NotionException("Блок типа $type нельзя редактировать этим инструментом.")
        }
        val content = JSONObject().put(type, JSONObject().put("rich_text", richText(text)))
        call("PATCH", apiUrl("blocks", blockId), content)
        return "Текст блока обновлён."
    }

    private fun normalizeId(id: String): String = id.lowercase().replace("-", "")

    private fun requireValidId(id: String) {
        if (!ID_PATTERN.matches(id)) {
            throw NotionException("Некорректный ID Notion: «$id». Нужен UUID страницы или блока.")
        }
    }

    private fun checkCanWrite(targetId: String) {
        if (writable.isEmpty()) {
            throw NotionException("Запись в Notion запрещена: нет разрешённых страниц.")
        }
        var current: String? = normalizeId(targetId)
        repeat(MAX_PARENT_DEPTH + 1) {
            if (current != null && writable.contains(current)) return
            current = current?.let(::parentOf)
        }
        throw NotionException("Запись запрещена: страница не входит в список разрешённых.")
    }

    private fun parentOf(id: String): String? {
        val obj = readBlockOrPage(id) ?: return null
        val parent = obj.optJSONObject("parent") ?: return null
        return when (parent.optString("type")) {
            "page_id" -> normalizeId(parent.getString("page_id"))
            "block_id" -> normalizeId(parent.getString("block_id"))
            else -> null
        }
    }

    private fun readBlockOrPage(id: String): JSONObject? {
        try {
            return call("GET", apiUrl("blocks", id))
        } catch (error: NotionException) {
            if (error.status != 404) throw error
        }
        return try {
            call("GET", apiUrl("pages", id))
        } catch (error: NotionException) {
            if (error.status != 404) throw error
            null
        }
    }

    private fun toNotionBlocks(blocks: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until blocks.length()) {
            val block = blocks.getJSONObject(i)
            val type = block.getString("type")
            val content =
                when (type) {
                    "divider" -> JSONObject()
                    "to_do" -> JSONObject().put("rich_text", richText(block.optString("text"))).put("checked", false)
                    in EDITABLE_TYPES -> JSONObject().put("rich_text", richText(block.optString("text")))
                    else -> throw NotionException("Неизвестный тип блока: $type")
                }
            out.put(JSONObject().put("object", "block").put("type", type).put(type, content))
        }
        return out
    }

    private fun call(
        method: String,
        url: HttpUrl,
        body: JSONObject? = null,
    ): JSONObject {
        val builder = Request.Builder()
        builder.url(url)
        builder.header("Authorization", "Bearer $token")
        builder.header("Notion-Version", NOTION_VERSION)
        val text = body?.toString()
        builder.method(method, text?.toRequestBody("application/json".toMediaType()))
        val request = builder.build()
        sharedClient.newCall(request).execute().use { response ->
            val raw = response.body?.string() ?: ""
            val payload = raw.ifEmpty { "{}" }
            val json = JSONObject(payload)
            if (!response.isSuccessful) {
                val detail = json.optString("message", response.message)
                throw NotionException("Notion ${response.code}: $detail", response.code)
            }
            return json
        }
    }

    private fun apiUrl(
        vararg segments: String,
        params: Map<String, String> = emptyMap(),
    ): HttpUrl {
        val builder = BASE_URL.toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private fun pageTitle(page: JSONObject): String {
        val props = page.optJSONObject("properties") ?: return ""
        for (key in props.keys()) {
            val prop = props.getJSONObject(key)
            if (prop.optString("type") == "title") return plainText(prop.getJSONArray("title"))
        }
        return ""
    }

    private fun plainText(richText: JSONArray): String =
        (0 until richText.length()).joinToString("") { richText.getJSONObject(it).optString("plain_text") }

    private fun richText(text: String): JSONArray {
        val parts = JSONArray()
        val words = text.chunked(2000)
        for (word in words) {
            parts.put(JSONObject().put("type", "text").put("text", JSONObject().put("content", word)))
        }
        return parts
    }

    companion object {
        private const val NOTION_VERSION = "2022-06-28"
        private const val BASE_URL = "https://api.notion.com/v1/"
        private const val MAX_PARENT_DEPTH = 10
        private val ID_PATTERN =
            Regex("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val EDITABLE_TYPES =
            setOf(
                "paragraph",
                "heading_1",
                "heading_2",
                "heading_3",
                "bulleted_list_item",
                "numbered_list_item",
                "to_do",
            )
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
