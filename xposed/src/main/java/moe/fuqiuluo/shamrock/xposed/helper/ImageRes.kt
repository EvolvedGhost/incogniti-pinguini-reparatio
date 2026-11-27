package moe.fuqiuluo.shamrock.xposed.helper

import com.tencent.qqnt.kernel.nativeinterface.FileTransNotifyInfo
import com.tencent.qqnt.kernel.nativeinterface.MsgConstant
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import kotlinx.coroutines.delay
import moe.fuqiuluo.shamrock.helper.Level
import moe.fuqiuluo.shamrock.helper.LogCenter
import moe.fuqiuluo.shamrock.remote.service.config.ShamrockConfig
import moe.fuqiuluo.shamrock.utils.FileUtils
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 图片落盘与对外 URL。
 *
 * 客户端上报的图片直链不可依赖：md5 直链已失效，originImageUrl 缺少 rkey 会被
 * 多媒体域名拒绝，收到的消息里 sourcePath 通常为空、原图并未落盘。
 *
 * 因此这里主动请求内核下载原图：
 *   wrapperSession.richMediaService.downloadRichMediaInVisit(RichDownLoadReq)
 * 该接口没有回调，落盘时机只能通过 IKernelMsgListener.onRichMediaDownloadComplete(
 * FileTransNotifyInfo) 得知。这里维护 wanted 登记表：下载完成 -> 与请求侧的
 * elementId 关联 -> 落到 FileUtils.getFile(md5) 的约定位置（files/<md5 后 4 位>/<md5>），
 * 由已有的 /res/<md5> 路由对外提供。
 *
 * 约束：消息转换路径只能“登记 + 起后台线程后立即返回”，阻塞会拖停整条消息推送。
 */
object ImageRes {
    private val published = ConcurrentHashMap<String, String>()   // md5 -> url
    private val wanted = ConcurrentHashMap<String, Long>()         // 等待落盘的 md5 -> 过期时间
    private const val MAX_COPY = 32L * 1024 * 1024
    private const val WANTED_TTL = 60_000L        // 等待登记的存活时间
    private const val MAX_PENDING = 12            // 同时等待落盘的上限
    private const val THUMB_FALLBACK_WAIT_MS = 8_000L  // 原图等这么久不到就退缩略图
    private const val MAX_PUBLISHED = 4096        // 已发布 URL 缓存上限
    private const val NATIVE_CONTEXT_TTL = 10 * 60_000L // 原生直链兜底上下文存活时间
    private const val MAX_NATIVE_CONTEXT = 4096   // 原生直链兜底上下文上限
    private const val MAX_PIC_DUMP = 5            // 诊断日志最多打印的图片数
    private const val MAX_PIC_DUMP_STRING = 300   // 诊断日志里的字符串长度上限

    private val picDumpMd5 = ConcurrentHashMap.newKeySet<String>()

    /** 供 /res 在本地取不到时申请原生直链；element 与聊天上下文保留到过期 */
    data class Pending(
        val element: MsgElement,
        val chatType: Int,
        val peerUid: String,
        val groupUin: Long,
        val expireAt: Long
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shamrock-image-res").apply { isDaemon = true }
    }

    /** 单张图片的内核下载参数，供后台下载线程使用 */
    private data class Meta(
        val element: MsgElement?,
        val chatType: Int,
        val peerUid: String,
        val senderUid: String,
        val msgId: Long,
        val msgSeq: Long,
        val msgRandom: Long,
        val msgTime: Long
    )

    private val known = ConcurrentHashMap<String, Meta>()
    private const val MAX_KNOWN = 128

    // 完成事件里的文件名是 QQ 内部缓存名（Cache_xxx），内容也可能被重新编码，
    // 因此不能靠 md5 匹配。改用请求与完成事件共享的 msgElementId / msgId 关联。
    private val byElement = ConcurrentHashMap<Long, String>()   // msgElementId -> md5
    private val byMsg = ConcurrentHashMap<Long, String>()       // msgId -> md5
    private const val MAX_LINKS = 256

    @Volatile
    private var baseUrl: String? = null       // 对外基地址
    @Volatile
    private var baseLocked = false            // 由配置或客户端 Host 确定后不再变化

    /** 供 /res 路由使用的目标文件（与 FileUtils.getFile 保持一致的布局） */
    fun targetFile(md5: String): File = FileUtils.getFile(md5.lowercase())

    /** 已知本地有文件时直接给出 URL */
    fun urlFor(md5: String): String? {
        val key = md5.lowercase()
        published[key]?.let { return it }
        val f = targetFile(key)
        return if (f.isFile && f.length() > 0) {
            "${resolveBase()}/res/$key".also { published[key] = it }
        } else null
    }

@Volatile
    private var downloadFlag: Boolean? = null

    /** 可选开关：files/shamrock_image_dl.txt 写 0 表示只发链接、不触发内核下载（排查用） */
    private fun downloadEnabled(): Boolean {
        downloadFlag?.let { return it }
        val enabled = runCatching {
            val f = FileUtils.getFile("shamrock_image_dl.txt")
            if (f.isFile) f.readText().trim() != "0" else true
        }.getOrDefault(true)
        downloadFlag = enabled
        return enabled
    }

    /**
     * 本地兜底：内核取不到原图时，看看 QQ 自己在磁盘上有没有任何版本的这张图。
     *
     * QQ 的取图缩略图缓存按 md5 命名（Tencent/QQfile_recv/.thumbnails/<md5>_750.jpg），
     * 用户点开过的图片会留在这里，能拿到就返回它 —— 宁可给小图也不给客户端 404。
     */
    fun localFallback(md5: String): File? {
        val key = md5.lowercase()
        targetFile(key).takeIf { it.isFile && it.length() > 0 }?.let { return it }
        // getFile 返回 <externalFilesDir>/<md5 后 4 位>/<md5>，上溯三级得到应用私有根目录，
        // Tencent 缓存目录与之同级
        val appRoot = targetFile(key).parentFile?.parentFile?.parentFile
        val roots = ArrayList<File>()
        if (appRoot != null) {
            roots.add(File(appRoot, "Tencent/QQfile_recv/.thumbnails"))
            roots.add(File(appRoot, "Tencent/MobileQQ/.thumbnails"))
            roots.add(File(appRoot, "Tencent/MobileQQ/thumb"))
        }
        for (dir in roots) {
            val hit = runCatching {
                dir.listFiles()?.firstOrNull {
                    it.isFile && it.length() > 0 && it.name.lowercase().startsWith(key)
                }
            }.getOrNull()
            if (hit != null) return hit
        }
        return null
    }

    /** 串行下载队列：单个线程依次执行，队列满才丢弃（丢弃会记日志，便于观察） */
    private val downloadQueue = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(256),
        { r -> Thread(r, "ShamrockImageDl").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
    )

    private fun submitDownload(task: () -> Unit) {
        runCatching { downloadQueue.execute(task) }
            .onFailure { LogCenter.log("[ImageRes] 下载队列已满，丢弃任务", Level.WARN) }
    }

    /** 后台线程里轮询等待目标文件出现 */
    private fun waitForFile(md5: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val f = targetFile(md5)
            if (f.isFile && f.length() > 0) return true
            try { Thread.sleep(300L) } catch (e: InterruptedException) { return false }
        }
        val f = targetFile(md5)
        return f.isFile && f.length() > 0
    }

    /** 自有线程上执行：把本地已有文件复制进 /res 目录，成功返回 true */
    private fun copyFrom(md5: String, path: String): Boolean {
        val src = File(path)
        if (!src.isFile || src.length() <= 0L || src.length() > MAX_COPY) return false
        if (!copyToRes(md5, src)) return false
        wanted.remove(md5)
        rememberPublished(md5)
        return true
    }

    /** 记录已发布 URL，条目过多时整体回收，避免无限增长 */
    private fun rememberPublished(md5: String) {
        published[md5] = "${resolveBase()}/res/$md5"
        if (published.size > MAX_PUBLISHED) published.clear()
    }

    /** 将对象的所有无参 getter 拼成一行，任何单个 getter 失败都只影响自己的字段 */
    private fun dumpGetters(target: Any?): String {
        if (target == null) return "null"
        return runCatching {
            target.javaClass.methods
                .asSequence()
                .filter {
                    it.parameterCount == 0 &&
                        it.name.startsWith("get") &&
                        it.name != "getClass" &&
                        Modifier.isPublic(it.modifiers)
                }
                .sortedBy { it.name }
                .joinToString(",") { method ->
                    val field = method.name.removePrefix("get")
                        .replaceFirstChar { char -> char.lowercaseChar() }
                    val value = runCatching { formatDumpValue(method.invoke(target)) }
                        .getOrElse { error ->
                            val cause = (error as? InvocationTargetException)?.targetException ?: error
                            return@joinToString "$field=<err:${cause.javaClass.simpleName}>"
                        }
                    "$field=$value"
                }
        }.getOrElse { "<err:${it.javaClass.simpleName}>" }
    }

    /** 诊断值格式化：数组只记长度，字符串截断并压成单行 */
    private fun formatDumpValue(value: Any?): String = when {
        value == null -> "null"
        value.javaClass.isArray -> {
            val type = value.javaClass.componentType?.simpleName ?: "object"
            "$type[${java.lang.reflect.Array.getLength(value)}]"
        }
        value is String -> {
            val text = if (value.length > MAX_PIC_DUMP_STRING) {
                value.take(MAX_PIC_DUMP_STRING) + "..."
            } else value
            text.replace("\r", "\\r").replace("\n", "\\n")
        }
        else -> value.toString().replace("\r", "\\r").replace("\n", "\\n")
    }

    /** 每张图片最多诊断一次；全局最多打印 MAX_PIC_DUMP 张 */
    private fun dumpPicOnce(md5: String, element: MsgElement?) {
        runCatching {
            val key = md5.lowercase()
            synchronized(picDumpMd5) {
                if (picDumpMd5.size >= MAX_PIC_DUMP || !picDumpMd5.add(key)) return
            }
            val msgElement = dumpGetters(element)
            val picElement = dumpGetters(runCatching { element?.picElement }.getOrNull())
            LogCenter.log(
                "[PicDump] md5=$key MsgElement(${MsgElement::class.java.name})={$msgElement} " +
                    "PicElement(com.tencent.qqnt.kernel.nativeinterface.PicElement)={$picElement}",
                Level.INFO
            )
        }
    }

    private fun rememberPendingContext(
        md5: String,
        element: MsgElement?,
        chatType: Int,
        peerUid: String
    ) {
        if (element == null) return
        val now = System.currentTimeMillis()
        if (pending.size >= MAX_NATIVE_CONTEXT) {
            pending.entries.removeIf { it.value.expireAt <= now }
            if (pending.size >= MAX_NATIVE_CONTEXT) pending.clear()
        }
        val groupUin = if (chatType == MsgConstant.KCHATTYPEGROUP) {
            peerUid.toLongOrNull() ?: 0L
        } else {
            0L
        }
        pending[md5] = Pending(element, chatType, peerUid, groupUin, now + NATIVE_CONTEXT_TTL)
    }

    /** 读取仍有效的原生直链兜底上下文；本地链路不需要时不会触发任何 OIDB 请求 */
    fun pendingContext(md5: String): Pending? {
        val key = md5.lowercase()
        val entry = pending[key] ?: return null
        if (entry.expireAt > System.currentTimeMillis()) return entry
        pending.remove(key, entry)
        return null
    }

    /**
     * 保证图片最终可用：本地已有 -> 直接 URL；否则登记 + 后台触发下载，**立即返回 URL**。
     * 文件未到位时 /res 返回 404，稍后重试即可。
     */
    fun ensure(
        md5: String,
        chatType: Int,
        peerUid: String,
        senderUid: String,
        element: MsgElement?,
        sourcePath: String? = null,
        msgId: Long = 0L,
        msgSeq: Long = 0L,
        msgRandom: Long = 0L,
        msgTime: Long = 0L
    ): String {
        val key = md5.lowercase()
        dumpPicOnce(key, element)
        rememberPendingContext(key, element, chatType, peerUid)
        urlFor(key)?.let { return it }

        val url = "${resolveBase()}/res/$key"
        if (known.size > MAX_KNOWN) known.clear()
        known[key] = Meta(element, chatType, peerUid, senderUid, msgId, msgSeq, msgRandom, msgTime)
        if (byElement.size > MAX_LINKS) byElement.clear()
        if (byMsg.size > MAX_LINKS) byMsg.clear()
        runCatching { element?.elementId ?: 0L }.getOrNull()?.takeIf { it > 0L }?.let { byElement[it] = key }
        if (msgId > 0L) byMsg[msgId] = key
        wanted.entries.removeIf { it.value <= System.currentTimeMillis() }
        if (!isWanted(key) && wanted.size < MAX_PENDING) {
            wanted[key] = System.currentTimeMillis() + WANTED_TTL
            Thread {
                if (!downloadEnabled()) {
                    // 只登记不下载：用于排查内核下载与消息通路的相互影响
                    wanted.remove(key)
                    return@Thread
                }
                // 先用消息里带的本地路径（有的话），失败再让内核下载。
                // 只走前一条路的话，路径失效的图片会永远 404。
                val copied = sourcePath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { copyFrom(key, it) }.getOrDefault(false) }
                    ?: false
                if (!copied) {
                    // 内核下载统一排进单线程队列：串行执行，既不并发压内核，也不丢任务
                    submitDownload {
                        runCatching {
                            triggerDownload(key, chatType, peerUid, senderUid, element, msgId, msgSeq, msgRandom, msgTime)
                        }.onFailure { LogCenter.log("[ImageRes] 触发下载失败 $key: ${it.javaClass.simpleName}", Level.WARN) }

                        // 兜底：这类图片内核永远不回调，等一会儿再单独申请一次缩略图。
                        // 宁可给小图也不让客户端拿到 404。
                        if (!waitForFile(key, THUMB_FALLBACK_WAIT_MS)) {
                            LogCenter.log("[ImageRes] 原图未到，退而取缩略图 $key")
                            runCatching {
                                triggerDownload(key, chatType, peerUid, senderUid, element,
                                    msgId, msgSeq, msgRandom, msgTime, downloadType = 1)
                            }
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
        }
        return url
    }

    /**
     * 等待图片就绪：文件已在则立即返回；该图正在后台下载时最多等 timeoutMs。
     *
     * 只做被动等待，**不在这里重试内核下载**：请求链路上反复调内核会占满内核的
     * 处理线程，导致整个接口失去响应。下载请求的发起一律留在消息转换时的后台线程里。
     */
    suspend fun await(md5: String, timeoutMs: Long = 20_000L): File? {
        val key = md5.lowercase()
        targetFile(key).takeIf { it.isFile && it.length() > 0 }?.let { return it }
        if (!isWanted(key)) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(150L)
            targetFile(key).takeIf { it.isFile && it.length() > 0 }?.let { return it }
            if (!isWanted(key)) break
        }
        return targetFile(key).takeIf { it.isFile && it.length() > 0 }
    }

    /** 该图是否仍在等待下载（登记有有效期，失败的请求不会永久占用等待状态） */
    private fun isWanted(md5: String): Boolean =
        (wanted[md5] ?: 0L) > System.currentTimeMillis()

    /** 按文件头魔数判断 MIME 类型：/res 链接不带扩展名，只能看内容 */
    fun mimeOf(file: File): String {
        val head = ByteArray(12)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        fun at(i: Int) = head[i].toInt().and(0xFF)
        return when {
            n >= 3 && at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            n >= 4 && at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            n >= 3 && at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            n >= 12 && at(0) == 0x52 && at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
            n >= 2 && at(0) == 0x42 && at(1) == 0x4D -> "image/bmp"
            n >= 4 && at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3 -> "video/webm"
            else -> "application/octet-stream"
        }
    }

    /**
     * 由 AioListener.onRichMediaDownloadComplete 调用。
     *
     * 该回调跑在 QQ 自己的线程上，所以**只取路径就返回**：算 md5、复制文件这类
     * 活全部丢到自有线程。该回调对所有富媒体（图片/语音/视频/文件）都会触发，
     * 在回调里做 IO 会占住 QQ 的处理线程，累积起来会让消息回调与内核查询整体停摆。
     */
    fun onDownloadComplete(info: FileTransNotifyInfo?) {
        val path = runCatching { info?.filePath }.getOrNull() ?: return
        if (path.isBlank()) return
        if (wanted.isEmpty()) return
        // 回调线程上只查表，不碰文件
        val eid = runCatching { info?.msgElementId ?: 0L }.getOrDefault(0L)
        val mid = runCatching { info?.msgId ?: 0L }.getOrDefault(0L)
        val md5 = byElement.remove(eid) ?: byMsg[mid]
        LogCenter.log("[ImageRes] 完成事件 ${File(path).name} eid=$eid mid=$mid → md5=${md5 ?: "未匹配"}")
        runCatching { worker.execute { handleCompleted(path, md5) } }
    }

    /** 自有线程上执行：命中等待中的 md5 才复制到 /res 目录 */
    private fun handleCompleted(path: String, knownMd5: String?) {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return
        val now = System.currentTimeMillis()
        wanted.entries.removeIf { it.value <= now }   // 清理过期的登记
        if (wanted.isEmpty()) return
        if (file.length() > MAX_COPY) return           // 超大文件不是图片

        // 优先用 elementId/msgId 关联到的 md5，其次文件名，最后才整文件哈希
        val md5 = knownMd5
            ?: wanted.keys.firstOrNull { file.name.contains(it, ignoreCase = true) }
            ?: runCatching { md5Of(file) }.getOrNull()
            ?: return
        if (!isWanted(md5)) return

        val size = file.length()
        if (copyToRes(md5, file)) {
            wanted.remove(md5)
            rememberPublished(md5)
            LogCenter.log("[ImageRes] 下载完成并发布 $md5 (${size}B)")
        }
    }

    // ---------- 内部实现 ----------

    private fun copyToRes(md5: String, src: File): Boolean {
        val dest = targetFile(md5)
        if (dest.isFile && dest.length() == src.length()) return true
        return runCatching {
            dest.parentFile?.mkdirs()
            // 硬链接优先（同分区零拷贝）：链接本身是原子操作，读到的一定是完整文件。
            // 绝不移动 QQ 自己的媒体文件：搬走会破坏 QQ 的缓存与后续发图。
            val linked = runCatching { java.nio.file.Files.createLink(dest.toPath(), src.toPath()) }.isSuccess
            if (!linked) {
                // 跨分区只能复制：先写临时文件再原子改名，
                // 否则 /res 可能在复制途中读到半个文件，客户端拿到截断的图片。
                val tmp = File(dest.parentFile, "${dest.name}.part")
                src.copyTo(tmp, overwrite = true)
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    throw IllegalStateException("publish rename failed")
                }
            }
            true
        }.getOrElse {
            LogCenter.log("[ImageRes] 复制失败 ${it.javaClass.simpleName}: ${it.message}", Level.WARN)
            false
        }
    }

    private fun triggerDownload(
        md5: String,
        chatType: Int,
        peerUid: String,
        senderUid: String,
        element: MsgElement?,
        msgId: Long,
        msgSeq: Long,
        msgRandom: Long,
        msgTime: Long,
        downloadType: Int = if (chatType == MsgConstant.KCHATTYPEGROUP) 2 else 1
    ) {
        if (element == null) {
            LogCenter.log("[ImageRes] $md5 缺少 MsgElement，无法触发内核下载", Level.WARN)
            return
        }
        val kernel = runCatching { NTServiceFetcher.kernelService }.getOrNull() ?: return
        val session = runCatching { kernel.wrapperSession }.getOrNull() ?: return
        val richMedia = runCatching { session.richMediaService }.getOrNull() ?: run {
            LogCenter.log("[ImageRes] richMediaService 为 null", Level.WARN)
            return
        }

        // RichDownLoadReq 的字段是包级私有、无 setter，只能反射写
        val reqCls = Class.forName(
            "com.tencent.qqnt.kernel.nativeinterface.RichDownLoadReq",
            false,
            richMedia.javaClass.classLoader
        )
        val req = reqCls.getConstructor().newInstance()
        fun set(name: String, value: Any?) {
            runCatching {
                val f = reqCls.getDeclaredField(name)
                f.isAccessible = true
                f.set(req, value)
            }.onFailure { LogCenter.log("[ImageRes] 设置 $name 失败: ${it.message}", Level.WARN) }
        }
        set("elem", element)
        set("chatType", chatType)
        set("peerUid", peerUid)
        set("senderUid", senderUid)
        // 注意：填入 msgId/msgSeq/msgRandom/msgTime 会让内核拒绝下载，
        // 下载完成回调一次都不会再触发，因此保持默认 0。
        set("downloadType", downloadType)
        set("thumbSize", 0)
        set("useHttps", 1)

        val method = richMedia.javaClass.methods.firstOrNull {
            it.name == "downloadRichMediaInVisit" && it.parameterCount == 1
        } ?: run {
            LogCenter.log("[ImageRes] 找不到 downloadRichMediaInVisit", Level.WARN)
            return
        }
        method.isAccessible = true
        method.invoke(richMedia, req)
        LogCenter.log("[ImageRes] 已请求内核下载 $md5 (chatType=$chatType, peer=$peerUid)")
    }

    private fun md5Of(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 记录客户端实际连接的 Host，作为 /res 链接的对外地址。
     *
     * 客户端经回环地址或本机网卡地址访问时，该地址对其他客户端不可达，此类 Host 不采纳；
     * 端口固定为 HTTP 端口，避免 WebSocket 客户端把资源链接拼到只提供 WS 的端口上。
     */
    fun observeHost(host: String?) {
        if (baseLocked) return          // 已确定就不再判断，避免每个请求都枚举网卡
        val h = host?.trim()?.takeIf { it.isNotBlank() } ?: return
        val hostOnly = h.substringBefore(":").trim().takeIf { it.isNotEmpty() } ?: return
        if (hostOnly == "localhost" || hostOnly == "0.0.0.0") return
        if (isSelfAddress(hostOnly)) return
        val httpPort = runCatching { ShamrockConfig.getPort() }.getOrDefault(5700)
        lockBase("http://$hostOnly:$httpPort")
    }

    /** 定下对外地址；已拼好的链接随地址变化失效 */
    private fun lockBase(url: String) {
        if (baseLocked) return
        baseUrl = url
        baseLocked = true
        published.clear()
    }

    private fun resolveBase(): String {
        baseUrl?.let { return it }
        val port = runCatching { ShamrockConfig.getPort() }.getOrDefault(5700)
        // 可选覆盖：files/shamrock_base_url.txt 内写一行对外地址，
        // 用于端口映射等客户端 Host 与实际访问地址不一致的场景
        val configured = runCatching {
            val f = FileUtils.getFile("shamrock_base_url.txt")
            if (f.isFile) f.readText().trim().trimEnd('/') else null
        }.getOrNull()?.takeIf { it.startsWith("http") }
        if (configured != null) {
            lockBase(configured)
            return configured
        }
        return "http://${localIpv4() ?: "127.0.0.1"}:$port".also { baseUrl = it }
    }

    /** 是否为本机自身地址（回环或任一网卡地址），这类地址只在本机范围内可达 */
    private fun isSelfAddress(host: String): Boolean {
        if (host == "127.0.0.1") return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .mapNotNull { it.hostAddress?.substringBefore('%') }
                .any { it == host }
        }.getOrDefault(false)
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .firstOrNull { it.isNotBlank() && it != "127.0.0.1" }
    }.getOrNull()
}
