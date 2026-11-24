package moe.fuqiuluo.shamrock.xposed.helper

import com.tencent.qqnt.kernel.nativeinterface.MemberInfo
import moe.fuqiuluo.shamrock.helper.LogCenter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * QQ 9.x（NT 架构）下的群成员列表缓存。
 *
 * 旧的 ITroopMemberInfoService 路径会永久挂起。
 * 内核群服务上真正的全量接口是（stub 里没声明，靠反射调用）：
 *   getAllMemberListV2(long groupCode, boolean, IGroupMemberListCallback)
 *   getAllMemberList  (long groupCode, boolean, IGroupMemberListCallback)
 * 回退方案是 Shamrock 原本给单成员查询用的 getTransferableMemberInfo（只含部分成员）。
 *
 * 所有拉取都在后台线程做，请求侧只读缓存。
 */
object GroupMemberCache {
    private val cache = ConcurrentHashMap<Long, List<MemberInfo>>()
    private val inflight = ConcurrentHashMap<Long, Boolean>()
    private val lastAttempt = ConcurrentHashMap<Long, Long>()
    // 限制并发拉取，避免大量线程 + 内核调用把 QQ 拖到 ANR
    private val semaphore = java.util.concurrent.Semaphore(2)

    fun cached(groupCode: Long): List<MemberInfo>? = cache[groupCode]

    /** 供探针等外部来源写入 */
    fun put(groupCode: Long, members: List<MemberInfo>) {
        if (members.isNotEmpty()) cache[groupCode] = members
    }

    /** 返回缓存；没有就触发后台拉取，最多等 waitMs 毫秒（绝不在请求线程里做拉取） */
    fun obtain(groupCode: Long, waitMs: Long = 1500L): List<MemberInfo> {
        cache[groupCode]?.let { return it }
        preload(listOf(groupCode))
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            cache[groupCode]?.let { return it }
            Thread.sleep(50)
        }
        return cache[groupCode] ?: emptyList()
    }

    /** 丢掉缓存重新拉一次 */
    fun refresh(groupCode: Long, waitMs: Long = 5000L): List<MemberInfo> {
        cache.remove(groupCode)
        fetch(groupCode, waitMs)
        return cache[groupCode] ?: emptyList()
    }

    /** 预热：并发拉取，不阻塞调用方。最多 2 个并发，且同一群 5 分钟内不重复拉，避免拖垮内核 */
    fun preload(groupCodes: List<Long>) {
        groupCodes.forEach { code ->
            if (cache.containsKey(code) || inflight.containsKey(code)) return@forEach
            val last = lastAttempt[code] ?: 0L
            if (System.currentTimeMillis() - last < 5 * 60 * 1000L) return@forEach
            lastAttempt[code] = System.currentTimeMillis()
            Thread {
                runCatching {
                    semaphore.acquire()
                    try {
                        fetch(code, 8000L)
                    } finally {
                        semaphore.release()
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun fetch(groupCode: Long, waitMs: Long) {
        if (inflight.putIfAbsent(groupCode, true) != null) {
            val deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline && cache[groupCode] == null) {
                Thread.sleep(100)
            }
            return
        }
        try {
            val kernel = runCatching { NTServiceFetcher.kernelService }.getOrNull() ?: return
            val groupService = runCatching { kernel.wrapperSession.groupService }.getOrNull() ?: return

            // 只用已验证可用的 getTransferableMemberInfo。
            // 说明：getAllMemberList/V2 与 createMemberListScene+getNextMemberList 这条路
            // 在 QQ 9.2.95 上要么回空、要么需要构造真机的 GroupMemberInfoListId 游标
            // （stub 构造器与真机不一致，newInstance 抛 NoSuchMethodError），
            // 强行调用还会拖慢内核导致正常请求超时，所以不再尝试。
            val members = fetchTransferable(groupService, groupCode, waitMs)
            if (members.isNotEmpty()) {
                cache[groupCode] = members
                LogCenter.log("[GroupMemberCache] $groupCode 成员 ${members.size}")
            } else {
                LogCenter.log("[GroupMemberCache] $groupCode 没拿到成员")
            }
        } catch (e: Throwable) {
            LogCenter.log("[GroupMemberCache] $groupCode 异常: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            inflight.remove(groupCode)
        }
    }

    /** 反射调用 getAllMemberListV2 / getAllMemberList，累积所有回调批次 */
    private fun fetchByReflect(groupService: Any, groupCode: Long, waitMs: Long, force: Boolean): List<MemberInfo> {
        for (name in listOf("getAllMemberListV2", "getAllMemberList")) {
            val m = groupService.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 3 && it.parameterTypes[0] == Long::class.javaPrimitiveType
            } ?: continue

            val collected = Collections.synchronizedList(arrayListOf<MemberInfo>())
            val firstCb = CountDownLatch(1)

            val handler = InvocationHandler { _, method, args ->
                runCatching {
                    val payload = args?.firstOrNull { it is List<*> || (it != null && it.javaClass.isArray) }
                    when (payload) {
                        is List<*> -> collected.addAll(payload.filterIsInstance<MemberInfo>())
                        is Array<*> -> collected.addAll(payload.filterIsInstance<MemberInfo>())
                        else -> {}
                    }
                }
                firstCb.countDown()
                null
            }

            val invoked = runCatching {
                val cbType = m.parameterTypes[2]
                val proxy = Proxy.newProxyInstance(cbType.classLoader, arrayOf(cbType), handler)
                m.isAccessible = true
                m.invoke(groupService, groupCode, force, proxy)
            }
            if (invoked.isFailure) {
                LogCenter.log("[GroupMemberCache] $name 调用失败: ${invoked.exceptionOrNull()?.javaClass?.simpleName}")
                continue
            }

            // 等首个回调，再多等 1.5 秒收后续分页批次
            if (!firstCb.await(waitMs, TimeUnit.MILLISECONDS)) {
                LogCenter.log("[GroupMemberCache] $name 无回调")
                continue
            }
            Thread.sleep(1500)
            val result = synchronized(collected) { collected.toList() }
            LogCenter.log("[GroupMemberCache] $name 返回 ${result.size} 人")
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    /**
     * QQ UI 用的分页成员列表流程：
     *   sceneId = createMemberListScene(groupCode, scene)
     *   getNextMemberList(sceneId, null, 500, cb)  ← 反复调用直到拿够
     *   destroyMemberListScene(sceneId)
     */
    private fun fetchByScene(groupService: Any, groupCode: Long, waitMs: Long, overallDeadline: Long): List<MemberInfo> {
        val create = groupService.javaClass.methods.firstOrNull {
            it.name == "createMemberListScene" && it.parameterCount == 2
        } ?: run { LogCenter.log("[GroupMemberCache] 无 createMemberListScene"); return emptyList() }
        val next = groupService.javaClass.methods.firstOrNull {
            it.name == "getNextMemberList" && it.parameterCount == 4
        } ?: run { LogCenter.log("[GroupMemberCache] 无 getNextMemberList"); return emptyList() }
        val destroy = groupService.javaClass.methods.firstOrNull {
            it.name == "destroyMemberListScene" && it.parameterCount == 1
        }

        val sceneId = runCatching {
            create.isAccessible = true
            create.invoke(groupService, groupCode, "shamrock") as? String
        }.getOrNull() ?: run { LogCenter.log("[GroupMemberCache] createMemberListScene 返回 null"); return emptyList() }
        LogCenter.log("[GroupMemberCache] scene=$sceneId group=$groupCode")

        val expected = GroupListCache.snapshot().firstOrNull { it.groupCode == groupCode }?.memberCount ?: 0
        val collected = Collections.synchronizedList(arrayListOf<MemberInfo>())

        try {
            var round = 0
            var cursor: Any? = newCursor(sceneId)
            while (round < 60) {
                round++
                if (System.currentTimeMillis() > overallDeadline) {
                    LogCenter.log("[GroupMemberCache] 场景分页超预算，止于 $round 轮")
                    break
                }
                val latch = CountDownLatch(1)
                var nextCursor: Any? = null
                val handler = InvocationHandler { _, _, args ->
                    runCatching {
                        val payload = args?.firstOrNull { it is List<*> || (it != null && it.javaClass.isArray) }
                        when (payload) {
                            is List<*> -> collected.addAll(payload.filterIsInstance<MemberInfo>())
                            is Array<*> -> collected.addAll(payload.filterIsInstance<MemberInfo>())
                            else -> {}
                        }
                        // 回调里可能带下一页游标（按类名判断，避免 stub 与真机类型不一致）
                        args?.forEach { a ->
                            if (a != null && a.javaClass.simpleName == "GroupMemberInfoListId") nextCursor = a
                        }
                    }
                    latch.countDown()
                    null
                }
                val cbType = next.parameterTypes[3]
                val proxy = Proxy.newProxyInstance(cbType.classLoader, arrayOf(cbType), handler)
                val invoked = runCatching {
                    next.isAccessible = true
                    next.invoke(groupService, sceneId, cursor, 500, proxy)
                }
                if (invoked.isFailure) {
                    LogCenter.log("[GroupMemberCache] getNextMemberList 失败: ${invoked.exceptionOrNull()?.javaClass?.simpleName}")
                    break
                }
                if (!latch.await(waitMs, TimeUnit.MILLISECONDS)) {
                    LogCenter.log("[GroupMemberCache] getNextMemberList 第 $round 轮无回调")
                    break
                }
                val size = collected.size
                if (size == 0) break
                if (expected > 0 && size >= expected) break
                nextCursor?.let { cursor = it as com.tencent.qqnt.kernel.nativeinterface.GroupMemberInfoListId }
                Thread.sleep(80)
            }
        } finally {
            destroy?.let { d ->
                runCatching { d.isAccessible = true; d.invoke(groupService, sceneId) }
            }
        }

        val result = synchronized(collected) { collected.toList() }
        LogCenter.log("[GroupMemberCache] 场景分页共 ${result.size} 人（预期 $expected）")
        return result
    }

    /** 反射构造分页游标：真机类的构造器与 stub 不一致，逐个尝试 */
    private fun newCursor(sceneId: String): Any? {
        return runCatching {
            val cls = Class.forName(
                "com.tencent.qqnt.kernel.nativeinterface.GroupMemberInfoListId",
                false,
                GroupMemberCache::class.java.classLoader
            )
            runCatching {
                cls.getConstructor(String::class.java, Int::class.javaPrimitiveType).newInstance("", 0)
            }.getOrElse {
                val o = cls.getConstructor().newInstance()
                runCatching { cls.getDeclaredField("uid").apply { isAccessible = true }.set(o, "") }
                runCatching { cls.getDeclaredField("index").apply { isAccessible = true }.set(o, 0) }
                o
            }
        }.getOrNull()
    }

    /** 回退：getTransferableMemberInfo（只返回部分成员） */
    private fun fetchTransferable(groupService: com.tencent.qqnt.kernel.nativeinterface.IKernelGroupService, groupCode: Long, waitMs: Long): List<MemberInfo> {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<List<MemberInfo>>(1)
        val ok = runCatching {
            groupService.getTransferableMemberInfo(groupCode) { errCode, _, data ->
                runCatching {
                    if (errCode == 0 && data != null) holder[0] = data.values.toList()
                }
                latch.countDown()
            }
        }.isSuccess
        if (!ok) return emptyList()
        latch.await(waitMs, TimeUnit.MILLISECONDS)
        return holder[0] ?: emptyList()
    }
}
