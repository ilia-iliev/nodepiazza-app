package com.nodepiazza.mlmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ModelEntry(
    val name: String,
    val path: String,
)

/**
 * Lists model files in [rootDir]. Returns empty if the folder is missing or inaccessible —
 * callers decide whether that's an error or a "no models yet" state.
 */
class ModelRegistry(private val rootDir: File) {

    suspend fun listModels(): List<ModelEntry> = withContext(Dispatchers.IO) {
        val dir = rootDir.takeIf { it.isDirectory } ?: return@withContext emptyList()
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }
            ?.map { ModelEntry(name = it.name, path = it.absolutePath) }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    private companion object {
        val EXTENSIONS = setOf("tflite", "task", "litertlm")
    }
}
