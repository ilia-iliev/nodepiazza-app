package com.nodepiazza

import android.content.Context
import com.nodepiazza.ble.BleCore
import com.nodepiazza.ble.BleScanService
import com.nodepiazza.ble.PeerCoordinator
import com.nodepiazza.llm.CachingLlmService
import com.nodepiazza.llm.LiteRtLlmService
import com.nodepiazza.llm.LlmService
import com.nodepiazza.llm.StubLlmService
import com.nodepiazza.mlmodels.ModelBootstrap
import com.nodepiazza.mlmodels.ModelCatalog
import com.nodepiazza.mlmodels.ModelDownloadState
import com.nodepiazza.mlmodels.ModelDownloadStatus
import com.nodepiazza.mlmodels.ModelEntry
import com.nodepiazza.mlmodels.ModelPreferences
import com.nodepiazza.mlmodels.ModelRegistry
import com.nodepiazza.mlmodels.ModelSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var modelRegistry: ModelRegistry
    private lateinit var appContext: Context
    lateinit var modelsFolder: File
        private set

    /** Download state per [ModelSpec.id], so the UI can show each model's progress independently. */
    lateinit var downloadStates: StateFlow<Map<String, ModelDownloadState>>
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
        appContext = app
        llm = CachingLlmService(
            inner = LiteRtLlmService(
                modelPathProvider = { _selectedModelPath.value },
                fallback = StubLlmService(),
            ),
        )
        coordinator = PeerCoordinator(
            myDeviceId = state.myDeviceId,
            initiallyBlocked = state.blockedDeviceIds.value,
            onMatchDismissed = { deviceId -> BleScanService.cancelMatch(app, deviceId) },
        )
        ble = BleCore(app, state, coordinator)
        modelPrefs = ModelPreferences(app)
        modelsFolder = app.getExternalFilesDir("models")
            ?: app.filesDir.resolve("models")
        modelRegistry = ModelRegistry(modelsFolder)
        val perModel = ModelCatalog.ALL.map { spec ->
            ModelDownloadStatus.observe(app, spec).map { spec.id to it }
        }
        downloadStates = combine(perModel) { pairs -> pairs.toMap() }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                ModelCatalog.ALL.associate { it.id to ModelDownloadState.Idle },
            )

        scope.launch { refreshModels() }
        scope.launch {
            // Re-scan whenever any download finishes so a newly-arrived file shows up.
            downloadStates.collect { states ->
                if (states.values.any { it is ModelDownloadState.Succeeded }) refreshModels()
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
                llm.setMyInterests(list.filterNot { it.placeholder }.map { it.text })
            }
        }
        scope.launch {
            state.aboutMe.collect { about -> llm.setMyAbout(about) }
        }
    }

    /**
     * Cancels any in-flight download for [spec], then removes the model file and any partial
     * download from disk. Cancelling first stops the worker from re-creating the `.part` file we're
     * about to delete; it's a no-op when nothing is queued.
     */
    suspend fun deleteModel(spec: ModelSpec) {
        ModelBootstrap.cancel(appContext, spec)
        withContext(Dispatchers.IO) {
            File(modelsFolder, spec.filename).delete()
            File(modelsFolder, spec.filename + ".part").delete()
        }
        refreshModels()
    }

    private suspend fun refreshModels() {
        _models.value = modelRegistry.listModels()
    }
}
