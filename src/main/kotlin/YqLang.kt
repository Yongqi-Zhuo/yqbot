package top.saucecode

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.NudgeEvent
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.ExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.uploadAsImage
import net.mamoe.mirai.utils.info
import top.saucecode.NodeValue.ListValue
import top.saucecode.NodeValue.NodeValue
import top.saucecode.NodeValue.toNodeValue
import top.saucecode.Yqbot.reload
import java.awt.image.BufferedImage
import java.io.File
import java.util.*
import javax.imageio.ImageIO

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

    class PerGroupStorage(gid: Long) {
        val path = Yqbot.dataFolderPath.toString() + "/yqlang/$gid"
        fun getPicture(picId: String): ExternalResource? {
            val f = File(path, picId)
            return if (f.exists()) {
                f.toExternalResource()
            } else {
                null
            }
        }
        fun savePicture(picId: String, image: BufferedImage) {
            val f = File(path, picId)
            f.parentFile.mkdirs()
            // extract extension from picId
            val ext = picId.substring(picId.lastIndexOf('.') + 1)
            ImageIO.write(image, ext, f)
        }
    }

    class ExecutionManager(private val subject: Contact) {
        private val id: Long = subject.id
        private val sendMsg: suspend (MessageChain?) -> Unit = { msg ->
            if (msg != null && msg.any { it is PlainText || it is Image }) subject.sendMessage(msg)
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
        private val storage: PerGroupStorage = PerGroupStorage(id)

        private fun String.toMsg(): MessageChain? = if (this.isNotEmpty()) this.toPlainText().toMessageChain() else null

        private suspend fun runProcess(process: Process, pid: Int, context: ControlledContext, images: Map<String, BufferedImage>?, save: Storage? = null) {
            if (process.interpreter == null) return
            coroutineScope { try {
                process.interpreter.run(context).collect { outputs ->
                    val builder = MessageChainBuilder()
                    outputs.forEach { output ->
                        when (output) {
                            is Output.Text -> builder.add(output.text + "\n")
                            is Output.PicSend -> {
                                if (images?.containsKey(output.picId) == true) {
                                    // recent, resend
                                    Yqbot.logger.info { "resend pic ${output.picId}" }
                                    builder.add(Image(output.picId))
                                } else {
                                    // read from storage
                                    val image = storage.getPicture(output.picId)?.use {
                                        it.uploadAsImage(subject)
                                    }
                                    Yqbot.logger.info { "read pic $image" }
                                    image?.let { builder.add(it) }
                                }
                            }
                            is Output.PicSave -> {
                                images?.get(output.picId)?.let { storage.savePicture(output.picId, it) }
                            }
                            is Output.Nudge -> {
                                launch {
                                    sendNudge(output.target)
                                }
                            }
                        }
                    }
                    val msg = builder.build()
                    launch {
                        sendMsg(msg)
                    }
                }
                if (save != null) {
                    save.symbolTable = process.symbolTable.serialize()
                }
            } catch (e: Exception) {
                sendMsg("程序${if (pid != 0) pid else ""}执行出错${e.javaClass}，参考原因：${e.message}。".toMsg())
            } }
        }

        private suspend fun runCommand(source: String, images: Map<String, BufferedImage>?, run: Boolean, save: Boolean, firstRun: Boolean) {
            try {
                val st = SymbolTable.createRoot()
                val ast = try {
                    val interpreter = RestrictedInterpreter(source)
                    interpreter
                } catch (e: Exception) {
                    sendMsg("程序编译失败${e.javaClass}，参考原因：${e.message}。".toMsg())
                    return
                }
                val process = Process(ast, st)
                val storage = if (save) {
                    if (YqlangStore.programs[id] == null) YqlangStore.programs[id] = mutableListOf()
                    if (states[id] == null) states[id] = mutableListOf()
                    val tmpStorage = Storage(source, st.serialize())
                    YqlangStore.programs[id]!!.add(tmpStorage)
                    states[id]!!.add(process)
                    tmpStorage
                } else null
                if (run) {
                    val imgList = images?.keys?.map { it.toNodeValue() }?.toNodeValue() ?: ListValue(mutableListOf())
                    val context = BotContext(st, firstRun, mapOf("images" to imgList), getNickName)
                    runProcess(process, 0, context, images, storage)
                }
            } catch (e: Exception) {
                sendMsg("发生了未知错误${e.javaClass}，参考原因：${e.message}。".toMsg())
            }
        }

        suspend fun eventRunCommand(events: Map<String, NodeValue>, images: Map<String, BufferedImage>?) {
            val processes = states[id]
            var index = 0
            coroutineScope { processes?.forEach { process ->
                index += 1
                if (process.interpreter != null) {
                    launch {
                        val context = BotContext(process.symbolTable, false, events, getNickName)
                        runProcess(process, index, context, images, YqlangStore.programs[id]!![index - 1])
                    }
                }
            }}
        }

        private suspend fun listCommands(index: Int? = null) {
            val programs = YqlangStore.programs[id]
            fun retrieve(i: Int, full: Boolean): String {
                val additionalInfo = if(states[id]?.get(i)?.interpreter == null) "（未能激活）" else ""
                val originalSource = programs!![i].source.trim()
                val source = if(full) originalSource else {
                    val lines = originalSource.split("\n")
                    val limit = 5
                    val croppedLines = lines.size - limit
                    lines.take(limit).joinToString("\n") + if(croppedLines > 0) "\n...（省略${croppedLines}行）" else ""
                }
                return "第${i + 1}个程序：$additionalInfo\n源代码：${source}\n全局变量：${states[id]!![i].symbolTable.displaySymbols()}\n\n"
            }
            if(programs == null) {
                sendMsg("还没有程序。".toMsg())
            } else {
                if (index == null) {
                    var info = "现有程序列表：\n\n"
                    for (i in programs.indices) {
                        info += retrieve(i, false)
                    }
                    info += "程序总数：${programs.size}。输入 /yqlang list <程序编号> 可以查看完整源代码。"
                    sendMsg(info.toMsg())
                } else {
                    if (index > programs.size) {
                        sendMsg("程序${index}不存在。".toMsg())
                    } else if (index <= 0) {
                        sendMsg("程序编号是正整数。".toMsg())
                    } else {
                        sendMsg(retrieve(index - 1, true).toMsg())
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
                    sendMsg("程序编译失败${e.javaClass}，参考原因：${e.message}。".toMsg())
                    return
                }
                programs[index] = Storage(source, programs[index].symbolTable)
                states[id]!![index] = Process(interpreter, states[id]!![index].symbolTable)
                sendMsg("程序${index + 1}更新成功。".toMsg())
            } else {
                sendMsg("没有找到这个程序。".toMsg())
            }
        }

        private suspend fun removeCommand(index: Int) {
            if(YqlangStore.programs[id]?.indices?.contains(index) == true) {
                YqlangStore.programs[id]!!.removeAt(index)
                states[id]!!.removeAt(index)
                sendMsg("成功删除了这个程序。".toMsg())
            } else {
                sendMsg("没有找到这个程序。".toMsg())
            }
        }

        suspend fun respondToMessageEvent(rawText: String, sender: Long, images: Map<String, BufferedImage>) {
            val segments = rawText.trim().split(Utility.whitespace, limit = 3)
            if (segments.size >= 2 &&
                segments[0] == "/yqlang"
            ) {
                // a command
                when (val predicate = segments[1]) {
                    "add", "run" -> {
                        if (segments.size == 3) {
                            val source = segments[2]
                            when (predicate) {
                                "add" -> runCommand(source, images, run = true, save = true, firstRun = true)
                                "run" -> runCommand(source, images, run = true, save = false, firstRun = true)
                            }
                        } else {
                            sendMsg("语法错误：/yqlang add <源代码> 或 /yqlang run <源代码>。".toMsg())
                        }
                    }
                    "list" -> {
                        if (segments.size == 2) {
                            listCommands()
                        } else if (segments.size == 3) {
                            segments[2].toIntOrNull()?.let { listCommands(it) } ?:
                                sendMsg("语法错误：/yqlang list <程序编号>。".toMsg())
                        }
                    }
                    "update" -> {
                        var ok = false
                        if (segments.size == 3) {
                            val indexAndSource = segments[2].split(Utility.whitespace, limit = 2)
                            if (indexAndSource.size == 2) {
                                val index = indexAndSource[0].toIntOrNull()?.minus(1)
                                if (index != null) {
                                    val source = indexAndSource[1]
                                    updateCommand(index, source)
                                    ok = true
                                }
                            }
                        }
                        if (!ok) {
                            sendMsg("语法错误：/yqlang update <程序编号> <源代码>。".toMsg())
                        }
                    }
                    "remove" -> {
                        var ok = false
                        if (segments.size == 3) {
                            val index = segments[2].toIntOrNull()?.minus(1)
                            if (index != null) {
                                removeCommand(index)
                                ok = true
                            }
                        }
                        if (!ok) {
                            sendMsg("语法错误：/yqlang remove <程序编号>。".toMsg())
                        }
                    }
                    "help" -> {
                        if (segments.size == 2) {
                            sendMsg(helpMessage.toMsg())
                        } else if (segments.size == 3) {
                            val help = segments[2].trim()
                            if(Constants.builtinProceduresHelps.containsKey(help)) {
                                sendMsg(Constants.builtinProceduresHelps[help]!!.toMsg())
                            } else {
                                sendMsg("没有找到这个内置函数。".toMsg())
                            }
                        }
                    }
                    else -> {
                        sendMsg("目前没有这个功能：$predicate。可以输入 /yqlang help 查看帮助信息。".toMsg())
                    }
                }
            } else if (segments.size == 1 &&
                segments[0] == "/yqlang" ) {
                sendMsg(("""
                    yqlang是yqbot的一个简单的脚本语言，可以用来编写脚本实现特定的功能。语言手册可以在群文件中找到。
                    程序可以只执行一次（/yqlang run），也可以设置为在事件发生时被调用（/yqlang add）。
                    支持的指令如下：
                """.trimIndent() + "\n" + helpMessage).toMsg())
            } else {
                // a message
                eventRunCommand(mapOf(
                    "text" to rawText.toNodeValue(),
                    "sender" to sender.toNodeValue(),
                    "images" to images.keys.map { it.toNodeValue() }.toNodeValue()
                ), images)
            }
        }

        companion object {
            val helpMessage = """
                /yqlang add <程序> - 添加一个新的程序，在每次新事件到来时都会触发执行。
                /yqlang run <程序> - 执行一个程序。
                /yqlang list - 显示所有程序及其全局变量。
                /yqlang list <程序编号> - 显示指定程序的完整源代码。
                /yqlang update <序号> <程序> - 更新一个程序，而不影响当前保存的全局变量。
                /yqlang remove <序号> - 删除一个程序。
                /yqlang help - 显示帮助信息。
                /yqlang help <内置函数> - 显示指定函数的帮助信息。
                """.trimIndent()
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
        Yqbot.registerImageLoadedMessageListener { images ->
            ExecutionManager(subject).respondToMessageEvent(message.contentToString(), sender.id, images)
        }
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<NudgeEvent> {
            if (target is Bot) {
                ExecutionManager(subject).eventRunCommand(mapOf("nudged" to from.id.toNodeValue()), null)
            }
        }

        clockTask = object : TimerTask() {
            override fun run() {
                val now = System.currentTimeMillis()
                Bot.instances.map { bot ->
                    for (gid in states.keys) {
                        Yqbot.launch {
                            val subject: Contact? = bot.getGroup(gid) ?: bot.getFriend(gid)
                            subject?.let { ExecutionManager(it) }?.eventRunCommand(mapOf("clock" to now.toNodeValue()), null)
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