package moe.fuqiuluo.shamrock.remote.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.document
import io.ktor.server.response.respond
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import moe.fuqiuluo.shamrock.remote.action.handlers.*
import moe.fuqiuluo.shamrock.helper.Level
import moe.fuqiuluo.shamrock.helper.LogCenter
import moe.fuqiuluo.shamrock.tools.*
import moe.fuqiuluo.shamrock.xposed.helper.ImageRes

private fun formatFileName(file: String): String = file
    .replace(regex = "[{}\\-]".toRegex(), replacement = "")
    .replace(" ", "")
    .split(".")[0].lowercase()

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