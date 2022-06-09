package top.saucecode

import kotlinx.coroutines.launch
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.MessageEvent
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

    class ExecutionManager(subject: Contact) {
        private val id: Long = subject.id
        private val sendMsg: suspend (String) -> Unit = { msg ->
            if (msg.isNotEmpty()) subject.sendMessage(msg)
        }
        private val sendNudge: suspend (Long) -> Unit = { target ->
            when (subject) {
                is Group -> subject[target]?.nudge()?.sendTo(subject)
                is User -> if (subject.id == target) subject.nudge().sendTo(subject)
            }
        }
        private val getNickName: (Long) -> String = { target ->
            when (subject) {
                is Group -> subject[target]?.nameCardOrNick
                is User -> if (subject.id == target) subject.nick else null
                else -> null
            } ?: "$target"
        }

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
            processes?.forEach { process ->
                index += 1
                if(process.interpreter != null) {
                    try {
                        val context = BotContext(process.symbolTable, false, events, getNickName)
                        process.interpreter.run(context, reduced = true).collect { reducedOutput ->
                            val output = reducedOutput as Output.Reduced
                            output.text?.let { sendMsg(it) }
                            output.nudge?.let { sendNudge(it) }
                        }
                        YqlangStore.programs[id]!![index - 1].symbolTable = process.symbolTable.serialize()
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

        private suspend fun updateCommand(index: Int, source: String) {
            val programs = YqlangStore.programs[id]
            if (programs?.indices?.contains(index) == true) {
                val interpreter = try {
                    RestrictedInterpreter(source)
                } catch (e: Exception) {
                    sendMsg("程序编译失败${e.javaClass}，参考原因：${e.message}。")
                    return
                }
                programs[index] = Storage(source, programs[index].symbolTable)
                states[id]!![index] = Process(interpreter, states[id]!![index].symbolTable)
                sendMsg("程序${index + 1}更新成功。")
            } else {
                sendMsg("没有找到这个程序。")
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
                val source = rawText.substring("/yqlang add".length).trim()
                runCommand(source, run = true, save = true, firstRun = true)
            } else if(rawText.startsWith("/yqlang run")) {
                val source = rawText.substring("/yqlang run".length).trim()
                runCommand(source, run = true, save = false, firstRun = true)
            } else if (rawText.startsWith("/yqlang list")) {
                val index = rawText.substring("/yqlang list".length).trim().toIntOrNull()
                if(index != null) {
                    listCommands(index)
                } else {
                    listCommands()
                }
            } else if (rawText.startsWith("/yqlang update")) {
                val indexAndSource = rawText.substring("/yqlang update".length).trim().split(" ", "\n", limit = 2)
                if(indexAndSource.size == 2) {
                    val index = indexAndSource[0].toIntOrNull()?.minus(1)
                    if(index != null) {
                        updateCommand(index, indexAndSource[1])
                    } else {
                        sendMsg("程序编号是正整数。")
                    }
                } else {
                    sendMsg("必须输入程序编号和更新后的源代码。")
                }
            } else if(rawText.startsWith("/yqlang remove")) {
                var index = rawText.substring("/yqlang remove".length).trim().toIntOrNull()
                index = if(index == null) null else index - 1
                index?.let { removeCommand(it) } ?: sendMsg("请输入要删除的程序序号。")
            } else if(rawText == "/yqlang help") {
                var helpMsg = "/yqlang add <程序> - 添加一个新的程序。\n/yqlang run <程序> - 执行一个程序。\n/yqlang list - 显示所有程序。\n/yqlang update <序号> <程序> - 更新一个程序。\n/yqlang remove <序号> - 删除一个程序。\n/yqlang help - 显示帮助信息。\n"
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
        }
    }

    fun load() {
        YqlangStore.reload()
        ExecutionManager.loadPrograms()
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<MessageEvent> {
            ExecutionManager(subject).respondToTextMessage(message.contentToString(), sender.id)
        }
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<NudgeEvent> {
            if (target is Bot) {
                ExecutionManager(subject).eventRunCommand(mapOf("nudged" to from.id.toNodeValue()))
            }
        }

        clockTask = object : TimerTask() {
            override fun run() {
                val now = System.currentTimeMillis()
                Bot.instances.map { bot ->
                    for (gid in states.keys) {
                        Yqbot.launch {
                            val subject: Contact? = bot.getGroup(gid) ?: bot.getFriend(gid)
                            subject?.let { ExecutionManager(it) }?.eventRunCommand(mapOf("clock" to now.toNodeValue()))
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