package com.nodepiazza

import android.content.Context
import com.nodepiazza.ble.BleCore
import com.nodepiazza.ble.PeerCoordinator
import com.nodepiazza.mlmodels.FilesystemModelRegistry
import com.nodepiazza.mlmodels.ModelBootstrap
import com.nodepiazza.mlmodels.ModelPreferences
import com.nodepiazza.mlmodels.ModelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

object Services {
    lateinit var llm: LlmService
        private set
    lateinit var ble: BleCore
        private set
    lateinit var coordinator: PeerCoordinator
        private set
    lateinit var modelPrefs: ModelPreferences
        private set
    lateinit var modelRegistry: ModelRegistry
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var initialized = false

    fun init(context: Context, state: AppState) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        llm = StubLlmService()
        coordinator = PeerCoordinator(myDeviceId = state.myDeviceId)
        ble = BleCore(app, state, coordinator)
        modelPrefs = ModelPreferences(app)
        val folder = runBlocking { modelPrefs.folderPath.first() }
            ?.let(::File)
            ?: app.getExternalFilesDir("models")
            ?: app.filesDir.resolve("models")
        modelRegistry = FilesystemModelRegistry(folder)
        scope.launch { ModelBootstrap.ensureDefault(app, modelRegistry, folder) }
        scope.launch {
            state.interests.collect { list ->
                llm.setMyInterests(list.map { it.text })
            }
        }
        scope.launch {
            state.aboutMe.collect { about -> llm.setMyAbout(about) }
        }
    }
}
