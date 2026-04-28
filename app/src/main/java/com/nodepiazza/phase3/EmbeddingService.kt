package com.nodepiazza.phase3

interface EmbeddingService {
    suspend fun setMyPrompts(prompts: List<String>)
    suspend fun match(peerEmbeddings: List<ByteArray>): EmbeddingMatch
}

data class EmbeddingMatch(
    val matched: Boolean,
    val pairScores: List<List<Float>>,
)

class StubEmbeddingService : EmbeddingService {
    @Volatile
    private var myEmbeddings: List<ByteArray> = emptyList()

    override suspend fun setMyPrompts(prompts: List<String>) {
        myEmbeddings = prompts.map { embed(it).toInt8Bytes() }
    }

    override suspend fun match(peerEmbeddings: List<ByteArray>): EmbeddingMatch {
        val scores = myEmbeddings.map { mine ->
            peerEmbeddings.map { theirs -> cosineInt8(mine, theirs) }
        }
        val best = scores.flatten().maxOrNull() ?: 0f
        return EmbeddingMatch(
            matched = best >= Protocol.MATCH_THRESHOLD,
            pairScores = scores,
        )
    }
}
