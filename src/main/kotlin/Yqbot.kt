package top.saucecode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.unregister
import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.console.permission.AbstractPermitteeId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.permission.PermissionService.Companion.permit
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.message.data.ForwardMessage
import net.mamoe.mirai.message.data.Image
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.utils.info
import java.awt.image.BufferedImage
import java.net.URL
import javax.imageio.ImageIO

object Yqbot : KotlinPlugin(
    JvmPluginDescription(
        id = "top.saucecode.yqbot",
        name = "yqbot",
        version = "1.0",
    ) {
        author("SauceCode")
    }
) {

    val adminPermission by lazy {
        PermissionService.INSTANCE.register(permissionId("admin"), "管理yqbot的权限")
    }

    val messageListeners: MutableList<suspend MessageEvent.(Map<String, BufferedImage>) -> Unit> = mutableListOf()

    override fun onEnable() {
        adminPermission
        YqConfig.reload()
        if(YqConfig.goshujin) {
            Goshujin.register()
            AbstractPermitteeId.AnyUser.permit(Goshujin.permission)
        }
        if(YqConfig.sgl) {
            SglManager.load()
        }
        if(YqConfig.repeater) {
            Repeater.load()
        }
        if(YqConfig.chatbot) {
            Chatbot.load()
        }
        if(YqConfig.yqlang) {
            YqLang.load()
        }
        if(YqConfig.wolfram) {
            Wolfram.load()
        }
        if (YqConfig.wordguess) {
            WordGuessManager.load()
        }
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<MessageEvent> { messageEvent ->
            // use coroutine to download images concurrently
            val images = coroutineScope {
                val imgs = mutableMapOf<String, BufferedImage>()
                val imageIds = mutableSetOf<String>()
                val directImages = message.filterIsInstance<Image>()
                val indirectImages = message[ForwardMessage]?.nodeList?.flatMap {
                    it.messageChain.filterIsInstance<Image>()
                } ?: emptyList()
                (directImages + indirectImages).forEach {
                    if (!imageIds.contains(it.imageId)) {
                        imageIds.add(it.imageId)
                        launch {
                            imgs[it.imageId] = withContext(Dispatchers.IO) {
                                ImageIO.read(URL(it.queryUrl()))
                            }
                        }
                    }
                }
                imgs
            }
            coroutineScope {
                messageListeners.forEach { listener ->
                    launch {
                        listener(messageEvent, images)
                    }
                }
            }
        }
        logger.info { "Loaded yqbot." }
    }

    fun registerImageLoadedMessageListener(listener: suspend MessageEvent.(Map<String, BufferedImage>) -> Unit) {
        messageListeners.add(listener)
    }

    override fun onDisable() {
        if(YqConfig.goshujin) {
            Goshujin.unregister()
        }
        if(YqConfig.sgl) {
            SglManager.unload()
        }
        if(YqConfig.repeater) {
            Repeater.unload()
        }
        if(YqConfig.chatbot) {
            Chatbot.unload()
        }
        if(YqConfig.yqlang) {
            YqLang.unload()
        }
        if(YqConfig.wolfram) {
            Wolfram.unload()
        }
        if(YqConfig.wordguess) {
            WordGuessManager.unload()
        }
    }
}

object YqConfig: AutoSavePluginConfig("yqconfig") {
    val sgl: Boolean by value(true)
    val goshujin: Boolean by value(true)
    val repeater: Boolean by value(true)
    val chatbot: Boolean by value(true)
    val yqlang: Boolean by value(true)
    val wolfram: Boolean by value(true)
    val wordguess: Boolean by value(true)
}
