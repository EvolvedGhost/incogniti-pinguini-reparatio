package moe.fuqiuluo.qqinterface.util

import moe.fuqiuluo.shamrock.helper.Level
import moe.fuqiuluo.shamrock.helper.LogCenter
import java.lang.reflect.Method
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 生成发送请求时使用的序列号。
 *
 * 优先复用运行环境中已有的序列号生成器，取其方法返回值；该方法在当前版本中并非静态成员，
 * 直接按静态方式调用会在链接阶段失败，因此这里统一通过反射调用。
 * 取不到时退化为本地自增计数，保证每次请求都得到不同的正整数。
 */
internal object MsfSeq {
    private const val CORE_CLASS_NAME = "com.tencent.mobileqq.msf.core.MsfCore"

    @Volatile
    private var coreMethod: Method? = null

    @Volatile
    private var coreInstance: Any? = null

    @Volatile
    private var resolved = false

    private val logged = AtomicBoolean(false)
    private val localSeq = AtomicInteger(Random().nextInt().coerceAtLeast(1))
    private val localSeqLock = Any()

    fun next(): Int {
        if (!resolved) {
            synchronized(this) {
                if (!resolved) {
                    resolveCore()
                    resolved = true
                }
            }
        }

        val method = coreMethod
        val instance = coreInstance
        if (method != null && instance != null) {
            try {
                val seq = method.invoke(instance) as? Int
                if (seq != null) {
                    logOnce(useCore = true)
                    return seq
                }
            } catch (_: Throwable) {
            }
        }

        logOnce(useCore = false)
        return nextLocal()
    }

    private fun resolveCore() {
        try {
            val coreClass = Class.forName(CORE_CLASS_NAME)
            val instance = coreClass.getField("sCore").get(null) ?: return
            val method = coreClass.getMethod("getNextSeq")
            coreInstance = instance
            coreMethod = method
        } catch (_: Throwable) {
        }
    }

    private fun nextLocal(): Int = synchronized(localSeqLock) {
        val seq = localSeq.getAndIncrement()
        if (seq == Int.MAX_VALUE) {
            localSeq.set(1)
        }
        seq
    }

    private fun logOnce(useCore: Boolean) {
        if (!logged.compareAndSet(false, true)) {
            return
        }

        runCatching {
            if (useCore) {
                LogCenter.log("[MsfSeq] 已通过 MsfCore.sCore 获取序列号", Level.INFO)
            } else {
                LogCenter.log("[MsfSeq] 无法通过 MsfCore.sCore 获取序列号，改用本地计数", Level.WARN)
            }
        }
    }
}
