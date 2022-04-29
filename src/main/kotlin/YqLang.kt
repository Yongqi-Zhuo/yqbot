package top.saucecode

import kotlinx.coroutines.launch
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.contact.Friend
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.NudgeEvent
import top.saucecode.NodeValue.NodeValue
import top.saucecode.NodeValue.ProcedureValue
import top.saucecode.NodeValue.toNodeValue
import top.saucecode.Yqbot.reload
import java.util.*

object YqLang {

    @kotlinx.serialization.Serializable
    data class Storage(val source: String, var symbolTable: String)

    private object YqlangStore: AutoSavePluginData("YqLang") {
        val programs: MutableMap<Long, MutableList<Storage>> by value(mutableMapOf())
    }

    data class Process(val interpreter: Interpreter?, val symbolTable: SymbolTable) {
        fun resetEvents() {
            symbolTable.remove("text")
            symbolTable.remove("sender")
            symbolTable.remove("clock")
            symbolTable.remove("nudged")
        }
    }

    private val states: MutableMap<Long, MutableList<Process>> = mutableMapOf()

    private var clockTask: TimerTask? = null

    class BotContext(scope: Scope, declarations: MutableMap<String, ProcedureValue>, firstRun: Boolean, private val sendNudge: suspend (Long) -> Unit, private val getNickName: (Long) -> String): ControlledContext(scope, declarations, firstRun) {
        private var nudges: Long? = null
        override fun nudge(target: Long) {
            nudges = target
        }
        override fun dumpOutput(): String {
            val str = super.dumpOutput()
            if (nudges != null) {
                Yqbot.launch { nudges?.let { sendNudge(it) } }
//                nudges = null
            }
            return str
        }
        override fun nickname(id: Long): String {
            return getNickName(id)
        }
    }

    private suspend fun runCommand(id: Long, sendMsg: suspend (String) -> Unit, sendNudge: suspend (Long) -> Unit, getNickName: (Long) -> String, source: String, run: Boolean, save: Boolean, firstRun: Boolean) {
        val msg = mutableListOf<String>()
        try {
            val st = SymbolTable.createRoot()
            val ast = try {
                val interpreter = Interpreter(source, true)
                msg.add("成功编译了程序。")
                interpreter
            } catch (e: Exception) {
                sendMsg("程序编译失败，参考原因：${e.message}。")
                return
            }
            val context = BotContext(st, ast.declarations, firstRun, sendNudge, getNickName)
            if (save) {
                if (YqlangStore.programs[id] == null) YqlangStore.programs[id] = mutableListOf()
                if (states[id] == null) states[id] = mutableListOf()
                YqlangStore.programs[id]!!.add(Storage(source, st.serialize()))
                states[id]!!.add(Process(ast, st))
                msg.add("成功添加了程序。")
            }
            if (run) {
                try {
                    ast.run(context)
                    msg += context.dumpOutput()
                    if (save) {
                        YqlangStore.programs[id]!!.last().symbolTable = st.serialize()
                    }
                } catch (e: Exception) {
                    msg.add("程序执行出错，参考原因：${e.message}。")
                }
            }
        } catch (e: Exception) {
            msg.add("发生了未知错误。参考原因：${e.message}。")
        }
        sendMsg(msg.joinToString("\n"))
    }

    private suspend fun eventRunCommand(id: Long, sendMsg: suspend (String) -> Unit, sendNudge: suspend (Long) -> Unit, getNickName: (Long) -> String, events: Map<String, NodeValue>) {
        val procs = states[id]
        var index = 0
        procs?.forEach {
            index += 1
            if(it.interpreter != null) {
                it.resetEvents()
                events.forEach { e ->
                    it.symbolTable[e.key] = e.value
                }
                try {
                    val context = BotContext(it.symbolTable, it.interpreter.declarations, false, sendNudge, getNickName)
                    it.interpreter.run(context)
                    sendMsg(context.dumpOutput())
                    YqlangStore.programs[id]!![index - 1].symbolTable = it.symbolTable.serialize()
                } catch (e: Exception) {
                    sendMsg("程序${index}执行出错，参考原因：${e.message}。")
                }
            }
        }
    }

    private suspend fun listCommands(id: Long, sendMsg: suspend (String) -> Unit, index: Int? = null) {
        val progs = YqlangStore.programs[id]
        fun retrieve(i: Int, full: Boolean): String {
            val additionalInfo = if(states[id]?.get(i)?.interpreter == null) "（未能激活）" else ""
            val originalSource = progs!![i].source.trim()
            val source = if(full) originalSource else {
                val lines = originalSource.split("\n")
                lines.subList(0, minOf(lines.size, 5)).joinToString("\n")
            }
            return "第${i + 1}个程序：$additionalInfo\n源代码：${source}\n全局变量：${states[id]!![i].symbolTable.symbols}\n\n"
        }
        if(progs == null) {
            sendMsg("还没有程序。")
        } else {
            if (index == null) {
                var info = "现有程序列表：\n\n"
                for (i in progs.indices) {
                    info += retrieve(i, false)
                }
                info += "程序总数：${progs.size}。输入 /yqlang list <程序编号> 可以查看完整源代码。"
                sendMsg(info)
            } else {
                if (index > progs.size) {
                    sendMsg("程序${index}不存在。")
                } else if (index <= 0) {
                    sendMsg("程序编号是正整数。")
                } else {
                    sendMsg(retrieve(index - 1, true))
                }
            }
        }
    }

    private suspend fun removeCommand(id: Long, sendMsg: suspend (String) -> Unit, index: Int) {
        if(YqlangStore.programs[id]?.indices?.contains(index) == true) {
            YqlangStore.programs[id]!!.removeAt(index)
            states[id]!!.removeAt(index)
            sendMsg("成功删除了这个程序。")
        } else {
            sendMsg("没有找到这个程序。")
        }
    }

    fun load() {
        YqlangStore.reload()
        YqlangStore.programs.forEach { gs ->
            states[gs.key] = mutableListOf()
            gs.value.forEach {
                val interpreter = try {
                    Interpreter(it.source, true)
                } catch (e: Exception) {
                    null
                }
                val st = SymbolTable.deserialize(it.symbolTable)
                states[gs.key]!!.add(Process(interpreter, st))
            }
        }
        suspend fun addTriggers(rawText: String, id: Long, sender: Long, sendMsg: suspend (String) -> Unit, sendNudge: suspend (Long) -> Unit, getNickName: (Long) -> String) {
            if(rawText.startsWith("/yqlang add")) {
                val source = rawText.substring("/yqlang add".length)
                runCommand(id, sendMsg, sendNudge, getNickName, source, run = true, save = true, firstRun = true)
            } else if(rawText.startsWith("/yqlang run")) {
                val source = rawText.substring("/yqlang run".length)
                runCommand(id, sendMsg, sendNudge, getNickName, source, run = true, save = false, firstRun = true)
            } else if (rawText.startsWith("/yqlang list")) {
                val index = rawText.substring("/yqlang list".length).trim().toIntOrNull()
                if(index != null) {
                    listCommands(id, sendMsg, index)
                } else {
                    listCommands(id, sendMsg)
                }
            } else if(rawText.startsWith("/yqlang remove")) {
                var index = rawText.substring("/yqlang remove".length).trim().toIntOrNull()
                index = if(index == null) null else index - 1
                index?.let { removeCommand(id, sendMsg, it) } ?: sendMsg("请输入要删除的程序序号。")
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
                eventRunCommand(id, sendMsg, sendNudge, getNickName, mapOf("text" to rawText.toNodeValue(), "sender" to sender.toNodeValue()))
            }
        }
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<GroupMessageEvent> {
            val id = group.id
            val sendMsg: suspend (String) -> Unit= { msg: String ->
                if (msg.isNotEmpty()) group.sendMessage(msg)
            }
            val sendNudge: suspend (Long) -> Unit = { target: Long ->
                group[target]?.nudge()?.sendTo(group)
            }
            val getNickName: (Long) -> String = { target: Long ->
                group[target]?.nameCardOrNick ?: target.toString()
            }
            addTriggers(message.contentToString(), id, sender.id, sendMsg, sendNudge, getNickName)
        }
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<NudgeEvent> {
            if (target is Bot) {
                val id = subject.id
                val sendMsg: suspend (String) -> Unit= { msg: String ->
                    if (msg.isNotEmpty()) subject.sendMessage(msg)
                }
                val sendNudge: suspend (Long) -> Unit = { target: Long ->
                    when (subject) {
                        is Group -> (subject as Group)[target]?.nudge()?.sendTo(subject)
                        is Friend -> if (subject.id == target) {
                            (subject as Friend).nudge().sendTo(subject)
                        }
                    }
                }
                val getNickName: (Long) -> String = { target: Long ->
                    when (subject) {
                        is Group -> (subject as Group)[target]?.nameCardOrNick ?: target.toString()
                        is Friend -> if (subject.id == target) {
                            (subject as Friend).nameCardOrNick
                        } else {
                            target.toString()
                        }
                        else -> target.toString()
                    }
                }
                eventRunCommand(id, sendMsg, sendNudge, getNickName, mapOf("nudged" to from.id.toNodeValue()))
            }
        }
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<FriendMessageEvent> {
            val id = friend.id
            val sendMsg: suspend (String) -> Unit= { msg: String ->
                if (msg.isNotEmpty()) friend.sendMessage(msg)
            }
            val sendNudge: suspend (Long) -> Unit = { target: Long ->
                if (friend.id == target) {
                    friend.nudge().sendTo(friend)
                }
            }
            val getNickName: (Long) -> String = { target: Long ->
                if (friend.id == target) {
                    friend.nameCardOrNick
                } else {
                    target.toString()
                }
            }
            addTriggers(message.contentToString(), id, sender.id, sendMsg, sendNudge, getNickName)
        }
        clockTask = object : TimerTask() {
            override fun run() {
                val now = System.currentTimeMillis()
                for (gid in states.keys) {
                    Yqbot.launch {
                        eventRunCommand(gid, { msg: String ->
                            if (msg.isNotEmpty()) {
                                Bot.instances.map { bot ->
                                    bot.getGroup(gid)?.sendMessage(msg) ?: bot.getFriend(gid)?.sendMessage(msg)
                                }
                            }
                        }, { target: Long ->
                            Bot.instances.map { bot ->
                                bot.getGroup(gid)?.let { g -> g[target]?.nudge()?.sendTo(g) } ?: bot.getFriend(gid)?.let { friend ->
                                    if (friend.id == target) {
                                        friend.nudge().sendTo(friend)
                                    }
                                }
                            }
                        }, { id: Long ->
                            Bot.instances.map { bot ->
                                bot.getGroup(gid)?.let { g -> g[id]?.nameCardOrNick } ?: bot.getFriend(gid)?.let { friend ->
                                    if (friend.id == id) {
                                        friend.nameCardOrNick
                                    } else {
                                        null
                                    }
                                } ?: id.toString()
                            }.first()
                        }, mapOf("clock" to now.toNodeValue()))
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