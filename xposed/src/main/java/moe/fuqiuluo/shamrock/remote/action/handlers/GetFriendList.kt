package moe.fuqiuluo.shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.fuqiuluo.shamrock.remote.action.ActionSession
import moe.fuqiuluo.shamrock.remote.action.IActionHandler
import moe.fuqiuluo.shamrock.remote.service.data.FriendEntry
import moe.fuqiuluo.shamrock.remote.service.data.PlatformType
import moe.fuqiuluo.shamrock.tools.EmptyJsonString
import moe.fuqiuluo.shamrock.xposed.helper.AppRuntimeFetcher
import moe.fuqiuluo.symbols.OneBotHandler

/**
 * QQ 9.2.95 是 NT 架构：旧的 IFriendDataService（isInitFinished / allFriends）
 * 和 IFriendHandlerService.requestFriendList 都已不存在，原实现会永久挂起。
 * 这里改走 NT 关系层：
 *   com.tencent.qqnt.ntrelation.friendsinfo.api.IFriendsInfoService#getAllFriend(String)
 * 返回的 bean 字段为 uin / nick / remark / age / sex / categoryId。
 * 用反射调用，避免依赖 QQ stub jar 的编译期符号。
 */
@OneBotHandler("get_friend_list")
internal object GetFriendList : IActionHandler() {
    private const val NT_FRIEND_SERVICE = "com.tencent.qqnt.ntrelation.friendsinfo.api.IFriendsInfoService"

    override suspend fun internalHandle(session: ActionSession): String {
        val refresh = session.getBooleanOrDefault("refresh", session.getBooleanOrDefault("no_cache", false))
        return invoke(refresh, session.echo)
    }

    suspend operator fun invoke(refresh: Boolean, echo: JsonElement = EmptyJsonString): String {
        val entries = runCatching { readFriends() }.getOrElse { emptyList() }
        return ok(entries, echo)
    }

    private fun readFriends(): List<FriendEntry> {
        val app = AppRuntimeFetcher.appRuntime
        val log = { msg: String -> runCatching { moe.fuqiuluo.shamrock.helper.LogCenter.log("[get_friend_list] $msg") } }
        // 关键：必须用 QQ 的 classloader，模块自己的 loader 看不到 QQ 的类
        val cls = runCatching {
            Class.forName(NT_FRIEND_SERVICE, false, app.javaClass.classLoader)
        }.getOrElse { log("ClassNotFound: ${it.message}"); return emptyList() }
        val getService = app.javaClass.methods.firstOrNull {
            it.name == "getRuntimeService" && it.parameterCount == 2 &&
                it.parameterTypes[0] == Class::class.java && it.parameterTypes[1] == String::class.java
        }
        if (getService == null) { log("getRuntimeService 方法未找到"); return emptyList() }
        // 依次尝试：getRuntimeService(cls,"all") → getRuntimeService(cls,"") → QRoute.api(cls)
        var service: Any? = null
        var via = ""
        for (flag in listOf("all", "")) {
            service = runCatching { getService.invoke(app, cls, flag) }.getOrNull()
            if (service != null) { via = "getRuntimeService(\"$flag\")"; break }
        }
        if (service == null) {
            service = runCatching {
                val qroute = Class.forName("com.tencent.mobileqq.qroute.QRoute", false, app.javaClass.classLoader)
                val api = qroute.getMethod("api", Class::class.java)
                api.invoke(null, cls)
            }.getOrNull()
            if (service != null) via = "QRoute.api"
        }
        if (service == null) { log("服务实例为 null（三条路径均失败）"); return emptyList() }
        log("服务实例: ${service.javaClass.name} via $via")

        val allFriend = runCatching { cls.getMethod("getAllFriend", String::class.java) }
            .getOrElse { log("getAllFriend 方法缺失: ${it.message}"); return emptyList() }
        val friendCount = runCatching { cls.getMethod("getFriendCount", String::class.java) }.getOrNull()

        // QQNT 的 getAllFriend 需要一个场景参数，不同版本取值不同。
        // 逐个试，并把计数打到日志里，便于定位真正生效的那个。
        for (arg in listOf("all", "", "0", "1", "2", "3")) {
            val count = runCatching { friendCount?.invoke(service, arg) as? Int }.getOrElse { null }
            val list = runCatching { allFriend.invoke(service, arg) as? List<*> }.getOrElse { null }
            log("arg='$arg' count=$count listSize=${list?.size}")
            if (!list.isNullOrEmpty()) return list.mapNotNull { mapFriend(it) }
        }
        return emptyList()
    }

    private fun mapFriend(item: Any?): FriendEntry? {
        if (item == null) return null
        // Kotlin data class 的字段通常是 private + getter，所以字段和 getter 都试
        fun read(name: String): Any? {
            val cap = name.replaceFirstChar { it.uppercaseChar() }
            for (getter in listOf("get$cap", "is$cap")) {
                val m = runCatching { item.javaClass.getMethod(getter) }.getOrNull()
                if (m != null) return runCatching { m.invoke(item) }.getOrNull()
            }
            val f = runCatching { item.javaClass.getField(name) }.getOrNull()
            return runCatching { f?.also { it.isAccessible = true }?.get(item) }.getOrNull()
        }

        // 一次性把 bean 的可用成员打出来，方便定位字段名
        if (!beanLogged) {
            beanLogged = true
            val names = (item.javaClass.declaredFields.map { it.name } +
                item.javaClass.methods.map { it.name })
                .filter { it.length in 3..20 }
                .distinct().take(40)
            runCatching {
                moe.fuqiuluo.shamrock.helper.LogCenter.log(
                    "[get_friend_list] bean=${item.javaClass.name} members=$names"
                )
            }
        }

        val uin = when (val v = read("uin")) {
            is String -> v.toLongOrNull()
            is Number -> v.toLong()
            else -> null
        } ?: return null

        val nick = read("nick") as? String
        val remark = read("remark") as? String
        return FriendEntry(
            id = uin,
            name = nick,
            displayName = remark ?: nick,
            remark = remark,
            age = (read("age") as? Number)?.toInt() ?: 0,
            gender = ((read("sex") as? Number)?.toInt() ?: 0).toByte(),
            groupId = (read("categoryId") as? Number)?.toInt() ?: 0,
            platformType = PlatformType.MOBILE_ANDROID,
            termType = 0
        )
    }

    @Volatile
    private var beanLogged = false
}
