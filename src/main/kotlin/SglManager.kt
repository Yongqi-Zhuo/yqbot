package top.saucecode

import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.unregister
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.CompositeCommand
import net.mamoe.mirai.console.command.MemberCommandSender
import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission
import net.mamoe.mirai.contact.AnonymousMember
import net.mamoe.mirai.contact.isOperator
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageRecallEvent
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.message.data.MessageSource.Key.quote
import top.saucecode.ImageHasher.Companion.dctHash
import top.saucecode.ImageHasher.Companion.distance
import top.saucecode.Yqbot.logger
import top.saucecode.Yqbot.reload
import java.io.IOException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import javax.imageio.ImageIO
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
        SglDatabase.readFromFile()
        SglCommand.register()
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<GroupMessageEvent> {
            if (!enabled || shutup.contains(group.id)) return@subscribeAlways
            var imgCnt = 0
            val collector: MutableMap<ImageSender, MutableList<Int>> = mutableMapOf()
            val repeated = mutableListOf<Image>()
            var sglCount = 0
            var notSglYet = true
            val forwardFlag = message[ForwardMessage] != null
            val messageChains: List<MessageChain> =
                message[ForwardMessage]?.nodeList?.map { it.messageChain } ?: listOf(message)
            val thisSender = ImageSender(if (sender is AnonymousMember) ImageSender.anonymousID else sender.id, time)
            messageChains.forEach { chain ->
                chain.forEach chainForeach@{
                    if ((it !is Image) || it.isEmoji) return@chainForeach
                    imgCnt += 1
                    Yqbot.logger.debug("Image found! Downloading...")
                    try {
                        val image = ImageIO.read(URL(it.queryUrl()))
                        val hash = image.dctHash()
                        Yqbot.logger.debug("Downloaded. Hash equals $hash")
                        val q = SglDatabase.query(group.id, hash)
                        if (q != null) {
                            // sgl
                            Yqbot.logger.debug("Matched. Distance: ${SglDatabase.hash(group.id, q) distance hash}")
                            val isender = SglDatabase.sender(group.id, q)
                            if(isender == thisSender) {
                                return@chainForeach
                            }
                            if (collector[isender] == null) collector[isender] = mutableListOf()
                            collector[isender]!!.add(imgCnt)
                            repeated.add(it)
                            sglCount += 1
                            if (notSglYet) {
                                if (toBeIgnored[group.id] == null) {
                                    toBeIgnored[group.id] = mutableMapOf()
                                } else {
                                    toBeIgnored[group.id]!!.clear()
                                }
                                notSglYet = false
                            }
                            toBeIgnored[group.id]!![imgCnt] = q
                        } else {
                            // 没sg
                            SglDatabase.addRecord(group.id, hash, thisSender)
                        }
                    } catch (e: IOException) {
                        Yqbot.logger.warning("Failed to download image. Reason: ${e.message}")
                    } catch (e: NullPointerException) {
                        Yqbot.logger.warning("Null pointer found when processing image. Reason: ${e.message}")
                    }
                }
            }
            if (sglCount == 0) return@subscribeAlways
            val format = SimpleDateFormat("yyyy年MM月dd日HH时mm分ss秒")
            val msg = "水过啦！" +
                    (if (forwardFlag) "转发消息里的" else "") +
                    (if (imgCnt == 1) "这张图片" else "这些图片中的") +
                    (if (collector.size == 1) "" else "：\n  ") +
                    collector.entries.joinToString(separator = (if (collector.size == 1) "，" else "，\n  ")) { (isender: ImageSender, ids: MutableList<Int>) ->
                        return@joinToString (if (imgCnt == 1) "" else "第${ids.joinToString(separator = "、") { it.toString() }}张") +
                                "在${TimeAgo.fromTimeStamp(isender.time * 1000L)}" +
                                "（${format.format(Date(isender.time * 1000L))}）" +
                                (if (isender.isAnonymous)
                                    "由匿名用户" else
                                    "由${group[isender.id]?.nameCardOrNick ?: ""}" +
                                            "（${isender.id}）")
                    } +
                    (if (collector.size == 1) "" else "\n") +
                    "水过了。" +
                    (if (sglCount == 1)
                        "如果这是一张表情包，请发送 /sgl ignore 来忽略。" else
                        "如果这些图片中有表情包，请发送 /sgl ignore [要忽略的序号] 来忽略。")
            try {
                group.sendMessage(buildMessageChain {
                    +message.quote()
                    +PlainText(msg)
                })
            } catch (e: Exception) {
                logger.warning("发送失败。原因：${e.message}")
            }
            // 鞭尸
            val locator = MessageLocator(message.ids, message.internalId, message.time)
            if (!antiRecall.contains(group.id)) antiRecall[group.id] = mutableMapOf()
            antiRecall[group.id]!![locator] = repeated
            Timer("VoidAntiRecallMemory", false).schedule(300000) {
                antiRecall[group.id]!!.remove(locator)
            }
        }
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<MessageRecallEvent.GroupRecall> {
            if (!enabled || shutup.contains(group.id)) return@subscribeAlways
            if (authorId != operator?.id) return@subscribeAlways
            val locator = MessageLocator(messageIds, messageInternalIds, messageTime)
            if (antiRecall[group.id]?.contains(locator) == true) {
                val imgs = antiRecall[group.id]!!.remove(locator)!!
                try {
                    group.sendMessage(buildMessageChain {
                        +PlainText("${author.nameCardOrNick}（${authorId}）被yqbot查重后把消息撤回啦！这里是查重记录：")
                        +imgs.toMessageChain()
                    })
                } catch (e: Exception) {
                    logger.warning("发送失败。原因：${e.message}")
                }
            }
        }
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
    Yqbot, "sgl", "水过了",
    description = "这张图片水过了！"
) {
    @SubCommand
    suspend fun CommandSender.help() {
        sendMessage(
            """这是yqbot的模块sgl。
            |/sgl on 开启sgl
            |/sgl off 关闭sgl
            |/sgl shutup 在群内禁用sgl
            |/sgl resume 在群内启用sgl
            |/sgl ignore 屏蔽一张图的sgl
            |/sgl threshold 调整图片相似阈值（0~7），表示识别容错率，越高越容易sgl""".trimMargin()
        )
    }

    @SubCommand
    suspend fun CommandSender.on() {
        if (!hasPermission(Yqbot.adminPermission)) {
            sendMessage("只有管理者可以使用总开关。")
            return
        }
        SglManager.enabled = true
        sendMessage("已开启sgl。")
    }

    @SubCommand
    suspend fun CommandSender.off() {
        if (!hasPermission(Yqbot.adminPermission)) {
            sendMessage("只有管理者可以使用总开关。")
            return
        }
        SglManager.enabled = false
        sendMessage("已关闭sgl。")
    }

    @SubCommand
    suspend fun MemberCommandSender.shutup() {
        SglManager.shutup(group.id)
        sendMessage("sgl已闭嘴。")
    }

    @SubCommand
    suspend fun MemberCommandSender.resume() {
        SglManager.resume(group.id)
        sendMessage("sgl继续服务。")
    }

    @SubCommand
    suspend fun MemberCommandSender.ignore() {
        if (SglManager.ignore(group.id)) {
            sendMessage("不再对这张图片sgl。")
        } else {
            sendMessage("找不到要忽略的图片。")
        }
    }

    @SubCommand
    suspend fun MemberCommandSender.manual() {
        if (user.isOperator()) {
            sendMessage("水过啦！")
        }
    }

    @SubCommand
    suspend fun MemberCommandSender.ignore(index: Int) {
        if (SglManager.ignore(group.id, index)) {
            sendMessage("不再对这张图片sgl。")
        } else {
            sendMessage("找不到要忽略的图片。")
        }
    }

    @SubCommand
    suspend fun CommandSender.threshold() {
        if (this is MemberCommandSender) {
            sendMessage("当前阈值为：${SglDatabase.queryThreshold(this.group.id) ?: SglDatabase.defaultThreshold}")
        } else {
            if (hasPermission(Yqbot.adminPermission)) {
                sendMessage("每个群的阈值为：\n" + SglDatabase.queryThreshold().entries.joinToString(separator = "\n") { (g, t) -> "$g: $t" })
            } else {
                sendMessage("无权查看每个群的阈值。")
            }
        }
    }

    @SubCommand
    suspend fun CommandSender.threshold(thres: Int) {
        if ((0..7).contains(thres)) {
            if (this is MemberCommandSender) {
                SglDatabase.changeThreshold(this.group.id, thres)
                sendMessage("成功更改阈值为$thres。")
            } else {
                if (hasPermission(Yqbot.adminPermission)) {
                    SglDatabase.changeThreshold(thres)
                    sendMessage("已设置所有群的阈值为$thres。")
                } else {
                    sendMessage("无权设置所有群的阈值。")
                }
            }
        } else {
            sendMessage("阈值只能取0~7。")
        }
    }
}