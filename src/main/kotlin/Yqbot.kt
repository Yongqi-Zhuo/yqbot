package top.saucecode

import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.unregister
import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.console.permission.AbstractPermitteeId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.permission.PermissionService.Companion.permit
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.utils.info

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

    override fun onEnable() {
        adminPermission
        YqConfig.reload()
        if(YqConfig.goshujin) {
            Goshujin.register()
            AbstractPermitteeId.AnyUser.permit(Goshujin.permission)
        }
        if(YqConfig.sgl) {
            SglManager.load()
            AbstractPermitteeId.AnyUser.permit(SglCommand.permission)
        }
        if(YqConfig.repeater) {
            Repeater.load()
            AbstractPermitteeId.AnyUser.permit(RepeaterCommand.permission)
        }
        logger.info { "Loaded yqbot." }
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
    }
}

object YqConfig: AutoSavePluginConfig("yqconfig") {
    val sgl: Boolean by value(true)
    val goshujin: Boolean by value(true)
    val repeater: Boolean by value(true)
}