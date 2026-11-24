package moe.fuqiuluo.shamrock.remote.action.handlers

import com.tencent.mobileqq.data.Card
import kotlinx.serialization.json.JsonElement
import moe.fuqiuluo.qqinterface.servlet.CardSvc
import moe.fuqiuluo.shamrock.remote.action.ActionSession
import moe.fuqiuluo.shamrock.remote.action.IActionHandler
import moe.fuqiuluo.shamrock.remote.service.data.SimpleTroopMemberInfo
import moe.fuqiuluo.shamrock.remote.service.data.push.MemberRole
import moe.fuqiuluo.shamrock.tools.EmptyJsonString
import moe.fuqiuluo.shamrock.xposed.helper.GroupMemberCache
import moe.fuqiuluo.symbols.OneBotHandler

/**
 * QQ 9.x（NT 架构）下 GroupSvc.getTroopMemberInfoByUin 走的是
 * ITroopMemberInfoService，该服务不会初始化，调用将永久挂起。
 *
 * 改为：先查内核成员缓存（getTransferableMemberInfo 拉来的），
 * 查不到再用 CardSvc 补陌生人资料，全过程有上限，绝不超过客户端超时。
 */
@OneBotHandler("get_group_member_info")
internal object GetTroopMemberInfo : IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val uin = session.getString("user_id")
        val groupId = session.getString("group_id")
        val refresh = session.getBooleanOrDefault("refresh", session.getBooleanOrDefault("no_cache", false))
        return invoke(groupId, uin, refresh, session.echo)
    }

    suspend operator fun invoke(
        groupId: String,
        uin: String,
        refresh: Boolean,
        echo: JsonElement = EmptyJsonString
    ): String {
        val groupCode = groupId.toLongOrNull()
            ?: return error("group_id 非法: $groupId", echo)
        val target = uin.toLongOrNull()
            ?: return error("user_id 非法: $uin", echo)

        // 1. 缓存命中直接返回，避免任何等待
        var member = GroupMemberCache.cached(groupCode)?.firstOrNull { it.uin == target }
        // 2. 未命中则短暂等待一次拉取
        if (member == null) {
            val list = if (refresh) GroupMemberCache.refresh(groupCode, 3000L)
            else GroupMemberCache.obtain(groupCode, 3000L)
            member = list.firstOrNull { it.uin == target }
        }

        // 3. 仍没有就用陌生人资料兜底（CardSvc 在 NT 下可用，~0.6s）
        val card = if (member == null) {
            runCatching { CardSvc.refreshAndGetProfileCard(uin).getOrNull() }.getOrNull()
        } else null

        if (member == null && card == null) {
            runCatching {
                moe.fuqiuluo.shamrock.helper.LogCenter.log(
                    "[get_group_member_info] 未找到 group=$groupCode uin=$target"
                )
            }
            return error("获取群成员信息失败", echo)
        }

        val cardName = member?.cardName?.takeIf { it.isNotBlank() }
        val nick = (member?.nick ?: card?.strNick ?: "").takeIf { it.isNotBlank() } ?: target.toString()
        val sex = when {
            member != null -> "unknown"
            card != null -> when (card.shGender) {
                Card.FEMALE -> "female"
                Card.MALE -> "male"
                else -> "unknown"
            }
            else -> "unknown"
        }

        return ok(
            SimpleTroopMemberInfo(
                uin = target,
                groupId = groupCode,
                name = nick,
                sex = sex,
                age = card?.age?.toInt() ?: 0,
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
                shutUpTimestamp = member?.shutUpTime?.toLong() ?: 0L
            ), echo
        )
    }

    override val requiredParams: Array<String> = arrayOf("user_id", "group_id")
}
