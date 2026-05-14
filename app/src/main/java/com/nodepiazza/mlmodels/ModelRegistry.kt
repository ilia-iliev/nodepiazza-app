package com.nodepiazza.mlmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Discovery surface for on-device model files. Sole entry point so the implementation can be
 * swapped (in-process scan today, AIDL/ContentProvider to a separate registry app tomorrow)
 * without touching consumers.
 */
interface ModelRegistry {
    suspend fun listModels(): List<ModelEntry>
}

data class ModelEntry(
    val name: String,
    val path: String,
)

/**
 * Default impl: lists model files in [rootDir]. Returns empty if the folder is missing or
 * inaccessible — callers decide whether that's an error or a "no models yet" state.
 */
class FilesystemModelRegistry(
    private val rootDir: File,
    private val extensions: Set<String> = DEFAULT_EXTENSIONS,
) : ModelRegistry {

    override suspend fun listModels(): List<ModelEntry> = withContext(Dispatchers.IO) {
        val dir = rootDir.takeIf { it.isDirectory } ?: return@withContext emptyList()
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in extensions }
            ?.map { ModelEntry(name = it.name, path = it.absolutePath) }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    companion object {
        val DEFAULT_EXTENSIONS = setOf("tflite", "task", "litertlm")
    }
}
