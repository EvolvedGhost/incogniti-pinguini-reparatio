package moe.fuqiuluo.shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.fuqiuluo.shamrock.remote.action.ActionSession
import moe.fuqiuluo.shamrock.remote.action.IActionHandler
import moe.fuqiuluo.shamrock.remote.service.data.SimpleTroopMemberInfo
import moe.fuqiuluo.shamrock.remote.service.data.push.MemberRole
import moe.fuqiuluo.shamrock.tools.EmptyJsonString
import moe.fuqiuluo.shamrock.tools.ifNullOrEmpty
import moe.fuqiuluo.shamrock.xposed.helper.GroupMemberCache
import moe.fuqiuluo.symbols.OneBotHandler

/**
 * QQ 9.x（NT 架构）下 GroupSvc.getGroupMemberList 会永久挂起
 * （IBizTroopMemberInfoService 那套已废弃）。
 * 改走 NT 内核 IKernelGroupService.getTransferableMemberInfo 获取成员，
 * 结果缓存在 GroupMemberCache，请求侧最多等 6 秒，为客户端默认超时留出余量。
 */
@OneBotHandler("get_group_member_list")
internal object GetTroopMemberList : IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val groupId = session.getString("group_id")
        val refresh = session.getBooleanOrDefault("refresh", session.getBooleanOrDefault("no_cache", false))
        return invoke(groupId, refresh, session.echo)
    }

    suspend operator fun invoke(
        groupId: String,
        refresh: Boolean,
        echo: JsonElement = EmptyJsonString
    ): String {
        val groupCode = groupId.toLongOrNull()
            ?: return error("group_id 非法: $groupId", echo, arrayResult = true)

        // 请求路径只读缓存：命中就返回，没有就返回空，绝不在这里触发拉取。
        // 单人成员信息由 get_group_member_info 负责，那里有 5 分钟冷却和 CardSvc 兜底。
        val members = runCatching {
            GroupMemberCache.cached(groupCode) ?: emptyList()
        }.getOrElse { emptyList() }

        runCatching {
            moe.fuqiuluo.shamrock.helper.LogCenter.log(
                "[get_group_member_list] group=$groupCode 成员数=${members.size}"
            )
        }

        return ok(members.mapNotNull { info ->
            val uin = info.uin
            if (uin <= 0L) return@mapNotNull null
            val cardName = info.cardName?.takeIf { it.isNotBlank() }
            val nick = (info.nick ?: "").ifNullOrEmpty(info.remark) ?: ""
            SimpleTroopMemberInfo(
                uin = uin,
                groupId = groupCode,
                name = nick,
                sex = "unknown",
                age = 0,
                title = "",
                titleExpireTime = 0,
                nick = nick,
                showName = cardName,
                cardName = cardName,
                distance = 0,
                honor = emptyList(),
                joinTime = 0L,
                lastActiveTime = 0L,
                lastSentTime = 0L,
                uniqueName = null,
                area = "",
                level = 0,
                role = MemberRole.Member,
                unfriendly = false,
                cardChangeable = false,
                shutUpTimestamp = info.shutUpTime.toLong()
            )
        }, echo)
    }

    override val requiredParams: Array<String> = arrayOf("group_id")
}
