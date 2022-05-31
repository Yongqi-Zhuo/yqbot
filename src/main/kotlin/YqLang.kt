package top.saucecode

import kotlinx.coroutines.launch
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.Friend
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.NudgeEvent
import top.saucecode.NodeValue.NodeValue
import top.saucecode.NodeValue.toNodeValue
import top.saucecode.Yqbot.reload
import java.util.*

object YqLang {

    @kotlinx.serialization.Serializable
    data class Storage(val source: String, var symbolTable: String)

    private object YqlangStore: AutoSavePluginData("YqLang") {
        val programs: MutableMap<Long, MutableList<Storage>> by value(mutableMapOf())
    }

    data class Process(val interpreter: RestrictedInterpreter?, val symbolTable: SymbolTable)

    private val states: MutableMap<Long, MutableList<Process>> = mutableMapOf()

    private var clockTask: TimerTask? = null

    class BotContext(scope: Scope, firstRun: Boolean, events: Map<String, NodeValue>, private val getNickName: (Long) -> String): ControlledContext(scope, firstRun, events) {
        override fun nickname(id: Long): String {
            return getNickName(id)
        }
    }

    abstract class ExecutionManager {
        protected abstract val id: Long
        protected abstract val sendMsg: suspend (String) -> Unit
        protected abstract val sendNudge: suspend (Long) -> Unit
        protected abstract val getNickName: (Long) -> String

        private suspend fun runCommand(source: String, run: Boolean, save: Boolean, firstRun: Boolean) {
            val msg = mutableListOf<String>()
            try {
                val st = SymbolTable.createRoot()
                val ast = try {
                    val interpreter = RestrictedInterpreter(source)
                    msg.add("成功编译了程序。")
                    interpreter
                } catch (e: Exception) {
                    sendMsg("程序编译失败${e.javaClass}，参考原因：${e.message}。")
                    return
                }
                if (save) {
                    if (YqlangStore.programs[id] == null) YqlangStore.programs[id] = mutableListOf()
                    if (states[id] == null) states[id] = mutableListOf()
                    YqlangStore.programs[id]!!.add(Storage(source, st.serialize()))
                    states[id]!!.add(Process(ast, st))
                    msg.add("成功添加了程序。")
                }
                val context = BotContext(st, firstRun, mapOf(), getNickName)
                if (run) {
                    try {
                        ast.run(context, reduced = true).collect { reducedOutput ->
                            val output = reducedOutput as Output.Reduced
                            output.text?.let { msg.add(it) }
                            sendMsg(msg.joinToString("\n"))
                            msg.clear()
                            output.nudge?.let { sendNudge(it) }
                        }
                        if (save) {
                            YqlangStore.programs[id]!!.last().symbolTable = st.serialize()
                        }
                    } catch (e: Exception) {
                        msg.add("程序执行出错${e.javaClass}，参考原因：${e.message}。")
                    }
                }
            } catch (e: Exception) {
                msg.add("发生了未知错误${e.javaClass}，参考原因：${e.message}。")
            }
            sendMsg(msg.joinToString("\n"))
        }

        suspend fun eventRunCommand(events: Map<String, NodeValue>) {
            val processes = states[id]
            var index = 0
            processes?.forEach {
                index += 1
                if(it.interpreter != null) {
                    try {
                        val context = BotContext(it.symbolTable, false, events, getNickName)
                        it.interpreter.run(context, reduced = true).collect { reducedOutput ->
                            val output = reducedOutput as Output.Reduced
                            output.text?.let { sendMsg(it) }
                            output.nudge?.let { sendNudge(it) }
                        }
                        YqlangStore.programs[id]!![index - 1].symbolTable = it.symbolTable.serialize()
                    } catch (e: Exception) {
                        sendMsg("程序${index}执行出错${e.javaClass}，参考原因：${e.message}。")
                    }
                }
            }
        }

        private suspend fun listCommands(index: Int? = null) {
            val programs = YqlangStore.programs[id]
            fun retrieve(i: Int, full: Boolean): String {
                val additionalInfo = if(states[id]?.get(i)?.interpreter == null) "（未能激活）" else ""
                val originalSource = programs!![i].source.trim()
                val source = if(full) originalSource else {
                    val lines = originalSource.split("\n")
                    lines.subList(0, minOf(lines.size, 5)).joinToString("\n")
                }
                return "第${i + 1}个程序：$additionalInfo\n源代码：${source}\n全局变量：${states[id]!![i].symbolTable.displaySymbols()}\n\n"
            }
            if(programs == null) {
                sendMsg("还没有程序。")
            } else {
                if (index == null) {
                    var info = "现有程序列表：\n\n"
                    for (i in programs.indices) {
                        info += retrieve(i, false)
                    }
                    info += "程序总数：${programs.size}。输入 /yqlang list <程序编号> 可以查看完整源代码。"
                    sendMsg(info)
                } else {
                    if (index > programs.size) {
                        sendMsg("程序${index}不存在。")
                    } else if (index <= 0) {
                        sendMsg("程序编号是正整数。")
                    } else {
                        sendMsg(retrieve(index - 1, true))
                    }
                }
            }
        }

        private suspend fun removeCommand(index: Int) {
            if(YqlangStore.programs[id]?.indices?.contains(index) == true) {
                YqlangStore.programs[id]!!.removeAt(index)
                states[id]!!.removeAt(index)
                sendMsg("成功删除了这个程序。")
            } else {
                sendMsg("没有找到这个程序。")
            }
        }

        suspend fun respondToTextMessage(rawText: String, sender: Long) {
            if(rawText.startsWith("/yqlang add")) {
                val source = rawText.substring("/yqlang add".length)
                runCommand(source, run = true, save = true, firstRun = true)
            } else if(rawText.startsWith("/yqlang run")) {
                val source = rawText.substring("/yqlang run".length)
                runCommand(source, run = true, save = false, firstRun = true)
            } else if (rawText.startsWith("/yqlang list")) {
                val index = rawText.substring("/yqlang list".length).trim().toIntOrNull()
                if(index != null) {
                    listCommands(index)
                } else {
                    listCommands()
                }
            } else if(rawText.startsWith("/yqlang remove")) {
                var index = rawText.substring("/yqlang remove".length).trim().toIntOrNull()
                index = if(index == null) null else index - 1
                index?.let { removeCommand(it) } ?: sendMsg("请输入要删除的程序序号。")
            } else if(rawText == "/yqlang help") {
                var helpMsg = "/yqlang add <程序> - 添加一个新的程序。\n/yqlang run <程序> - 执行一个程序。\n/yqlang list - 显示所有程序。\n/yqlang remove <序号> - 删除一个程序。\n/yqlang help - 显示帮助信息。\n"
                helpMsg += "当前的内置函数有\n" + Constants.builtinProceduresHelps.keys.joinToString(", ") + "\n可以使用/yqlang help <builtin> 查看具体的帮助信息。"
                sendMsg(helpMsg)
            } else if(rawText.startsWith("/yqlang help ")) {
                val help = rawText.substring("/yqlang help ".length)
                if(Constants.builtinProceduresHelps.containsKey(help)) {
                    sendMsg(Constants.builtinProceduresHelps[help]!!)
                } else {
                    sendMsg("没有找到这个内置函数。")
                }
            } else {
                eventRunCommand(mapOf("text" to rawText.toNodeValue(), "sender" to sender.toNodeValue()))
            }
        }

        companion object {
            fun loadPrograms() {
                YqlangStore.programs.forEach { gs ->
                    states[gs.key] = mutableListOf()
                    gs.value.forEach {
                        val interpreter = try {
                            RestrictedInterpreter(it.source)
                        } catch (e: Exception) {
                            null
                        }
                        val st = SymbolTable.deserialize(it.symbolTable)
                        states[gs.key]!!.add(Process(interpreter, st))
                    }
                }
            }

            fun fromSubject(subject: Contact): ExecutionManager? {
                return when (subject) {
                    is Group -> GroupExecutionManager(subject)
                    is Friend -> FriendExecutionManager(subject)
                    else -> null
                }
            }
        }
    }

    class GroupExecutionManager(group: Group): ExecutionManager() {
        override val id = group.id
        override val sendMsg: suspend (String) -> Unit= { msg: String ->
            if (msg.isNotEmpty()) group.sendMessage(msg)
        }
        override val sendNudge: suspend (Long) -> Unit = { target: Long ->
            group[target]?.nudge()?.sendTo(group)
        }
        override val getNickName: (Long) -> String = { target: Long ->
            group[target]?.nameCardOrNick ?: target.toString()
        }
    }

    class FriendExecutionManager(friend: Friend): ExecutionManager() {
        override val id = friend.id
        override val sendMsg: suspend (String) -> Unit= { msg: String ->
            if (msg.isNotEmpty()) friend.sendMessage(msg)
        }
        override val sendNudge: suspend (Long) -> Unit = { target: Long ->
            if (friend.id == target) {
                friend.nudge().sendTo(friend)
            }
        }
        override val getNickName: (Long) -> String = { target: Long ->
            if (friend.id == target) {
                friend.nameCardOrNick
            } else {
                target.toString()
            }
        }
    }

    fun load() {
        YqlangStore.reload()
        ExecutionManager.loadPrograms()
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<GroupMessageEvent> {
            GroupExecutionManager(group).respondToTextMessage(message.contentToString(), sender.id)
        }
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<FriendMessageEvent> {
            FriendExecutionManager(friend).respondToTextMessage(message.contentToString(), sender.id)
        }
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<NudgeEvent> {
            if (target is Bot) {
                ExecutionManager.fromSubject(subject)?.eventRunCommand(mapOf("nudged" to from.id.toNodeValue()))
            }
        }

        clockTask = object : TimerTask() {
            override fun run() {
                val now = System.currentTimeMillis()
                Bot.instances.map { bot ->
                    for (gid in states.keys) {
                        Yqbot.launch {
                            val subject: Contact? = bot.getGroup(gid) ?: bot.getFriend(gid)
                            subject?.let { ExecutionManager.fromSubject(it) }?.eventRunCommand(mapOf("clock" to now.toNodeValue()))
                        }
                    }
                }
            }
        }
        Timer().schedule(clockTask!!, 1000, 60 * 1000)
    }
    fun unload() {
        clockTask?.cancel()
        clockTask = null
    }
}