@file:OptIn(ExperimentalSerializationApi::class)

package protobuf.rkey

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class RkeyResp(
    @ProtoNumber(4) val body: RkeyBody? = null
)

@Serializable
data class RkeyBody(
    @ProtoNumber(4) val list: RkeyList? = null
)

@Serializable
data class RkeyList(
    @ProtoNumber(1) val entries: List<RkeyEntry> = emptyList()
)

@Serializable
data class RkeyEntry(
    @ProtoNumber(1) val rkey: String = "",
    @ProtoNumber(2) val ttl: ULong = 0uL,
    @ProtoNumber(3) val storeId: UInt = 0u,
    @ProtoNumber(4) val createTime: UInt = 0u,
    @ProtoNumber(5) val type: UInt = 0u
)
