package moe.fuqiuluo.shamrock.remote.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.document
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.fuqiuluo.shamrock.remote.action.handlers.*
import moe.fuqiuluo.shamrock.helper.Level
import moe.fuqiuluo.shamrock.helper.LogCenter
import moe.fuqiuluo.shamrock.tools.*
import moe.fuqiuluo.shamrock.xposed.helper.ImageRes
import moe.fuqiuluo.shamrock.xposed.hooks.PicRKeyHub
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private fun formatFileName(file: String): String = file
    .replace(regex = "[{}\\-]".toRegex(), replacement = "")
    .replace(" ", "")
    .split(".")[0].lowercase()

private const val REMOTE_CONNECT_TIMEOUT_MS = 4_000
private const val REMOTE_READ_TIMEOUT_MS = 10_000
private const val REMOTE_UA = "Shamrock/1.0"

/** 已取回内容的远端连接包装，关闭时一并释放连接 */
private class RemoteImage(
    val stream: InputStream,
    val contentType: String,
    val contentLength: Long,
    private val connection: HttpURLConnection
) {
    fun close() {
        runCatching { stream.close() }
        runCatching { connection.disconnect() }
    }
}

/** 上一次取到内容所用的凭证类型，下次优先尝试，减少无用请求 */
@Volatile
private var preferredRkeyType: UInt? = null

private fun originOf(md5: String): String? =
    ImageRes.pendingContext(md5)?.element?.picElement?.originImageUrl?.takeIf { it.isNotBlank() }
        ?: ImageRes.originInfo(md5)?.originImageUrl?.takeIf { it.isNotBlank() }

private fun expectedSizeOf(md5: String): Long =
    ImageRes.pendingContext(md5)?.element?.picElement?.fileSize
        ?: ImageRes.originInfo(md5)?.fileSize
        ?: 0L

/** 文件长度是否已达到原图应有的大小，留 2% 余量 */
private fun isOriginalSize(actual: Long, expected: Long): Boolean =
    expected <= 0L || actual * 100L >= expected * 98L

/**
 * 取回远端原图内容。
 *
 * 内容由本服务取回后直接转发给客户端，避免依赖客户端是否跟随重定向；
 * 依次尝试已缓存的凭证，命中后记住该凭证类型，后续请求优先使用。
 */
private suspend fun openRemoteImage(md5: String): RemoteImage? = withContext(Dispatchers.IO) {
    val origin = originOf(md5) ?: return@withContext null
    if (!PicRKeyHub.hasRkey()) return@withContext null
    val preferred = preferredRkeyType
    val candidates = PicRKeyHub.candidatesWithType()
        .sortedBy { if (it.second == preferred) 0 else 1 }
    for ((rkey, type) in candidates) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(PicRKeyHub.buildUrl(origin, rkey)).openConnection() as HttpURLConnection).apply {
                connectTimeout = REMOTE_CONNECT_TIMEOUT_MS
                readTimeout = REMOTE_READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", REMOTE_UA)
                setRequestProperty("Accept", "image/*")
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                preferredRkeyType = type
                return@withContext RemoteImage(
                    stream = connection.inputStream,
                    contentType = connection.contentType ?: "application/octet-stream",
                    contentLength = connection.contentLengthLong,
                    connection = connection
                )
            }
            connection.disconnect()
        } catch (_: Exception) {
            runCatching { connection?.disconnect() }
        }
    }
    null
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
            val expected = expectedSizeOf(md5)
            val local = ImageRes.await(md5) ?: ImageRes.localFallback(md5)
            // 本地已是原图时直接返回，省掉一次远端下载
            if (local != null && isOriginalSize(local.length(), expected)) {
                LogCenter.log("[res] $md5 200 size=${local.length()} waited=${System.currentTimeMillis() - startAt}ms ua=$ua")
                call.respond(LocalFileContent(local, ContentType.parse(ImageRes.mimeOf(local))))
                return@get
            }
            // 本地缺失或只是缩略图：取回远端原图后转发，客户端无需跟随重定向
            val remote = openRemoteImage(md5)
            if (remote != null) {
                try {
                    LogCenter.log(
                        "[res] $md5 200 原图 size=${remote.contentLength} " +
                            "waited=${System.currentTimeMillis() - startAt}ms ua=$ua"
                    )
                    call.respondOutputStream(ContentType.parse(remote.contentType)) {
                        remote.stream.copyTo(this)
                    }
                    return@get
                } catch (e: Exception) {
                    LogCenter.log("[res] $md5 转发远端原图失败: ${e.javaClass.simpleName}", Level.WARN)
                } finally {
                    remote.close()
                }
            }
            // 远端不可用时退回本地副本，可能只是缩略图
            if (local != null) {
                LogCenter.log(
                    "[res] $md5 200 size=${local.length()} expected=$expected " +
                        "waited=${System.currentTimeMillis() - startAt}ms ua=$ua",
                    Level.WARN
                )
                call.respond(LocalFileContent(local, ContentType.parse(ImageRes.mimeOf(local))))
                return@get
            }
            LogCenter.log("[res] $md5 404 waited=${System.currentTimeMillis() - startAt}ms ua=$ua", Level.WARN)
            call.respond(HttpStatusCode.NotFound)
        }
    }
}
