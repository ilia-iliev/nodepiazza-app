package com.nodepiazza.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * LlmService backed by the LiteRT-LM on-device runtime. Falls back to [fallback] on any failure
 * (model file missing, engine init, inference timeout, JSON parse) so the matching feature keeps
 * working even when the model can't run.
 *
 * Lifecycle: the engine is initialised lazily on the first match call (init can take ~10s) and
 * reused across calls. If the selected model path changes between calls, the old engine is closed
 * and a new one is built.
 */
class LiteRtLlmService(
    private val modelPathProvider: () -> String?,
    private val fallback: LlmService,
    private val languageProvider: () -> String? = {
        Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).ifBlank { null }
    },
) : LlmService {

    private val mutex = Mutex()

    @Volatile private var engineForPath: Pair<String, Engine>? = null
    private val failedPaths = mutableSetOf<String>()

    @Volatile private var myInterests: List<String> = emptyList()
    @Volatile private var myAbout: String = ""

    override suspend fun setMyInterests(interests: List<String>) {
        myInterests = interests
        fallback.setMyInterests(interests)
    }

    override suspend fun setMyAbout(about: String) {
        myAbout = about
        fallback.setMyAbout(about)
    }

    override suspend fun match(peerInterests: List<String>): LlmMatch {
        val verdict = tryLlm(peerInterests) ?: return fallback.match(peerInterests)
        val label = verdict.reasonSummary.ifBlank { null }
        return LlmMatch(
            matched = verdict.matched,
            peerInterest = if (verdict.matched) label else null,
        )
    }

    private suspend fun tryLlm(peerInterests: List<String>): MatchVerdict? {
        val prompt = MatchPrompt.build(myInterests, myAbout, peerInterests, languageProvider())
        val raw = withContext(Dispatchers.Default) {
            withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                mutex.withLock {
                    val eng = ensureEngineLocked() ?: return@withLock null
                    runCatching {
                        eng.createConversation(CONVERSATION_CONFIG).use { conv ->
                            conv.sendMessage(prompt).extractText().take(MAX_OUTPUT_CHARS)
                        }
                    }.onFailure { Log.w(TAG, "inference failed", it) }.getOrNull()
                }
            }
        } ?: return null
        return MatchParser.parse(raw).also {
            if (it == null) Log.w(TAG, "could not parse model output, falling back; raw=${raw.take(160)}")
        }
    }

    private fun Message.extractText(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /** Caller must hold [mutex]. */
    private fun ensureEngineLocked(): Engine? {
        val path = modelPathProvider() ?: return null
        engineForPath?.let { (cachedPath, cachedEngine) ->
            if (cachedPath == path) return cachedEngine
            runCatching { cachedEngine.close() }
            engineForPath = null
        }
        if (path in failedPaths) return null
        if (!File(path).isFile) {
            failedPaths += path
            return null
        }
        val eng = tryInitialize(path)
        if (eng == null) {
            failedPaths += path
        } else {
            engineForPath = path to eng
        }
        return eng
    }

    private fun tryInitialize(path: String): Engine? {
        for (backend in listOf(Backend.GPU(), Backend.CPU())) {
            val attempt = runCatching {
                Engine(EngineConfig(modelPath = path, backend = backend)).also { it.initialize() }
            }
            attempt.onFailure { Log.w(TAG, "engine init failed on $backend", it) }
            val eng = attempt.getOrNull()
            if (eng != null) return eng
        }
        return null
    }

    companion object {
        private const val TAG = "LiteRtLlm"
        private const val INFERENCE_TIMEOUT_MS = 30_000L
        private const val MAX_OUTPUT_CHARS = 2_048
        private val CONVERSATION_CONFIG = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = 1,
                topP = 1.0,
                temperature = 0.1,
            ),
        )
    }
}
