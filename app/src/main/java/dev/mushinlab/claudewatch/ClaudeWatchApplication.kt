package dev.mushinlab.claudewatch

import android.app.Application

class ClaudeWatchApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
