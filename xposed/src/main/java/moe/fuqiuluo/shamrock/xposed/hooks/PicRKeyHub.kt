@file:OptIn(ExperimentalSerializationApi::class)

package moe.fuqiuluo.shamrock.xposed.hooks

import android.content.Context
import de.robv.android.xposed.XposedHelpers
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.protobuf.ProtoBuf
import moe.fuqiuluo.shamrock.helper.Level
import moe.fuqiuluo.shamrock.helper.LogCenter
import moe.fuqiuluo.shamrock.remote.service.config.ShamrockConfig
import moe.fuqiuluo.shamrock.tools.GlobalJson5
import moe.fuqiuluo.shamrock.tools.hookMethod
import moe.fuqiuluo.symbols.XposedHook
import protobuf.rkey.RkeyEntry
import protobuf.rkey.RkeyResp
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 从消息响应中提取图片下载用的 rkey 并常驻内存，同时落盘供重启后复用。
 *
 * 仅监听固定的 OIDB 响应命令，解析成功后整表替换；不修改任何响应数据。
 */
@XposedHook(priority = 2)
internal class PicRKeyHub : IAction {

    companion object {
        private const val TAG = "[PicRKeyHub]"
        private const val MSG_RESP_HANDLER = "mqq.app.msghandle.MsgRespHandler"
        private const val RKEY_CMD = "OidbSvcTrpcTcp.0x9067_202"
        private const val MULTIMEDIA_HOST = "https://multimedia.nt.qq.com.cn"
        private const val CACHE_FILE_NAME = "rkey.cache"
        private const val EXPIRE_MARGIN_SEC = 300L

        /** 最近一次解析到的 rkey 列表，整表替换保证读取方看到一致快照 */
        @Volatile
        var entries: List<RkeyEntry> = emptyList()
            private set

        /** 最近一次成功更新的时间戳（毫秒） */
        @Volatile
        var updatedAt: Long = 0L
            private set

        fun hasRkey(): Boolean = entries.isNotEmpty()

        /** 按类型优先级排序后的 rkey 字符串，已含 &rkey= 前缀 */
        fun candidates(): List<String> = candidatesWithType().map { it.first }

    /** 首选凭证：按候选排序取第一项，没有缓存时返回 null */
    fun preferredRkey(): String? = candidatesWithType().firstOrNull()?.first

        /** 与 [candidates] 同序，附带每条 rkey 的类型，供探测日志使用 */
        internal fun candidatesWithType(): List<Pair<String, UInt>> = entries
            .filter { it.rkey.isNotBlank() }
            .sortedBy { priorityOf(it.type) }
            .map { it.rkey to it.type }

        /** 拼接多媒体域名的原生直链，rkey 自带 &rkey= 前缀 */
        fun buildUrl(originImageUrl: String, rkey: String): String =
            MULTIMEDIA_HOST + originImageUrl + rkey

        private fun priorityOf(type: UInt): Int = when (type) {
            20u -> 0
            10u -> 1
            34u -> 2
            else -> 3
        }

        /** 配置目录下的缓存文件，拿不到配置目录时返回 null */
        private fun cacheFile(): File? = runCatching {
            ShamrockConfig.resolveConfigFile(CACHE_FILE_NAME)
        }.getOrNull()

        /** 解析成功后把整表写入磁盘；写入失败只告警，不影响内存缓存 */
        private fun persist(list: List<RkeyEntry>, at: Long) {
            val file = cacheFile()
            if (file == null) {
                LogCenter.log("$TAG 无法获取配置目录，跳过 rkey 缓存写入", Level.WARN)
                return
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            runCatching {
                val cache = RkeyCache(
                    updatedAt = at / 1000,
                    entries = list.map { it.toCacheEntry() }
                )
                tmp.writeText(GlobalJson5.encodeToString(cache))
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }.onFailure {
                tmp.delete()
                LogCenter.log("$TAG 写入 rkey 缓存失败: $it", Level.WARN)
            }
        }

        /** hook 注册时尝试从磁盘恢复未过期的 rkey */
        private fun restoreFromDisk() {
            val file = cacheFile()
            if (file == null) {
                LogCenter.log("$TAG 无法获取配置目录，跳过 rkey 缓存恢复", Level.WARN)
                return
            }
            if (!file.exists()) {
                LogCenter.log("$TAG 未找到磁盘 rkey 缓存 via=disk", Level.INFO)
                return
            }
            val cache = runCatching {
                GlobalJson5.decodeFromString<RkeyCache>(file.readText())
            }.onFailure {
                LogCenter.log("$TAG 读取磁盘 rkey 缓存失败: $it via=disk", Level.WARN)
            }.getOrNull()
            if (cache == null) return

            val nowSec = System.currentTimeMillis() / 1000
            val alive = cache.entries
                .filter { it.rkey.isNotBlank() }
                .filter { it.createTime + it.ttl > nowSec + EXPIRE_MARGIN_SEC }
                .map { it.toRkeyEntry() }
            if (alive.isEmpty()) {
                LogCenter.log("$TAG 磁盘 rkey 缓存均已过期 via=disk", Level.INFO)
                return
            }

            entries = alive
            updatedAt = cache.updatedAt * 1000
            LogCenter.log("$TAG 从磁盘恢复 rkey ${alive.size} 条 via=disk", Level.INFO)
        }
    }

    private val inited = AtomicBoolean(false)

    override fun invoke(ctx: Context) {
        if (!inited.compareAndSet(false, true)) return

        runCatching {
            restoreFromDisk()
        }.onFailure {
            LogCenter.log("$TAG 恢复 rkey 缓存失败: $it", Level.WARN)
        }

        runCatching {
            val clz = XposedHelpers.findClassIfExists(MSG_RESP_HANDLER, ctx.classLoader)
            if (clz == null) {
                LogCenter.log("$TAG MsgRespHandler not found, skip.", Level.WARN)
                return@runCatching
            }

            clz.hookMethod("dispatchRespMsg").before { param ->
                runCatching {
                    if (param.args.size < 2) return@runCatching
                    val msg = param.args[1] ?: return@runCatching

                    // 入参是消息对，命令与包体都在其 fromServiceMsg 字段里
                    val from = XposedHelpers.getObjectField(msg, "fromServiceMsg")
                        ?: return@runCatching
                    val cmd = XposedHelpers.callMethod(from, "getServiceCmd") as? String
                        ?: return@runCatching
                    if (cmd != RKEY_CMD) return@runCatching

                    val raw = XposedHelpers.callMethod(from, "getWupBuffer") as? ByteArray
                        ?: return@runCatching

                    val body = unwrapWupBody(raw)
                    val list = parseRkeyResponse(body) ?: return@runCatching

                    val at = System.currentTimeMillis()
                    entries = list
                    updatedAt = at
                    persist(list, at)
                    val ttl = list.maxOfOrNull { it.ttl } ?: 0uL
                    LogCenter.log("$TAG 已更新 rkey 共 ${list.size} 条 ttl=$ttl via=hook", Level.INFO)
                }
            }
        }.onFailure {
            LogCenter.log("$TAG hook MsgRespHandler failed: $it", Level.WARN)
        }
    }
}

/** 磁盘缓存结构，字段与 rkey.cache 文件保持一致 */
@Serializable
private data class RkeyCache(
    val updatedAt: Long = 0L,
    val entries: List<RkeyCacheEntry> = emptyList()
)

@Serializable
private data class RkeyCacheEntry(
    val rkey: String = "",
    val ttl: Long = 0L,
    val storeId: Int = 0,
    val createTime: Long = 0L,
    val type: Int = 0
)

private fun RkeyEntry.toCacheEntry() = RkeyCacheEntry(
    rkey = rkey,
    ttl = ttl.toLong(),
    storeId = storeId.toInt(),
    createTime = createTime.toLong(),
    type = type.toInt()
)

private fun RkeyCacheEntry.toRkeyEntry() = RkeyEntry(
    rkey = rkey,
    ttl = ttl.coerceAtLeast(0L).toULong(),
    storeId = storeId.coerceAtLeast(0).toUInt(),
    createTime = createTime.coerceAtLeast(0L).toUInt(),
    type = type.coerceAtLeast(0).toUInt()
)

/** 与 QQ 一致：首字节为 0 时去掉 4 字节长度包头 */
private fun unwrapWupBody(raw: ByteArray): ByteArray =
    if (raw.size >= 4 && raw[0].toInt() == 0) raw.copyOfRange(4, raw.size) else raw

/** OIDB 包体可直接按 RkeyResp 解码 */
private fun parseRkeyResponse(bytes: ByteArray): List<RkeyEntry>? {
    if (bytes.isEmpty()) return null
    val resp = runCatching {
        ProtoBuf.decodeFromByteArray<RkeyResp>(bytes)
    }.getOrNull() ?: return null
    return resp.body?.list?.entries?.takeIf { it.isNotEmpty() }
}
