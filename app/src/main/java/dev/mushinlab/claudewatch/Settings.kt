package dev.mushinlab.claudewatch

import android.content.Context

/** Preset model / effort and the API key, stored on the watch. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("claude_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var notionToken: String
        get() = prefs.getString(KEY_NOTION_TOKEN, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_NOTION_TOKEN, value.trim()).apply()

    var notionPrompt: String
        get() = prefs.getString(KEY_NOTION_PROMPT, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_NOTION_PROMPT, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, null) ?: MODELS.first().id
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var effort: String
        get() = prefs.getString(KEY_EFFORT, null) ?: "low"
        set(value) = prefs.edit().putString(KEY_EFFORT, value).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean(KEY_SPEAK, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEAK, value).apply()

    data class ModelOption(
        val id: String,
        val label: String,
        val supportsFallbacks: Boolean,
        val dynamicWebSearch: Boolean,
    )

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_NOTION_TOKEN = "notion_token"
        private const val KEY_NOTION_PROMPT = "notion_prompt"
        private const val KEY_MODEL = "model"
        private const val KEY_EFFORT = "effort"
        private const val KEY_SPEAK = "speak"

        val MODELS = listOf(
            ModelOption("claude-opus-5-5", "Opus 5.5", supportsFallbacks = true, dynamicWebSearch = true),
            ModelOption("claude-sonnet-5-5", "Sonnet 5.5", supportsFallbacks = true, dynamicWebSearch = true),
            ModelOption("claude-haiku-5-5", "Haiku 5.5", supportsFallbacks = false, dynamicWebSearch = false),
        )
        val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

        fun modelLabel(id: String) = MODELS.firstOrNull { it.id == id }?.label ?: id
    }
}
