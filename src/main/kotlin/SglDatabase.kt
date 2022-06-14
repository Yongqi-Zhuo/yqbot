package top.saucecode

import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value
import top.saucecode.Yqbot.reload
import kotlinx.serialization.*
import net.mamoe.mirai.utils.info
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name

@Serializable
data class ImageSender(val id: Long, val time: Int) {
    val isAnonymous: Boolean
        get() = id == -1L

    companion object {
        const val anonymousID = -1L
    }
}

object SglDatabase: AutoCloseable {
    val connection: Connection by lazy {
        Class.forName("org.sqlite.JDBC");
        val databaseFile = Yqbot.dataFolderPath.toString() + "/sgl.db"
        Yqbot.logger.info { "Using database: $databaseFile" }
        DriverManager.getConnection("jdbc:sqlite:$databaseFile")
    }
    private val hashDatabases = ConcurrentHashMap<Long, ImageHashDatabase>()
    override fun close() {
        connection.close()
    }

    var defaultThreshold: Int
        get() = SglManager.defaultThreshold
        set(value) {
            SglManager.defaultThreshold = value
        }

    fun load() {
        connection.autoCommit = false
        val statement = connection.createStatement()

        // the table that stores all hashes
        statement.executeUpdate("""
            create table if not exists sgl (
                id integer primary key, 
                gid int8 not null,
                hash int8 not null,
                senderId int8 not null,
                senderTime int not null,
                exempt boolean not null
            );
        """.trimIndent())
        connection.commit()

        // the table that stores group ids
        statement.executeUpdate("""
            create table if not exists groups (
                id int8 primary key not null,
                threshold int not null
            );
        """.trimIndent())
        connection.commit()

        val groups = statement.executeQuery("select id, threshold from groups;")
        while (groups.next()) {
            val groupId = groups.getLong("id")
            val threshold = groups.getInt("threshold")
            val individualStatement = connection.createStatement()
            val grs = individualStatement.executeQuery("select id, hash, exempt from sgl where gid = $groupId;")
            val hashes = mutableMapOf<Int, Long>()
            val exempts = mutableSetOf<Int>()
            while (grs.next()) {
                val id = grs.getInt("id")
                val hash = grs.getLong("hash")
                val exempt = grs.getBoolean("exempt")
                hashes[id] = hash
                if (exempt) {
                    exempts.add(id)
                }
            }
            grs.close()
            individualStatement.close()
            hashDatabases[groupId] = ImageHashDatabase(threshold, hashes, exempts)
        }
        groups.close()

        statement.close()
    }

    fun query(g: Long, q: ULong): Int? {
        return hashDatabases[g]?.query(q)
    }

    fun sender(g: Long, index: Int): ImageSender {
        val statement = connection.createStatement()
        val rs = statement.executeQuery("select senderId, senderTime from sgl where id = $index;")
        rs.next()
        val senderId = rs.getLong("senderId")
        val senderTime = rs.getInt("senderTime")
        rs.close()
        statement.close()
        return ImageSender(senderId, senderTime)
    }

    private fun getHashDb(g: Long): ImageHashDatabase {
        return hashDatabases.computeIfAbsent(g) {
            val statement = connection.prepareStatement("insert into groups (id, threshold) values (?, ?);")
            statement.setLong(1, g)
            statement.setInt(2, defaultThreshold)
            statement.executeUpdate()
            connection.commit()
            statement.close()
            ImageHashDatabase(defaultThreshold, mutableMapOf(), mutableSetOf())
        }
    }

    fun addRecord(g: Long, hash: ULong, sender: ImageSender): Int {
        val hashDb = getHashDb(g)
        val statement = connection.prepareStatement("insert into sgl (id, gid, hash, senderId, senderTime, exempt) values (null, ?, ?, ?, ?, ?);", Statement.RETURN_GENERATED_KEYS)
        statement.setLong(1, g)
        statement.setLong(2, hash.toLong())
        statement.setLong(3, sender.id)
        statement.setInt(4, sender.time)
        statement.setBoolean(5, false)
        statement.executeUpdate()
        connection.commit()
        val rs = statement.generatedKeys
        rs.next()
        val index = rs.getInt(1)
        rs.close()
        statement.close()
        hashDb.addHash(index, hash)
        return index
    }

    fun addExempt(g: Long, index: Int) {
        val statement = connection.prepareStatement("update sgl set exempt = true where id = ?;")
        statement.setInt(1, index)
        statement.executeUpdate()
        connection.commit()
        statement.close()
        hashDatabases[g]!!.addExempt(index)
    }

    fun hash(g: Long, index: Int) = hashDatabases[g]!!.hash(index)

    fun queryThreshold(): Map<Long, Int> {
        val statement = connection.createStatement()
        val rs = statement.executeQuery("select id, threshold from groups;")
        val res = mutableMapOf<Long, Int>()
        while (rs.next()) {
            val id = rs.getLong("id")
            val threshold = rs.getInt("threshold")
            res[id] = threshold
        }
        rs.close()
        statement.close()
        return res
    }

    fun queryThreshold(g: Long): Int? {
        val statement = connection.createStatement()
        val rs = statement.executeQuery("select threshold from groups where id = $g;")
        if (rs.next()) {
            val threshold = rs.getInt("threshold")
            rs.close()
            statement.close()
            return threshold
        }
        rs.close()
        statement.close()
        return null
    }

    fun changeThreshold(newTh: Int) {
        val statement = connection.prepareStatement("update groups set threshold = ?;")
        statement.setInt(1, newTh)
        statement.executeUpdate()
        connection.commit()
        statement.close()
        hashDatabases.values.forEach { it.setThreshold(newTh) }
        defaultThreshold = newTh
    }

    fun changeThreshold(g: Long, newTh: Int) {
        getHashDb(g).setThreshold(newTh)
        val statement = connection.prepareStatement("update groups set threshold = ? where id = ?;")
        statement.setInt(1, newTh)
        statement.setLong(2, g)
        statement.executeUpdate()
        connection.commit()
        statement.close()
        hashDatabases[g]!!.setThreshold(newTh)
    }

}
