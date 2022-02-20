package top.saucecode

import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.SimpleCommand
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission

object Goshujin : SimpleCommand(
    Yqbot, "whoami", "我是谁",
    description = "到底谁才是botのご主人様呢？"
) {
    @Handler
    suspend fun CommandSender.whoami() {
        if(hasPermission(Yqbot.adminPermission)) {
            sendMessage("主人的命令罢了。")
        } else {
            sendMessage("你不是我的主人捏~")
        }
    }
}