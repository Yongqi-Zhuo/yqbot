package top.saucecode

import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.unregister
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.CompositeCommand
import net.mamoe.mirai.console.command.ConsoleCommandSender
import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.console.permission.AbstractPermitteeId
import net.mamoe.mirai.console.permission.PermissionService.Companion.permit
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.code.MiraiCode.deserializeMiraiCode
import net.mamoe.mirai.message.data.*
import top.saucecode.Yqbot.reload

object Repeater {

    object RepeaterStore : AutoSavePluginConfig("repeaterconfig") {
        var threshold: Int by value(3)
        var probability: Int by value(50)
    }

    private class RepeaterState {
        var repeated: Boolean = false
        var messageContent: String? = null
        var count: Int = 0
        fun reset() {
            repeated = false
            messageContent = null
            count = 0
        }

        fun input(message: MessageChain): String? {
            val accepted = message.all {
                if (it is MessageContent) {
                    if (it !is PlainText && it !is Image && it !is Face) return@all false
                } else if (it is MessageMetadata) {
                    if (it is QuoteReply || it is ShowImageFlag) return@all false
                }
                return@all true
            }
            if (accepted) {
                val encodedMsg = message.serializeToMiraiCode()
                if(messageContent == encodedMsg) {
                    count += 1
                    if (!repeated && count >= RepeaterStore.threshold && (1..100).random() <= RepeaterStore.probability) {
                        repeated = true
                        return messageContent
                    }
                } else {
                    reset()
                    messageContent = encodedMsg
                    count = 1
                }
            } else {
                reset()
            }
            return null
        }
    }

    private val counter: MutableMap<Long, RepeaterState> = mutableMapOf()

    fun load() {
        RepeaterStore.reload()
        RepeaterCommand.register()
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<GroupMessageEvent> {
            if (group.id !in counter) {
                counter[group.id] = RepeaterState()
            }
            val whatToDo = counter[group.id]!!.input(it.message)
            if(whatToDo != null) {
                group.sendMessage(whatToDo.deserializeMiraiCode(group))
            }
        }
        AbstractPermitteeId.AnyUser.permit(RepeaterCommand.permission)
    }

    fun unload() {
        RepeaterCommand.unregister()
    }

}

object RepeaterCommand: CompositeCommand(
    Yqbot, "repeat", "复读",
    description = "Yqbot的本质是复读机"
) {
    @SubCommand
    suspend fun CommandSender.help() {
        sendMessage(
            """这是yqbot的模块repeater。
            |/repeat threshold 开始复读的消息数量阈值，默认为3
            |/repeat probability 复读的概率，为百分数，默认为50""".trimMargin()
        )
    }
    @SubCommand
    suspend fun CommandSender.threshold() {
        ConsoleCommandSender.sendMessage("当前复读的消息数量阈值为${Repeater.RepeaterStore.threshold}")
    }
    @SubCommand
    suspend fun CommandSender.threshold(threshold: Int) {
        if (threshold < 1) {
            sendMessage("阈值不能小于1")
            return
        }
        Repeater.RepeaterStore.threshold = threshold
        sendMessage("阈值已更新为$threshold")
    }
    @SubCommand
    suspend fun CommandSender.probability() {
        ConsoleCommandSender.sendMessage("当前复读的概率为${Repeater.RepeaterStore.probability}")
    }
    @SubCommand
    suspend fun CommandSender.probability(probability: Int) {
        if (probability < 1 || probability > 100) {
            sendMessage("概率必须在1~100之间")
            return
        }
        Repeater.RepeaterStore.probability = probability
        sendMessage("概率已更新为$probability")
    }
}