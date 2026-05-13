package com.nodepiazza

import android.content.Context
import com.nodepiazza.ble.BleCore
import com.nodepiazza.ble.PeerCoordinator
import com.nodepiazza.llm.CachingLlmService
import com.nodepiazza.llm.LiteRtLlmService
import com.nodepiazza.mlmodels.FilesystemModelRegistry
import com.nodepiazza.mlmodels.ModelBootstrap
import com.nodepiazza.mlmodels.ModelDownloadState
import com.nodepiazza.mlmodels.ModelDownloadStatus
import com.nodepiazza.mlmodels.ModelEntry
import com.nodepiazza.mlmodels.ModelPreferences
import com.nodepiazza.mlmodels.ModelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
    lateinit var modelsFolder: File
        private set
    lateinit var downloadState: StateFlow<ModelDownloadState>
        private set

    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

    private val _selectedModelPath = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var initialized = false

    fun init(context: Context, state: AppState) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        llm = CachingLlmService(
            inner = LiteRtLlmService(
                modelPathProvider = { _selectedModelPath.value },
                fallback = StubLlmService(),
            ),
        )
        coordinator = PeerCoordinator(myDeviceId = state.myDeviceId)
        ble = BleCore(app, state, coordinator)
        modelPrefs = ModelPreferences(app)
        modelsFolder = runBlocking { modelPrefs.folderPath.first() }
            ?.let(::File)
            ?: app.getExternalFilesDir("models")
            ?: app.filesDir.resolve("models")
        modelRegistry = FilesystemModelRegistry(modelsFolder)
        downloadState = ModelDownloadStatus.observe(app)
            .stateIn(scope, SharingStarted.Eagerly, ModelDownloadState.Idle)

        scope.launch { ModelBootstrap.ensureDefault(app, modelRegistry, modelsFolder) }
        scope.launch { refreshModels() }
        scope.launch {
            // Re-scan whenever the download finishes so a newly-arrived file shows up.
            downloadState.collect { state ->
                if (state is ModelDownloadState.Succeeded) refreshModels()
            }
        }
        scope.launch {
            // Auto-select the first available model whenever the selection is empty or stale, and
            // expose the resolved file path so the LLM service can (re)load on changes.
            combine(_models, modelPrefs.selectedModelName) { list, selected -> list to selected }
                .collect { (list, selected) ->
                    val needsPick = selected.isNullOrBlank() || list.none { it.name == selected }
                    if (needsPick && list.isNotEmpty()) {
                        modelPrefs.setSelectedModelName(list.first().name)
                    }
                    val resolved = list.firstOrNull { it.name == selected }
                        ?: list.firstOrNull()
                    _selectedModelPath.value = resolved?.path
                }
        }
        scope.launch {
            state.interests.collect { list ->
                llm.setMyInterests(list.map { it.text })
            }
        }
        scope.launch {
            state.aboutMe.collect { about -> llm.setMyAbout(about) }
        }
    }

    suspend fun refreshModels() {
        _models.value = modelRegistry.listModels()
    }
}
