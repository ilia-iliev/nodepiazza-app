package com.nodepiazza.phase3

/**
 * Stage-1 of the match pipeline: a fast on-device embedding gate run against every newly-seen
 * peer's advertised embeddings. Implementations decide what "matched" means; today the stub uses
 * cosine similarity ≥ [Protocol.MATCH_THRESHOLD].
 *
 * TODO: swap [StubEmbeddingService] for a real on-device embedder (e.g. a quantized sentence
 *  transformer) — keep this interface stable so [BleCore] doesn't need to change.
 */
interface EmbeddingService {
    suspend fun setMyPrompts(prompts: List<String>)
    suspend fun match(peerEmbeddings: List<ByteArray>): EmbeddingMatch
}

data class EmbeddingMatch(
    val matched: Boolean,
    val bestSimilarity: Float,
)

class StubEmbeddingService : EmbeddingService {
    @Volatile
    private var myEmbeddings: List<ByteArray> = emptyList()

    override suspend fun setMyPrompts(prompts: List<String>) {
        myEmbeddings = prompts.map { embed(it).toInt8Bytes() }
    }

    override suspend fun match(peerEmbeddings: List<ByteArray>): EmbeddingMatch {
        if (myEmbeddings.isEmpty() || peerEmbeddings.isEmpty()) {
            return EmbeddingMatch(matched = false, bestSimilarity = 0f)
        }
        var best = 0f
        for (mine in myEmbeddings) for (theirs in peerEmbeddings) {
            val s = cosineInt8(mine, theirs)
            if (s > best) best = s
        }
        return EmbeddingMatch(
            matched = best >= Protocol.MATCH_THRESHOLD,
            bestSimilarity = best,
        )
    }
}
