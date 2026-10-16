package dev.mushinlab.claudewatch

import android.content.Context
import dev.mushinlab.claudewatch.data.ChatRepository
import dev.mushinlab.claudewatch.data.ChatStore
import dev.mushinlab.claudewatch.data.ClaudeRemoteDataSource
import dev.mushinlab.claudewatch.data.SecretCipher
import dev.mushinlab.claudewatch.data.SettingsRepository
import dev.mushinlab.claudewatch.service.ChatRequestService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository = SettingsRepository(appContext, SecretCipher())

    val chatRepository =
        ChatRepository(
            settings = settingsRepository,
            store = ChatStore(File(appContext.noBackupFilesDir, "chat.json")),
            remote = ClaudeRemoteDataSource(),
            scope = appScope,
            onRequestStarted = { ChatRequestService.start(appContext) },
        )
}
