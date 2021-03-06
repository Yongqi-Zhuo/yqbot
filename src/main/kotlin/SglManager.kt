package top.saucecode

import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.unregister
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.CompositeCommand
import net.mamoe.mirai.console.command.MemberCommandSender
import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.console.permission.AbstractPermitteeId
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission
import net.mamoe.mirai.console.permission.PermissionService.Companion.permit
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.MessageRecallEvent
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.MessageSource.Key.quote
import top.saucecode.ImageHasher.Companion.dctHash
import top.saucecode.ImageHasher.Companion.distance
import top.saucecode.Yqbot.logger
import top.saucecode.Yqbot.reload
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.schedule

object SglManager {

    private lateinit var toBeIgnored: MutableMap<Long, MutableMap<Int, Int>>
    var enabled: Boolean
        get() = SglConfigStore.enabled
        set(value) {
            SglConfigStore.enabled = value
        }
    private lateinit var shutup: MutableSet<Long>

    private object SglConfigStore : AutoSavePluginData("sglconfig") {
        val toBeIgnored: MutableMap<Long, MutableMap<Int, Int>> by value(mutableMapOf())
        var enabled: Boolean by value(true)
        val shutup: MutableSet<Long> by value(mutableSetOf())
        var defaultThreshold: Int by value(3)
    }
    var defaultThreshold: Int
        get() = SglConfigStore.defaultThreshold
        set(value) {
            SglConfigStore.defaultThreshold = value
        }

    data class MessageLocator(val ids: IntArray, val internalId: IntArray, val time: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as MessageLocator
            if (!ids.contentEquals(other.ids)) return false
            if (!internalId.contentEquals(other.internalId)) return false
            if (time != other.time) return false
            return true
        }
        override fun hashCode(): Int {
            var result = ids.contentHashCode()
            result = 31 * result + internalId.contentHashCode()
            result = 31 * result + time
            return result
        }
    }

    private var antiRecall: MutableMap<Long, MutableMap<MessageLocator, List<Image>>> = mutableMapOf()

    fun load() {
        SglConfigStore.reload()
        toBeIgnored = SglConfigStore.toBeIgnored
        shutup = SglConfigStore.shutup
        SglDatabase.load()
        SglCommand.register()

        Yqbot.registerImageLoadedMessageListener { bufferedImages ->
            if (!enabled || shutup.contains(subject.id)) return@registerImageLoadedMessageListener
            var imgCnt = 0
            val collector: MutableMap<ImageSender, MutableList<Int>> = mutableMapOf()
            val repeated = mutableListOf<Image>()
            var sglCount = 0
            var notSglYet = true
            val forwardFlag = message[ForwardMessage] != null
            val messageChains: List<MessageChain> =
                message[ForwardMessage]?.nodeList?.map { it.messageChain } ?: listOf(message)
            val thisSender = ImageSender(if (sender is AnonymousMember) ImageSender.anonymousID else sender.id, time)
            messageChains.forEach { chain -> chain.forEach chainForeach@{
                if ((it !is Image) || it.isEmoji) return@chainForeach
                imgCnt += 1
                Yqbot.logger.debug("In total ${bufferedImages.size} buffered images. They are: ${bufferedImages.joinToString(", ") { it.first }}. Now processing image ${it.imageId}")
                val hash = bufferedImages.first { pair -> pair.first == it.imageId }.second.dctHash()
                val q = SglDatabase.query(subject.id, hash)
                if (q != null) {
                    // sgl
                    Yqbot.logger.debug("Matched. Distance: ${SglDatabase.hash(subject.id, q) distance hash}")
                    val isender = SglDatabase.sender(subject.id, q)
                    if(isender == thisSender) {
                        return@chainForeach
                    }
                    if (collector[isender] == null) collector[isender] = mutableListOf()
                    collector[isender]!!.add(imgCnt)
                    repeated.add(it)
                    sglCount += 1
                    if (notSglYet) {
                        if (toBeIgnored[subject.id] == null) {
                            toBeIgnored[subject.id] = mutableMapOf()
                        } else {
                            toBeIgnored[subject.id]!!.clear()
                        }
                        notSglYet = false
                    }
                    toBeIgnored[subject.id]!![imgCnt] = q
                } else {
                    // ???sg
                    SglDatabase.addRecord(subject.id, hash, thisSender)
                }
            } }
            if (sglCount == 0) return@registerImageLoadedMessageListener
            val format = SimpleDateFormat("yyyy???MM???dd???HH???mm???ss???")
            fun getUserName(target: Long): String {
                return when (subject) {
                    is Group -> (subject as Group)[target]?.nameCardOrNick
                    is User -> if (subject.id == target) (subject as User).nick else null
                    else -> null
                } ?: "$target"
            }
            val msg = "????????????" +
                    (if (forwardFlag) "??????????????????" else "") +
                    (if (imgCnt == 1) "????????????" else "??????????????????") +
                    (if (collector.size == 1) "" else "???\n  ") +
                    collector.entries.joinToString(separator = (if (collector.size == 1) "???" else "???\n  ")) { (isender: ImageSender, ids: MutableList<Int>) ->
                        return@joinToString (if (imgCnt == 1) "" else "???${ids.joinToString(separator = "???") { it.toString() }}???") +
                                "???${TimeAgo.fromTimeStamp(isender.time * 1000L)}" +
                                "???${format.format(Date(isender.time * 1000L))}???" +
                                (if (isender.isAnonymous)
                                    "???????????????" else
                                    "???${getUserName(isender.id)}" + "???${isender.id}???")
                    } +
                    (if (collector.size == 1) "" else "\n") +
                    "????????????" +
                    (if (sglCount == 1)
                        "??????????????????????????????????????? /sgl ignore ????????????" else
                        "????????????????????????????????????????????? /sgl ignore [??????????????????] ????????????")
            try {
                subject.sendMessage(buildMessageChain {
                    +message.quote()
                    +PlainText(msg)
                })
            } catch (e: Exception) {
                logger.warning("????????????????????????${e.message}")
            }
            // ??????
            val locator = MessageLocator(message.ids, message.internalId, message.time)
            if (!antiRecall.contains(subject.id)) antiRecall[subject.id] = mutableMapOf()
            antiRecall[subject.id]!![locator] = repeated
            Timer("VoidAntiRecallMemory", false).schedule(300000) {
                antiRecall[subject.id]!!.remove(locator)
            }
        }
        // FriendRecall ???????????????
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<MessageRecallEvent.GroupRecall> {
            if (!enabled || shutup.contains(group.id)) return@subscribeAlways
            if (authorId != operator?.id) return@subscribeAlways
            val locator = MessageLocator(messageIds, messageInternalIds, messageTime)
            if (antiRecall[group.id]?.contains(locator) == true) {
                val imgs = antiRecall[group.id]!!.remove(locator)!!
                try {
                    group.sendMessage(buildMessageChain {
                        +PlainText("${author.nameCardOrNick}???${authorId}??????yqbot??????????????????????????????????????????????????????")
                        +imgs.toMessageChain()
                    })
                } catch (e: Exception) {
                    logger.warning("????????????????????????${e.message}")
                }
            }
        }
        AbstractPermitteeId.AnyUser.permit(SglCommand.permission)
    }

    fun unload() {
        SglCommand.unregister()
    }

    fun shutup(gid: Long) {
        shutup.add(gid)
    }

    fun resume(gid: Long) {
        shutup.remove(gid)
    }

    fun ignore(gid: Long): Boolean {
        val exempt = toBeIgnored[gid]
        if (exempt != null && exempt.size == 1) {
            exempt.forEach { (_, e) ->
                SglDatabase.addExempt(gid, e)
            }
            toBeIgnored.remove(gid)
            return true
        }
        return false
    }

    fun ignore(gid: Long, index: Int): Boolean {
        val exempt = toBeIgnored[gid]?.get(index)
        if (exempt != null) {
            SglDatabase.addExempt(gid, exempt)
            toBeIgnored[gid]!!.remove(index)
            return true
        }
        return false
    }

}

object SglCommand : CompositeCommand(
    Yqbot, "sgl", "?????????",
    description = "????????????????????????"
) {
    @SubCommand
    suspend fun CommandSender.help() {
        sendMessage(
            """??????yqbot?????????sgl???
            |/sgl on ??????sgl
            |/sgl off ??????sgl
            |/sgl shutup ???????????????sgl
            |/sgl resume ???????????????sgl
            |/sgl ignore ??????????????????sgl
            |/sgl threshold ???????????????????????????0~7?????????????????????????????????????????????sgl""".trimMargin()
        )
    }

    @SubCommand
    suspend fun CommandSender.on() {
        if (!hasPermission(Yqbot.adminPermission)) {
            sendMessage("???????????????????????????????????????")
            return
        }
        SglManager.enabled = true
        sendMessage("?????????sgl???")
    }

    @SubCommand
    suspend fun CommandSender.off() {
        if (!hasPermission(Yqbot.adminPermission)) {
            sendMessage("???????????????????????????????????????")
            return
        }
        SglManager.enabled = false
        sendMessage("?????????sgl???")
    }

    @SubCommand
    suspend fun MemberCommandSender.shutup() {
        SglManager.shutup(group.id)
        sendMessage("sgl????????????")
    }

    @SubCommand
    suspend fun MemberCommandSender.resume() {
        SglManager.resume(group.id)
        sendMessage("sgl???????????????")
    }

    @SubCommand
    suspend fun MemberCommandSender.ignore() {
        if (SglManager.ignore(group.id)) {
            sendMessage("?????????????????????sgl???")
        } else {
            sendMessage("??????????????????????????????")
        }
    }

    @SubCommand
    suspend fun MemberCommandSender.manual() {
        if (user.isOperator()) {
            sendMessage("????????????")
        }
    }

    @SubCommand
    suspend fun MemberCommandSender.ignore(index: Int) {
        if (SglManager.ignore(group.id, index)) {
            sendMessage("?????????????????????sgl???")
        } else {
            sendMessage("??????????????????????????????")
        }
    }

    @SubCommand
    suspend fun CommandSender.threshold() {
        if (this is MemberCommandSender) {
            sendMessage("??????????????????${SglDatabase.queryThreshold(this.group.id) ?: SglDatabase.defaultThreshold}")
        } else {
            if (hasPermission(Yqbot.adminPermission)) {
                sendMessage("????????????????????????\n" + SglDatabase.queryThreshold().entries.joinToString(separator = "\n") { (g, t) -> "$g: $t" })
            } else {
                sendMessage("?????????????????????????????????")
            }
        }
    }

    @SubCommand
    suspend fun CommandSender.threshold(thres: Int) {
        if ((0..7).contains(thres)) {
            if (this is MemberCommandSender) {
                SglDatabase.changeThreshold(this.group.id, thres)
                sendMessage("?????????????????????$thres???")
            } else {
                if (hasPermission(Yqbot.adminPermission)) {
                    SglDatabase.changeThreshold(thres)
                    sendMessage("??????????????????????????????$thres???")
                } else {
                    sendMessage("?????????????????????????????????")
                }
            }
        } else {
            sendMessage("???????????????0~7???")
        }
    }
}