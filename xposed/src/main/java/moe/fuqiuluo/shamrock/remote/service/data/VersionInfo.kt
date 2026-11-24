package moe.fuqiuluo.shamrock.remote.service.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VersionInfo(
    @SerialName("app_full_name")
    val appFullName: String,
    @SerialName("app_name")
    val appName: String,
    @SerialName("app_version")
    val appVersion: String,
    val impl: String,
    val version: String,
    @SerialName("onebot_version")
    val onebotVersion: String,
    // OneBot 11 规范要求 get_version_info 返回 protocol_version: "v11"，
    // 按 OneBot 11 规范固定返回 "v11"，部分客户端会据此校验实现是否合规
    @SerialName("protocol_version")
    val protocolVersion: String
)
