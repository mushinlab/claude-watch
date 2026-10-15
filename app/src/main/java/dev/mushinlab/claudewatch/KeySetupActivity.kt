package dev.mushinlab.claudewatch

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class KeySetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        intent.getStringExtra(EXTRA_API_KEY)?.let { settings.apiKey = it }
        intent.getStringExtra(EXTRA_NOTION_TOKEN)?.let { settings.notionToken = it }
        intent.getStringExtra(EXTRA_NOTION_PROMPT)?.let { settings.notionPrompt = it }
        Toast.makeText(applicationContext, "Ключи сохранены", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_NOTION_TOKEN = "notion_token"
        const val EXTRA_NOTION_PROMPT = "notion_prompt"
    }
}
