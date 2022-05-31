package top.saucecode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.Message
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.message.data.toMessageChain
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import top.saucecode.Yqbot.reload

object Wolfram {

    private object WolframStore: AutoSavePluginConfig("WolframConfig") {
        val appid: String by value("XXXXXX-XXXXXXXXXX")
    }

    fun load() {
        WolframStore.reload()
        GlobalEventChannel.parentScope(Yqbot).subscribeAlways<GroupMessageEvent> {
            val rawText = message.contentToString()
            if (rawText.startsWith("/wolfram")) {
                val stripped = rawText.substring("/wolfram".length).trim()
                withContext(Dispatchers.IO) {
                    val what = URLEncoder.encode(stripped, StandardCharsets.UTF_8.toString())
                    val query =
                        URL("http://api.wolframalpha.com/v2/query?input=$what&appid=${WolframStore.appid}&format=plaintext,image")
                    // download xml file from query url
                    val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    val doc = docBuilder.parse(query.openStream())
                    val res = doc.getElementsByTagName("pod")
                    if (res.length > 0) {
                        val results = mutableListOf<Message>()
                        results.add(PlainText("以下是由Wolfram Alpha返回的结果："))
                        for (i in 0 until res.length) {
                            val pod = res.item(i) as Element
                            val title = pod.getAttribute("title")
                            results.add(PlainText("\n----------------\n-- $title --"))
                            val subpods = pod.getElementsByTagName("subpod")
                            for (j in 0 until subpods.length) {
                                val subpod = subpods.item(j) as Element
                                val plaintext = subpod.getElementsByTagName("plaintext").item(0).textContent
                                results.add(PlainText("\n" + plaintext))
                                val imgUrl = URL((subpod.getElementsByTagName("img").item(0) as Element).getAttribute("src"))
                                val imgResource = imgUrl.openStream().toExternalResource()
                                val img = group.uploadImage(imgResource)
                                imgResource.close()
                                results.add(img)
                            }
                        }
                        val timing = (doc.getElementsByTagName("queryresult").item(0) as Element).getAttribute("timing")
                        results.add(PlainText("\n----------------\n计算用时：${timing}秒"))
                        group.sendMessage(results.toMessageChain())
                    } else {
                        group.sendMessage("请求Wolfram Alpha服务器得到的响应为空。")
                    }
                }
            }
        }
    }
    fun unload() {

    }
}