package com.nodepiazza.phase3

import android.content.Context
import com.nodepiazza.phase3.ble.BleCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object Services {
    lateinit var embedder: EmbeddingService
        private set
    lateinit var llm: LlmService
        private set
    lateinit var ble: BleCore
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var initialized = false

    fun init(context: Context, state: AppState) {
        if (initialized) return
        initialized = true
        embedder = StubEmbeddingService()
        llm = StubLlmService()
        ble = BleCore(context.applicationContext, state)
        scope.launch {
            state.prompts.collect { list ->
                val texts = list.map { it.text }
                embedder.setMyPrompts(texts)
                llm.setMyPrompts(texts)
            }
        }
    }
}
