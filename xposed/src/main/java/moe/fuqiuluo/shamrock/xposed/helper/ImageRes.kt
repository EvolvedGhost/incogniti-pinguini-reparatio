package moe.fuqiuluo.shamrock.xposed.helper

import com.tencent.qqnt.kernel.nativeinterface.FileTransNotifyInfo
import com.tencent.qqnt.kernel.nativeinterface.MsgConstant
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import moe.fuqiuluo.shamrock.helper.Level
import moe.fuqiuluo.shamrock.helper.LogCenter
import moe.fuqiuluo.shamrock.remote.service.config.ShamrockConfig
import moe.fuqiuluo.shamrock.utils.FileUtils
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片落盘与对外 URL。
 *
 * QQ 9.2.95 的图片链接均不可用：
 *  - md5 直链 http://gchat.qpic.cn/gchatpic_new/0/0-0-<MD5>/0?term=2 返回 404；
 *  - PicElement.originImageUrl 形如 /download?...&spec=0，缺少 rkey，
 *    请求多媒体域名返回 retcode -5503010（invalid rkey）；
 *  - 收到的群图片 PicElement.sourcePath 为 null，原图未被落盘。
 *
 * 因此主动请内核下载原图：
 *   wrapperSession.richMediaService.downloadRichMediaInVisit(RichDownLoadReq)
 * 该接口没有回调，落盘时机只能通过 IKernelMsgListener.onRichMediaDownloadComplete(
 * FileTransNotifyInfo) 得知。这里维护 wanted(md5) 集合：下载完成 -> 计算 md5 ->
 * 命中则落到 FileUtils.getFile(md5) 的约定位置（files/<md5 后 4 位>/<md5>），
 * 由已有的 /res/<md5> 路由对外提供。
 *
 * 约束：消息转换路径只能“登记 + 起后台线程后立即返回”，阻塞会拖停整条消息推送。
 */
object ImageRes {
    private val published = ConcurrentHashMap<String, String>()   // md5 -> url
    private val wanted = ConcurrentHashMap.newKeySet<String>()     // 等待落盘的 md5
    private const val MAX_COPY = 32L * 1024 * 1024

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

    /** 把已知本地路径的图片发布出去（sourcePath 可用时） */
    fun publish(md5: String, sourcePath: String?): String? {
        val key = md5.lowercase()
        published[key]?.let { return it }
        val src = sourcePath?.takeIf { it.isNotBlank() }?.let { File(it) } ?: return null
        if (!src.isFile || src.length() <= 0L || src.length() > MAX_COPY) return null
        if (!copyToRes(key, src)) return null
        return "${resolveBase()}/res/$key".also { published[key] = it }
    }

    /**
     * 保证图片最终可用：本地已有 -> 直接 URL；否则登记 + 后台触发下载，**立即返回 URL**。
     * 文件未到位时 /res 返回 404，稍后重试即可。
     */
    fun ensure(md5: String, chatType: Int, peerUid: String, senderUid: String, element: MsgElement?): String {
        val key = md5.lowercase()
        urlFor(key)?.let { return it }

        val url = "${resolveBase()}/res/$key"
        if (wanted.add(key)) {
            Thread {
                runCatching { triggerDownload(key, chatType, peerUid, senderUid, element) }
                    .onFailure { LogCenter.log("[ImageRes] 触发下载失败 $key: ${it.javaClass.simpleName}", Level.WARN) }
            }.apply { isDaemon = true }.start()
        }
        return url
    }

    /** 由 AioListener.onRichMediaDownloadComplete 调用 */
    fun onDownloadComplete(info: FileTransNotifyInfo?) {
        val path = runCatching { info?.filePath }.getOrNull() ?: return
        if (path.isBlank()) return
        val file = File(path)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_COPY) return
        if (wanted.isEmpty()) return   // 没人等就不做哈希，省开销

        val md5 = runCatching { md5Of(file) }.getOrNull() ?: return
        if (!wanted.contains(md5)) return

        val size = file.length()
        if (copyToRes(md5, file)) {
            wanted.remove(md5)
            published[md5] = "${resolveBase()}/res/$md5"
            LogCenter.log("[ImageRes] 下载完成并发布 $md5 (${size}B)")
        }
    }

    // ---------- 内部实现 ----------

    private fun copyToRes(md5: String, src: File): Boolean {
        val dest = targetFile(md5)
        if (dest.isFile && dest.length() == src.length()) return true
        return runCatching {
            dest.parentFile?.mkdirs()
            if (!dest.exists() && src.renameTo(dest)) return true
            src.copyTo(dest, overwrite = true)
            true
        }.getOrElse {
            LogCenter.log("[ImageRes] 复制失败 ${it.javaClass.simpleName}: ${it.message}", Level.WARN)
            false
        }
    }

    private fun triggerDownload(md5: String, chatType: Int, peerUid: String, senderUid: String, element: MsgElement?) {
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
        set("downloadType", if (chatType == MsgConstant.KCHATTYPEGROUP) 2 else 1)
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
