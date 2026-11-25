package moe.fuqiuluo.shamrock.xposed.helper

import com.tencent.qqnt.kernel.nativeinterface.GroupSimpleInfo
import com.tencent.qqnt.kernel.nativeinterface.IKernelGroupListener
import com.tencent.qqnt.kernel.nativeinterface.IKernelGroupService
import moe.fuqiuluo.shamrock.helper.LogCenter
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

private val logExec = Executors.newSingleThreadExecutor { r ->
    Thread(r, "shamrock-grouplist-log").apply { isDaemon = true }
}

/** 内核回调线程上不允许同步写日志文件（LogCenter 是同步落盘的），统一转后台 */
private fun logAsync(msg: String) {
    runCatching { logExec.execute { LogCenter.log(msg) } }
}


/**
 * QQ 9.x（NT 架构）下群列表的唯一来源。
 *
 * ITroopInfoService.allTroopList 已不存在，本地库被 SQLCipher 加密，
 * EntityManager 也拿不到 Cursor。只能注册 IKernelGroupListener 让 QQ 推。
 *
 * 关键点：onGroupListUpdate 的 ArrayList 是**增量**，不是全量快照。
 * GroupListUpdateType 取 REFRESHALL / GETALL 时才是全量替换，
 * MODIFIED 是 upsert，REMOVE 是删除。若一律当全量覆盖，
 * 单条的增量更新会把整个列表冲掉，因此这里按更新类型合并。
 *
 * 用动态 Proxy 实现监听器（接口 30+ 回调，手写太脆），只处理列表相关回调。
 * 请求侧只读缓存，零阻塞。
 */
object GroupListCache {
    private val map = ConcurrentHashMap<Long, GroupSimpleInfo>()

    @Volatile
    private var inited = false

    @Volatile
    private var attached = false

    fun snapshot(): List<GroupSimpleInfo> = map.values.toList()

    val isReady: Boolean get() = inited || map.isNotEmpty()

    fun attach(service: IKernelGroupService?) {
        if (service == null) {
            LogCenter.log("[GroupListCache] groupService 为 null，跳过注册")
            return
        }
        if (attached) return
        attached = true
        runCatching {
            val listener = Proxy.newProxyInstance(
                IKernelGroupListener::class.java.classLoader,
                arrayOf(IKernelGroupListener::class.java)
            ) { _, method, args ->
                runCatching { onCallback(method.name, args) }
                null
            } as IKernelGroupListener

            val handle = service.addKernelGroupListener(listener)
            LogCenter.log("[GroupListCache] 已注册群列表监听, handle=$handle")
        }.onFailure {
            LogCenter.log("[GroupListCache] 注册失败: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun onCallback(name: String, args: Array<out Any?>?) {
        when (name) {
            "onGroupListUpdate" -> handleUpdate(args)
            "onGroupListInited" -> {
                inited = true
                logAsync("[GroupListCache] 群列表就绪标记, 当前缓存 ${map.size} 个")
            }
            else -> Unit
        }
    }

    private fun handleUpdate(args: Array<out Any?>?) {
        val type = args?.getOrNull(0)?.toString() ?: ""
        val list = args?.firstOrNull { it is List<*> } as? List<*> ?: return
        val infos = list.filterIsInstance<GroupSimpleInfo>()
        if (infos.isEmpty()) return

        when {
            type.contains("REFRESHALL") || type.contains("GETALL") -> {
                map.clear()
                infos.forEach { map[it.groupCode] = it }
            }
            type.contains("REMOVE") -> {
                infos.forEach { map.remove(it.groupCode) }
            }
            else -> {
                // MODIFIED 及未知类型一律按增量合并
                infos.forEach { map[it.groupCode] = it }
            }
        }
        logAsync("[GroupListCache] $type 推来 ${infos.size} 个, 合计 ${map.size} 个")
    }
}
