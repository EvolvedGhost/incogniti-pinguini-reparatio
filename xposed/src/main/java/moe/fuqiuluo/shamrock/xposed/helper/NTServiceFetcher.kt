package moe.fuqiuluo.shamrock.xposed.helper

import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.api.impl.MsgService
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.fuqiuluo.shamrock.helper.Level
import moe.fuqiuluo.shamrock.helper.LogCenter
import moe.fuqiuluo.shamrock.remote.service.PacketReceiver
import moe.fuqiuluo.shamrock.remote.service.listener.AioListener
import moe.fuqiuluo.shamrock.remote.service.listener.PrimitiveListener
import moe.fuqiuluo.shamrock.tools.hookMethod
import moe.fuqiuluo.shamrock.utils.PlatformUtils

internal object NTServiceFetcher {
    private lateinit var iKernelService: IKernelService
    private val lock = Mutex()
    private var curKernelHash = 0

    suspend fun onFetch(service: IKernelService) {
        lock.withLock {
            val msgService = service.msgService ?: return
            val sessionService = service.wrapperSession ?: return
            //val groupService = sessionService.groupService ?: return

            val curHash = service.hashCode() + msgService.hashCode()
            if (isInitForNt(curHash)) return

            PacketHandler.initPacketHandler()
            PacketReceiver.init()

            LogCenter.log("Fetch kernel service successfully: $curKernelHash,$curHash,${PlatformUtils.isMainProcess()}")
            curKernelHash = curHash
            this.iKernelService = service


            initNTKernelListener(msgService)
            antiBackgroundMode(sessionService)
            // QQ 9.x：群列表只能靠内核监听推送，这里尽早挂上，之后 get_group_list 直接读缓存
            runCatching { GroupListCache.attach(sessionService.groupService) }
                .onFailure { LogCenter.log("GroupListCache 初始化失败: ${it.message}", Level.WARN) }
            //hookGuildListener(sessionService)
        }
    }

    /*
    private fun hookGuildListener(sessionService: IQQNTWrapperSession) {
        val guildService = sessionService.guildService
        XposedBridge.hookMethod(guildService::addKernelGuildListener.javaMethod, object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                val service = param?.thisObject as IKernelGuildService
                service.addKernelGuildListener(KernelGuildListener)
                LogCenter.log("Register Guild listener successfully.")
            }
        })
    }
    */

    private inline fun isInitForNt(hash: Int): Boolean {
        return hash == curKernelHash
    }

    private fun initNTKernelListener(msgService: MsgService) {
        if (!PlatformUtils.isMainProcess()) return

        try {
            LogCenter.log("Register MSG listener successfully.")
            msgService.addMsgListener(AioListener)
            startMsgListenerGuard(msgService)

            // 接口缺失 暂不使用
            //groupService.addKernelGroupListener(GroupEventListener)
            //LogCenter.log("Register Group listener successfully.")

            PrimitiveListener.registerListener()
        } catch (e: Throwable) {
            LogCenter.log(e.stackTraceToString(), Level.WARN)
        }
    }

    private fun antiBackgroundMode(sessionService: IQQNTWrapperSession) {
        try {
            sessionService.javaClass.hookMethod("switchToBackGround").before {
                LogCenter.log({ "阻止进入后台模式！" }, Level.DEBUG)
                it.result = null
            }

            val msgService = sessionService.msgService
            msgService.javaClass.hookMethod("switchBackGroundForMqq").before {
                LogCenter.log({ "阻止进入后台模式！" }, Level.DEBUG)
                val cb = it.args[1] as IOperateCallback
                cb.onResult(-1, "injected")
                it.result = null
            }
            msgService.javaClass.hookMethod("switchBackGround").before {
                LogCenter.log({ "阻止进入后台模式！" }, Level.DEBUG)
                val cb = it.args[1] as IOperateCallback
                cb.onResult(-1, "injected")
                it.result = null
            }
            LogCenter.log({ "反后台模式注入成功！" }, Level.DEBUG)
        } catch (e: Throwable) {
            LogCenter.log("Keeping NT alive failed: ${e.message}", Level.WARN)
        }
    }

    val kernelService: IKernelService
        get() = iKernelService

    /**
     * 消息监听会静默脱落：QQ 仍在正常收消息（界面有新增、内核查询正常），
     * 但 onRecvMsg 不再被调用，表现为 OneBot 客户端一条事件都收不到。
     * 这里周期性检查"距离上次消息事件过了多久"，超过阈值就重新注册一次监听。
     */
    private fun startMsgListenerGuard(msgService: Any) {
        Thread {
            var lastReRegister = 0L
            while (true) {
                try {
                    Thread.sleep(60_000L)
                    val silentMs = System.currentTimeMillis() - AioListener.lastMsgAt
                    // 刚启动或刚注册过不算异常
                    if (AioListener.lastMsgAt == 0L) continue
                    if (silentMs < GUARD_SILENCE_MS) continue
                    val now = System.currentTimeMillis()
                    if (now - lastReRegister < GUARD_SILENCE_MS) continue
                    lastReRegister = now
                    LogCenter.log(
                        "[MsgGuard] 已 ${silentMs / 1000}s 未收到消息事件，重新注册监听 (累计 ${AioListener.msgCount})",
                        Level.WARN
                    )
                    runCatching {
                        val svcCls = msgService.javaClass
                        svcCls.methods.firstOrNull { it.name == "removeMsgListener" && it.parameterCount == 1 }
                            ?.invoke(msgService, AioListener)
                        Thread.sleep(300L)
                        svcCls.methods.firstOrNull { it.name == "addMsgListener" && it.parameterCount == 1 }
                            ?.invoke(msgService, AioListener)
                        LogCenter.log("[MsgGuard] 监听已重新注册")
                    }.onFailure { LogCenter.log("[MsgGuard] 重注册失败: ${it.javaClass.simpleName}", Level.WARN) }
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Throwable) {
                    runCatching { LogCenter.log("[MsgGuard] 异常: ${e.javaClass.simpleName}: ${e.message}", Level.WARN) }
                }
            }
        }.apply { isDaemon = true; name = "shamrock-msg-guard" }.start()
    }

    private const val GUARD_SILENCE_MS = 180_000L   // 3 分钟无消息事件即视为脱落
}
