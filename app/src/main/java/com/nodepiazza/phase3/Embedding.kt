package com.nodepiazza.phase3

import java.util.Random
import kotlin.math.sqrt

/** Deterministic bag-of-words random projection into a unit-length float vector. */
fun embed(text: String): FloatArray {
    val vec = FloatArray(Protocol.EMBEDDING_DIMS)
    val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
    for (word in words) {
        val rand = Random(word.hashCode().toLong())
        for (i in vec.indices) vec[i] += rand.nextGaussian().toFloat()
    }
    val norm = sqrt(vec.fold(0.0) { acc, v -> acc + v * v }).toFloat()
    if (norm > 0f) for (i in vec.indices) vec[i] = vec[i] / norm
    return vec
}

/** Quantize a unit-length float vector to int8 bytes. */
fun FloatArray.toInt8Bytes(): ByteArray {
    val out = ByteArray(size)
    for (i in indices) {
        val v = (this[i] * 127f).coerceIn(-127f, 127f).toInt()
        out[i] = v.toByte()
    }
    return out
}

/** Cosine similarity between two int8-quantized embeddings. */
fun cosineInt8(a: ByteArray, b: ByteArray): Float {
    if (a.size != b.size || a.isEmpty()) return 0f
    var dot = 0
    var na = 0
    var nb = 0
    for (i in a.indices) {
        val x = a[i].toInt()
        val y = b[i].toInt()
        dot += x * y
        na += x * x
        nb += y * y
    }
    if (na == 0 || nb == 0) return 0f
    return (dot / (sqrt(na.toDouble()) * sqrt(nb.toDouble()))).toFloat()
}
