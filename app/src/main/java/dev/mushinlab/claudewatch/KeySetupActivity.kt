package dev.mushinlab.claudewatch

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class KeySetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = (application as ClaudeWatchApplication).container.settingsRepository
        intent.getStringExtra(EXTRA_API_KEY)?.let(settings::setApiKey)
        intent.getStringExtra(EXTRA_NOTION_TOKEN)?.let(settings::setNotionToken)
        intent.getStringExtra(EXTRA_NOTION_PROMPT)?.let(settings::setNotionPrompt)
        intent.getStringExtra(EXTRA_NOTION_WRITABLE_PAGES)?.let { pages ->
            settings.setNotionWritablePageIds(
                pages
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet(),
            )
        }
        Toast.makeText(applicationContext, R.string.keys_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_NOTION_TOKEN = "notion_token"
        const val EXTRA_NOTION_PROMPT = "notion_prompt"
        const val EXTRA_NOTION_WRITABLE_PAGES = "notion_writable_pages"
    }
}
