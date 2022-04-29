package top.saucecode

import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.PlainText

object Chatbot {
    fun reply(question: String): String {
        return "？"
    }
    fun load() {
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<GroupMessageEvent> {
            if(message.filterIsInstance<At>().filter { it.target == bot.id }.isEmpty() == false) {
                val question = message.filterIsInstance<PlainText>().joinToString { it.content }
                group.sendMessage(reply(question))
            }
        }
    }
    fun unload() {

    }
}