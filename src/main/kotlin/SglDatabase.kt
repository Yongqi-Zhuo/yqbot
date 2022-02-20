package top.saucecode

import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value
import top.saucecode.Yqbot.reload
import kotlinx.serialization.*

@Serializable
data class ImageSender(val id: Long, val time: Int) {
    val isAnonymous: Boolean
        get() = id == -1L

    companion object {
        val anonymousID = -1L
    }
}

object SglDatabase {
    var defaultThreshold: Int
        get() = SglStore.defaultThreshold
        set(value) {
            SglStore.defaultThreshold = value
        }
    private val hashDatabases = mutableMapOf<Long, ImageHashDatabase>()
    private val senderDatabases = mutableMapOf<Long, MutableList<ImageSender>>()

    private object SglStore : AutoSavePluginData("sgl") {
        var defaultThreshold: Int by value(3)
        var thresholds: MutableMap<Long, Int> by value(mutableMapOf())
        val hashDatabases: MutableMap<Long, MutableList<Long>> by value(mutableMapOf())
        val exempts: MutableMap<Long, MutableSet<Int>> by value(mutableMapOf())
        val senderDatabases: MutableMap<Long, MutableList<ImageSender>> by value(mutableMapOf())
    }

    fun readFromFile() {
        SglStore.reload()
        for ((g, d) in SglStore.hashDatabases) {
            hashDatabases[g] =
                ImageHashDatabase(SglStore.thresholds[g]!!, d, SglStore.exempts[g]!!)
        }
        for ((g, d) in SglStore.senderDatabases) {
            senderDatabases[g] = d
        }
    }

    fun query(g: Long, q: ULong): Int? {
        return hashDatabases[g]?.query(q)
    }

    fun sender(g: Long, index: Int): ImageSender {
        return senderDatabases[g]!![index]
    }

    private fun addGroup(g: Long) {
        SglStore.hashDatabases[g] = mutableListOf()
        SglStore.exempts[g] = mutableSetOf()
        SglStore.senderDatabases[g] = mutableListOf()
        SglStore.thresholds[g] = defaultThreshold
        senderDatabases[g] = SglStore.senderDatabases[g]!!
        hashDatabases[g] = ImageHashDatabase(defaultThreshold, SglStore.hashDatabases[g]!!, SglStore.exempts[g]!!)
    }

    fun addRecord(g: Long, hash: ULong, sender: ImageSender) {
        if (hashDatabases[g] == null) {
            addGroup(g)
        }
        senderDatabases[g]!!.add(sender)
        hashDatabases[g]!!.addHash(hash)
    }

    fun addExempt(g: Long, index: Int) {
        hashDatabases[g]!!.addExempt(index)
    }

    fun hash(g: Long, index: Int) = hashDatabases[g]!!.hash(index)

    fun queryThreshold(): Map<Long, Int> {
        return SglStore.thresholds
    }

    fun queryThreshold(g: Long): Int? {
        return SglStore.thresholds[g]
    }

    fun changeThreshold(newTh: Int) {
        for ((g, d) in hashDatabases) {
            d.threshold = newTh
            SglStore.thresholds[g] = newTh
        }
        defaultThreshold = newTh
    }

    fun changeThreshold(g: Long, newTh: Int) {
        if(SglStore.thresholds[g] == null) {
            addGroup(g)
        }
        SglStore.thresholds[g] = newTh
    }

}