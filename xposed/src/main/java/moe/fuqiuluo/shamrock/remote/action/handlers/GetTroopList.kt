package moe.fuqiuluo.shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.fuqiuluo.shamrock.remote.action.ActionSession
import moe.fuqiuluo.shamrock.remote.action.IActionHandler
import moe.fuqiuluo.shamrock.remote.service.data.SimpleTroopInfo
import moe.fuqiuluo.shamrock.tools.EmptyJsonString
import moe.fuqiuluo.shamrock.xposed.helper.GroupListCache
import moe.fuqiuluo.shamrock.xposed.helper.GroupMemberCache
import moe.fuqiuluo.shamrock.xposed.helper.NTServiceFetcher
import moe.fuqiuluo.symbols.OneBotHandler

/**
 * QQ 9.x（NT 架构）下 ITroopInfoService 已无 allTroopList / isTroopCacheInited，
 * 原实现会永久挂起。本地库被加密、EntityManager 也拿不到 Cursor。
 *
 * 群列表的正解是 NT 内核的 IKernelGroupListener：
 * 在 NTServiceFetcher 初始化时挂上监听，QQ 通过 onGroupListUpdate 推送 ArrayList<GroupSimpleInfo>，
 * 这里只读缓存，零阻塞、零反射。
 */
@OneBotHandler("get_group_list")
internal object GetTroopList : IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        // refresh / no_cache 在 NT 架构下没有可用的强制刷新入口，仅为参数兼容保留
        val refresh = session.getBooleanOrDefault("refresh", session.getBooleanOrDefault("no_cache", true))
        return invoke(refresh, session.echo)
    }

    suspend operator fun invoke(refresh: Boolean, echo: JsonElement = EmptyJsonString): String {
        val groups = runCatching { readFromCache() }.getOrElse { emptyList() }
        runCatching {
            moe.fuqiuluo.shamrock.helper.LogCenter.log(
                "[get_group_list] 缓存群数=${groups.size} ready=${GroupListCache.isReady}"
            )
        }
        return ok(groups, echo)
    }

    private fun readFromCache(): List<SimpleTroopInfo> {
        val infos = GroupListCache.snapshot()
        // 注意：不要在此批量预热成员列表。
        // 按群并发发起内核拉取会占满 QQ 的处理线程：HTTP 根路径仍返回 200，
        // 但 action 请求与消息回调会全部停摆。
        // 成员数据按需拉取即可（get_group_member_info 内部处理，带冷却）。
        return infos.map { info ->
            val code = info.groupCode
            SimpleTroopInfo(
                groupId = code,
                groupName = info.remarkName?.takeIf { it.isNotBlank() } ?: info.groupName,
                groupRemark = info.remarkName,
                groupUin = code,
                adminList = emptyList(),
                classText = null,
                isFrozen = false,
                maxMember = info.maxMember,
                memNum = info.memberCount,
                memCount = info.memberCount,
                maxNum = info.maxMember
            )
        }
    }
}
