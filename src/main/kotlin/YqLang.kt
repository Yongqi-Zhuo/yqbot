package top.saucecode

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.console.util.ContactUtils.getContactOrNull
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.NudgeEvent
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.Image.Key.IMAGE_ID_REGEX
import net.mamoe.mirai.utils.ExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.uploadAsImage
import top.saucecode.yqlang.*
import top.saucecode.Yqbot.reload
import top.saucecode.yqlang.Runtime.Memory
import java.awt.image.BufferedImage
import java.io.File
import java.util.*
import javax.imageio.ImageIO

object YqLang {

    @kotlinx.serialization.Serializable
    data class Storage(
        var source: String,
        var memory: String
        )

    private object YqlangStore: AutoSavePluginData("YqLang") {
        val programs: MutableMap<Long, MutableList<Storage>> by value(mutableMapOf())
    }

    data class Process(val interpreter: RestrictedContainer?)

    private val states: MutableMap<Long, MutableList<Process>> = mutableMapOf()

    private var clockTask: TimerTask? = null

    class BotContext(firstRun: Boolean, events: List<Event>, private val getNickName: (Long) -> String): ControlledContext(firstRun, events) {
        override fun nickname(id: Long): String {
            return getNickName(id)
        }
    }

    class PerGroupStorage(gid: Long) {
        private val path = Yqbot.dataFolderPath.toString() + "/yqlang/$gid"
        fun getPicture(picId: String): ExternalResource? {
            if (!IMAGE_ID_REGEX.matches(picId)) return null
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

    class PerGroupExecutionManager(private val subject: Contact) {
        private val id: Long
            get() = subject.id
        private suspend fun sendMsg(msg: MessageChain?) {
            if (msg != null && msg.any { it is PlainText || it is Image }) subject.sendMessage(msg)
        }
        private suspend fun sendNudge(target: Long) {
            when (subject) {
                is Group -> subject[target]?.nudge()?.sendTo(subject)
                is User -> if (subject.id == target) subject.nudge().sendTo(subject)
            }
        }
        private fun getNickName(target: Long): String {
            return when (subject) {
                is Group -> subject[target]?.nameCardOrNick
                is User -> if (subject.id == target) subject.nick else null
                else -> null
            } ?: "$target"
        }
        private val storage: PerGroupStorage = PerGroupStorage(id)

        private fun String.toMsg(): MessageChain? = if (this.isNotEmpty()) this.toPlainText().toMessageChain() else null

        private suspend fun runProcess(process: Process, pid: Int, context: ControlledContext, images: List<Pair<String, BufferedImage>>?, save: Storage? = null) {
            if (process.interpreter == null) return
            coroutineScope {
                process.interpreter.run(context).catch { e ->
                    sendMsg("??????${if (pid != 0) pid else ""}????????????${e.javaClass}??????????????????${e.message}???".toMsg())
                }.collect { outputs ->
                    var lastIsText = false
                    val builder = MessageChainBuilder()
                    outputs.forEach { output ->
                        when (output) {
                            is Output.Text -> {
                                if (lastIsText) {
                                    builder.add(PlainText("\n"))
                                }
                                builder.add(output.text)
                                lastIsText = true
                            }
                            is Output.PicSend -> {
                                if (images?.any { it.first == output.picId } == true) {
                                    // recent, resend
                                    builder.add(Image(output.picId))
                                } else {
                                    // read from storage
                                    val image = storage.getPicture(output.picId)?.use {
                                        it.uploadAsImage(subject)
                                    }
                                    image?.let { builder.add(it) } ?: builder.add(PlainText("\n${output.picId}\n"))
                                }
                                lastIsText = false
                            }
                            is Output.PicSave -> {
                                images?.firstOrNull { it.first == output.picId }
                                    ?.let { storage.savePicture(output.picId, it.second) }
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
                    save.memory = process.interpreter.memory.serialize()
                }
            }
        }

        private suspend fun commandRun(source: String, images: List<Pair<String, BufferedImage>>?, run: Boolean, save: Boolean, firstRun: Boolean) {
            try {
                val ast = try {
                    RestrictedContainer(source, null)
                } catch (e: Exception) {
                    sendMsg("??????????????????${e.javaClass}??????????????????${e.message}???".toMsg())
                    return
                }
                val process = Process(ast)
                val storage = if (save) {
                    if (YqlangStore.programs[id] == null) YqlangStore.programs[id] = mutableListOf()
                    if (states[id] == null) states[id] = mutableListOf()
                    val tmpStorage = Storage(source, ast.memory.serialize())
                    YqlangStore.programs[id]!!.add(tmpStorage)
                    states[id]!!.add(process)
                    tmpStorage
                } else null
                if (run) {
                    val imgList = images?.map { it.first }?.let { ImagesEvent(it) } ?: ImagesEvent(emptyList())
                    val context = BotContext(firstRun, listOf(imgList), ::getNickName)
                    runProcess(process, 0, context, images, storage)
                }
            } catch (e: Exception) {
                sendMsg("?????????????????????${e.javaClass}??????????????????${e.message}???".toMsg())
            }
        }

        suspend fun eventRun(events: List<Event>, images: List<Pair<String, BufferedImage>>?) {
            val processes = states[id]
            coroutineScope { processes?.forEachIndexed { index, process ->
                if (process.interpreter != null) {
                    launch {
                        val context = BotContext(false, events, ::getNickName)
                        runProcess(process, index + 1, context, images, YqlangStore.programs[id]!![index])
                    }
                }
            }}
        }

        private suspend fun commandList(index: Int? = null) {
            val programs = YqlangStore.programs[id]
            fun retrieve(i: Int, full: Boolean): String {
                val additionalInfo = if(states[id]?.get(i)?.interpreter == null) "??????????????????" else ""
                val originalSource = programs!![i].source.trim()
                val source = if(full) originalSource else {
                    val lines = originalSource.split("\n")
                    val limit = 5
                    val croppedLines = lines.size - limit
                    lines.take(limit).joinToString("\n") + if(croppedLines > 0) "\n...?????????${croppedLines}??????" else ""
                }
                val originalVariables = states[id]!![i].interpreter?.memory?.memoryDump() ?: "null" // TODO: display symbols
                val variables = if (full) originalVariables else {
                    val limit = 50
                    val croppedVariables = originalVariables.length - limit
                    originalVariables.take(limit) + if(croppedVariables > 0) " ...?????????${croppedVariables}????????????" else ""
                }
                return "???${i + 1}????????????$additionalInfo\n????????????$source\n?????????$variables\n\n"
            }
            if(programs == null) {
                sendMsg("??????????????????".toMsg())
            } else {
                if (index == null) {
                    var info = "?????????????????????\n\n"
                    for (i in programs.indices) {
                        info += retrieve(i, false)
                    }
                    info += "???????????????${programs.size}????????? /yqlang list <????????????> ?????????????????????????????????????????????"
                    sendMsg(info.toMsg())
                } else {
                    if (index > programs.size || index <= 0) {
                        sendMsg("??????${index}????????????".toMsg())
                    } else {
                        sendMsg(retrieve(index - 1, true).trim().toMsg())
                    }
                }
            }
        }

        private suspend fun commandUpdate(index: Int, source: String) {
            val programs = YqlangStore.programs[id]
            if (programs?.indices?.contains(index) == true) {
                val interpreter = try {
                    RestrictedContainer(source, Memory.deserialize(programs[index].memory))
                } catch (e: Exception) {
                    sendMsg("??????????????????${e.javaClass}??????????????????${e.message}???".toMsg())
                    return
                }
                programs[index].memory = interpreter.memory.serialize()
                programs[index].source = source
                states[id]!![index].interpreter?.cancelAllRunningTasks()
                states[id]!![index] = Process(interpreter)
                sendMsg("??????${index + 1}???????????????".toMsg())
            } else {
                sendMsg("???????????????????????????".toMsg())
            }
        }

        private suspend fun commandRemove(index: Int) {
            if(YqlangStore.programs[id]?.indices?.contains(index) == true) {
                YqlangStore.programs[id]!!.removeAt(index)
                states[id]!!.removeAt(index).interpreter?.cancelAllRunningTasks()
                sendMsg("??????????????????????????????".toMsg())
            } else {
                sendMsg("???????????????????????????".toMsg())
            }
        }

        suspend fun respondToMessageEvent(rawText: String, sender: Long, images: List<Pair<String, BufferedImage>>) {
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
                                "add" -> commandRun(source, images, run = true, save = true, firstRun = true)
                                "run" -> commandRun(source, images, run = true, save = false, firstRun = true)
                            }
                        } else {
                            sendMsg("???????????????/yqlang add <?????????> ??? /yqlang run <?????????>???".toMsg())
                        }
                    }
                    "list" -> {
                        if (segments.size == 2) {
                            commandList()
                        } else if (segments.size == 3) {
                            segments[2].toIntOrNull()?.let { commandList(it) } ?:
                                sendMsg("???????????????/yqlang list <????????????>???".toMsg())
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
                                    commandUpdate(index, source)
                                    ok = true
                                }
                            }
                        }
                        if (!ok) {
                            sendMsg("???????????????/yqlang update <????????????> <?????????>???".toMsg())
                        }
                    }
                    "remove" -> {
                        var ok = false
                        if (segments.size == 3) {
                            val index = segments[2].toIntOrNull()?.minus(1)
                            if (index != null) {
                                commandRemove(index)
                                ok = true
                            }
                        }
                        if (!ok) {
                            sendMsg("???????????????/yqlang remove <????????????>???".toMsg())
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
                                sendMsg("?????????????????????????????????".toMsg())
                            }
                        }
                    }
                    else -> {
                        sendMsg("???????????????????????????$predicate??????????????? /yqlang help ?????????????????????".toMsg())
                    }
                }
            } else if (segments.size == 1 &&
                segments[0] == "/yqlang" ) {
                sendMsg(("""
                    yqlang???yqbot???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                    ??????yqlang?????????GitHub??????????????????https://github.com/Yongqi-Zhuo/yqlang???
                    ??????????????????????????????/yqlang run??????????????????????????????????????????????????????/yqlang add??????
                    ????????????????????????
                """.trimIndent() + "\n" + helpMessage).toMsg())
            } else {
                // a message
                eventRun(listOf(
                    TextEvent(rawText),
                    SenderEvent(sender),
                    ImagesEvent(images.map { it.first })
                ), images)
            }
        }

        companion object {
            val helpMessage = """
                /yqlang add <??????> - ???????????????????????????????????????????????????????????????????????????
                /yqlang run <??????> - ?????????????????????
                /yqlang list - ???????????????????????????????????????
                /yqlang list <????????????> - ???????????????????????????????????????
                /yqlang update <??????> <??????> - ???????????????????????????????????????????????????????????????
                /yqlang remove <??????> - ?????????????????????
                /yqlang help - ?????????????????????
                /yqlang help <????????????> - ????????????????????????????????????
                """.trimIndent()
            fun loadPrograms() {
                states.putAll(runBlocking {
                    YqlangStore.programs.map { (gid, gstorage) ->
                        async {
                            val gprocesses = gstorage.mapTo(mutableListOf()) { storage ->
                                async {
                                    val interpreter = try {
                                        RestrictedContainer(storage.source, Memory.deserialize(storage.memory))
                                    } catch (e: Exception) {
                                        null
                                    }
                                    if (interpreter != null) {
                                        storage.memory = interpreter.memory.serialize()
                                    }
                                    Process(interpreter)
                                }
                            }.awaitAll().toMutableList()
                            Pair(gid, gprocesses)
                        }
                    }.awaitAll()
                })
            }
        }
    }

    fun load() {
        YqlangStore.reload()
        PerGroupExecutionManager.loadPrograms()
        Yqbot.registerImageLoadedMessageListener { images ->
            PerGroupExecutionManager(subject).respondToMessageEvent(message.contentToString(), sender.id, images)
        }
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<NudgeEvent> {
            if (target is Bot) {
                PerGroupExecutionManager(subject).eventRun(listOf(NudgedEvent(from.id)), null)
            }
        }

        clockTask = object : TimerTask() {
            override fun run() {
                val now = System.currentTimeMillis()
                Bot.instances.map { bot ->
                    Yqbot.launch {
                        states.keys.map { gid ->
                            val subject: Contact? = bot.getGroup(gid) ?: bot.getFriend(gid)
                            async {
                                subject?.let { PerGroupExecutionManager(it) }?.eventRun(
                                    listOf(ClockEvent(now)), null
                                )
                            }
                        }.awaitAll()
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