package moe.fuqiuluo.shamrock.remote.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.document
import io.ktor.server.response.respond
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.fuqiuluo.shamrock.remote.action.handlers.*
import moe.fuqiuluo.shamrock.helper.Level
import moe.fuqiuluo.shamrock.helper.LogCenter
import moe.fuqiuluo.shamrock.tools.*
import moe.fuqiuluo.shamrock.xposed.helper.ImageRes
import moe.fuqiuluo.shamrock.xposed.hooks.PicRKeyHub
import java.net.HttpURLConnection
import java.net.URL

private fun formatFileName(file: String): String = file
    .replace(regex = "[{}\\-]".toRegex(), replacement = "")
    .replace(" ", "")
    .split(".")[0].lowercase()

private const val RKEY_PROBE_TOTAL_MS = 5_000L
private const val RKEY_PROBE_TRY_MS = 2_000L
private const val RKEY_PROBE_UA = "Shamrock/1.0"

private data class RkeyHit(val type: UInt, val url: String)

/**
 * 依次用候选 rkey 拼出原生直链并探测，命中返回类型与链接。
 * 整个过程总耗时不超过 [RKEY_PROBE_TOTAL_MS]，到点直接放弃。
 */
private suspend fun probeRkeyHit(originImageUrl: String, deadline: Long): RkeyHit? {
    val budget = deadline - System.currentTimeMillis()
    if (budget <= 0L) return null
    return withTimeoutOrNull(budget) {
        withContext(Dispatchers.IO) {
            val candidates = PicRKeyHub.candidatesWithType()
            if (candidates.isEmpty()) return@withContext null
            for ((rkey, type) in candidates) {
                if (System.currentTimeMillis() >= deadline) break
                val url = PicRKeyHub.buildUrl(originImageUrl, rkey)
                if (probeUrl(url, deadline)) return@withContext RkeyHit(type, url)
            }
            null
        }
    }
}

/** HEAD 探测；服务端不支持 HEAD 时退化为 GET 读取 1 字节后关闭 */
private fun probeUrl(url: String, deadline: Long): Boolean {
    val headTimeout = timeoutWithin(deadline) ?: return false
    var head: HttpURLConnection? = null
    var headCode = -1
    try {
        head = openProbe(url, "HEAD", headTimeout)
        headCode = head.responseCode
    } catch (_: Exception) {
        headCode = -1
    } finally {
        head?.disconnect()
    }
    if (headCode == HttpURLConnection.HTTP_OK) return true
    // 服务端对 HEAD 的拒绝方式不统一（部分服务直接返回 400），
    // 因此只要不是 200 就改用 GET 复核，避免把可用链接误判为失效。

    val getTimeout = timeoutWithin(deadline) ?: return false
    var get: HttpURLConnection? = null
    return try {
        get = openProbe(url, "GET", getTimeout)
        // 只取头部少量字节，确认可读即可，不下载完整内容
        get.setRequestProperty("Range", "bytes=0-1023")
        val code = get.responseCode
        (code == HttpURLConnection.HTTP_OK || code == 206) && get.inputStream.use { it.read() != -1 }
    } catch (_: Exception) {
        false
    } finally {
        get?.disconnect()
    }
}

private fun openProbe(url: String, method: String, timeoutMs: Int): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        instanceFollowRedirects = false
        useCaches = false
        // 固定标识来源，且不携带任何 Cookie
        setRequestProperty("User-Agent", RKEY_PROBE_UA)
        setRequestProperty("Accept", "*/*")
    }

private fun timeoutWithin(deadline: Long): Int? {
    val remaining = deadline - System.currentTimeMillis()
    if (remaining < 100L) return null
    return minOf(RKEY_PROBE_TRY_MS, remaining).toInt()
}

fun Routing.fetchRes() {
    getOrPost("/get_record") {
        val file = formatFileName( fetchGetOrThrow("file") )
        val format = fetchOrThrow("out_format")
        call.respondText(GetRecord(file, format), ContentType.Application.Json)
    }

    getOrPost("/get_image") {
        val file = formatFileName( fetchGetOrThrow("file") )
        call.respondText(GetImage(file), ContentType.Application.Json)
    }

    getOrPost("/upload_group_file") {
        val groupId = fetchOrThrow("group_id")
        val file = fetchOrThrow("file")
        val name = fetchOrThrow("name")
        call.respondText(UploadGroupFile(groupId, file, name), ContentType.Application.Json)
    }

    getOrPost("/upload_private_file") {
        val userId = fetchOrThrow("user_id")
        val file = fetchOrThrow("file")
        val name = fetchOrThrow("name")
        call.respondText(UploadPrivateFile(userId, file, name), ContentType.Application.Json)
    }

    getOrPost("/create_group_file_folder") {
        val groupId = fetchOrThrow("group_id")
        val name = fetchOrThrow("name")
        call.respondText(CreateGroupFileFolder(groupId, name), ContentType.Application.Json)
    }

    getOrPost("/delete_group_folder") {
        val groupId = fetchOrThrow("group_id")
        val id = fetchOrThrow("folder_id")
        call.respondText(DeleteGroupFolder(groupId, id), ContentType.Application.Json)
    }

    getOrPost("/delete_group_file") {
        val groupId = fetchOrThrow("group_id")
        val id = fetchOrThrow("file_id")
        val busid = fetchOrThrow("busid").toInt()
        call.respondText(DeleteGroupFile(groupId, id, busid), ContentType.Application.Json)
    }

    getOrPost("/get_group_file_system_info") {
        val groupId = fetchOrThrow("group_id")
        call.respondText(GetGroupFileSystemInfo(groupId), ContentType.Application.Json)
    }

    getOrPost("/get_group_root_files") {
        val groupId = fetchOrThrow("group_id")
        call.respondText(GetGroupRootFiles(groupId), ContentType.Application.Json)
    }

    getOrPost("/get_group_files_by_folder") {
        val groupId = fetchOrThrow("group_id")
        val folderId = fetchOrThrow("folder_id")
        call.respondText(GetGroupSubFiles(groupId, folderId), ContentType.Application.Json)
    }

    getOrPost("/get_group_file_url") {
        val groupId = fetchOrThrow("group_id")
        val id = fetchOrThrow("file_id")
        val busid = fetchOrThrow("busid").toInt()
        call.respondText(GetGroupFileUrl(groupId, id, busid), ContentType.Application.Json)
    }

    // 图片/媒体资源。链接不带扩展名，Content-Type 由文件头推断。
    // 该图仍在后台下载时短暂等待，避免客户端刚收到消息推送就来取图而拿到 404。
    route("/res/[a-fA-F0-9]{32}".toRegex()) {
        get {
            val md5 = call.request.document()
            val startAt = System.currentTimeMillis()
            val ua = call.request.headers["User-Agent"] ?: "-"
            var file = ImageRes.await(md5)
            // 内核取不到时，退到 QQ 本地已有的任何版本（多为用户看过的缩略图）
            if (file == null) {
                file = ImageRes.localFallback(md5)
                if (file != null) LogCenter.log("[res] $md5 用本地兜底文件 ${file.name} (${file.length()}B)")
            }
            // 本地与内核下载都拿不到时，用已有的 rkey 探测原生直链并重定向。
            if (file == null) {
                val origin = ImageRes.pendingContext(md5)
                    ?.element?.picElement?.originImageUrl
                    ?.takeIf { it.isNotBlank() }
                if (origin != null) {
                    if (PicRKeyHub.hasRkey()) {
                        val hit = probeRkeyHit(origin, System.currentTimeMillis() + RKEY_PROBE_TOTAL_MS)
                        if (hit != null) {
                            LogCenter.log(
                                "[res] $md5 302 命中 rkeyType=${hit.type} " +
                                    "waited=${System.currentTimeMillis() - startAt}ms"
                            )
                            call.response.headers.append(HttpHeaders.Location, hit.url)
                            call.respond(HttpStatusCode.Found)
                            return@get
                        }
                        LogCenter.log("[res] $md5 候选 rkey 全部不可用", Level.WARN)
                    } else {
                        LogCenter.log("[res] $md5 无可用 rkey", Level.WARN)
                    }
                }
            }
            val waited = System.currentTimeMillis() - startAt
            if (file == null) {
                LogCenter.log("[res] $md5 404 waited=${waited}ms ua=$ua", Level.WARN)
                call.respond(HttpStatusCode.NotFound)
            } else {
                LogCenter.log("[res] $md5 200 size=${file.length()} waited=${waited}ms ua=$ua")
                call.respond(LocalFileContent(file, ContentType.parse(ImageRes.mimeOf(file))))
            }
        }
    }
}
