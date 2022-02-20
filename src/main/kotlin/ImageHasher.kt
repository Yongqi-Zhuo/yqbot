package top.saucecode

import top.saucecode.ImageHasher.Companion.distance
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

class ImageHasher {
    companion object {

        private fun dctMatrix(dim: Int): Array<Array<Double>> {
            val c = sqrt(2.0 / dim)
            return Array(dim) { row -> Array(dim) { col -> c * cos((0.5 + col) * row * PI / dim) } }
        }

        private val dct32 = dctMatrix(32)

        fun BufferedImage.dctHash(): ULong {
            val scl = BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY)
            scl.graphics.drawImage(this, 0, 0, scl.width, scl.height, 0, 0, this.width, this.height, null)
            val dim = 32
            val dct = Array(dim) outer@{ row ->
                return@outer Array(dim) inner@{ col ->
                    var acc = 0.0
                    for (k in 0 until dim) {
                        for (l in 0 until dim) {
                            acc += dct32[row][k] * dct32[col][l] * scl.getRGB(k, l)
                        }
                    }
                    return@inner acc
                }
            }
            val serial = Array(64) { i -> dct[i / 8][i % 8] }
            var hash = 0UL
            val sc = serial.sorted()
            val med = (sc[31] + sc[32]) / 2
            for (i in 0 until 64) {
                if (serial[i] > med) {
                    hash = hash or 1UL
                }
                hash = hash shl 1
            }
            return hash
        }

        infix fun ULong.distance(other: ULong): Int {
            return (this xor other).countOneBits()
        }
    }
}

class ImageHashDatabase(
    var threshold: Int,
    private val hashes: MutableList<Long>,
    private val exempt: MutableSet<Int>
) {
    private val chunks = List(8) { List(256) { mutableListOf<Int>() } }
    private fun addToChunks(index: Int, hash: ULong) {
        var h = hash
        for (tier in 0 until 8) {
            chunks[7 - tier][(h % 256UL).toInt()].add(index)
            h = h shr 8
        }
    }

    init {
        for (index in 0 until hashes.size) {
            addToChunks(index, hashes[index].toULong())
        }
    }

    private fun checkNode(node: MutableList<Int>, q: ULong): Int? {
        for (h in node) {
            if ((q distance hashes[h].toULong()) <= threshold) {
                return h
            }
        }
        return null
    }

    fun query(q: ULong): Int? {
        synchronized(hashes) {
            var h = q
            for (tier in 0 until 8) {
                if (chunks[7 - tier][(h % 256UL).toInt()].size > 0) {
                    val res = checkNode(chunks[7 - tier][(h % 256UL).toInt()], q)
                    if (res != null) {
                        if (exempt.contains(res)) {
                            return null
                        }
                        return res
                    }
                }
                h = h shr 8
            }
            return null
        }
    }

    fun addHash(hash: ULong) {
        synchronized(hashes) {
            hashes.add(hash.toLong())
            addToChunks(hashes.lastIndex, hash)
        }
    }

    fun addExempt(index: Int) {
        exempt.add(index)
    }

    fun hash(index: Int) = hashes[index].toULong()
}