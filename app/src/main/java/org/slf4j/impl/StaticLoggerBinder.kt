package org.slf4j.impl

import com.lansync.app.data.FileLogger
import org.slf4j.ILoggerFactory
import org.slf4j.Logger
import org.slf4j.helpers.MarkerIgnoringBase
import org.slf4j.spi.LoggerFactoryBinder

/**
 * 最小 SLF4J 1.7 绑定：把 netty / ktor 等库的日志转发进 lansync_debug.log。
 *
 * 背景：工程只有 slf4j-api 而无绑定，slf4j 静默降级为 NOP——netty 管道异常、
 * ktor 请求处理错误全部丢失，导致「接受连接后立刻 RST、应用日志无痕」无从定位。
 * slf4j 以 `Class.forName("org.slf4j.impl.StaticLoggerBinder")` 发现本类，因此
 * proguard 规则里有对应的 -keep（类名不可混淆）。INFO/WARN/ERROR 转发，DEBUG 以下丢弃。
 */
class StaticLoggerBinder : LoggerFactoryBinder {

    override fun getLoggerFactory(): ILoggerFactory = FACTORY

    override fun getLoggerFactoryClassStr(): String = ForwardingLoggerFactory::class.java.name

    companion object {
        /** slf4j 通过静态方法 `StaticLoggerBinder.getSingleton()` 取绑定（@JvmStatic 生成同名静态方法）。 */
        @JvmStatic
        val singleton: StaticLoggerBinder = StaticLoggerBinder()

        /** slf4j 1.7 版本探测字段（API 版本不匹配时会告警）。 */
        @JvmField
        val REQUESTED_API_VERSION: String = "1.7.16"

        private val FACTORY = ForwardingLoggerFactory()
    }
}

internal class ForwardingLoggerFactory : ILoggerFactory {
    override fun getLogger(name: String): Logger = ForwardingLogger(name)
}

internal class ForwardingLogger(private val name: String) : MarkerIgnoringBase() {

    override fun getName(): String = name

    private fun log(level: Char, msg: String, t: Throwable? = null) {
        when (level) {
            'E' -> FileLogger.e(name, msg, t)
            'W' -> FileLogger.w(name, msg, t)
            'I' -> FileLogger.i(name, msg)
            else -> Unit
        }
    }

    // ---- TRACE / DEBUG：不转发（降低噪声） ----
    override fun isTraceEnabled(): Boolean = false
    override fun trace(msg: String?) = Unit
    override fun trace(format: String?, arg: Any?) = Unit
    override fun trace(format: String?, arg1: Any?, arg2: Any?) = Unit
    override fun trace(format: String?, vararg arguments: Any?) = Unit
    override fun trace(msg: String?, t: Throwable?) = Unit

    override fun isDebugEnabled(): Boolean = false
    override fun debug(msg: String?) = Unit
    override fun debug(format: String?, arg: Any?) = Unit
    override fun debug(format: String?, arg1: Any?, arg2: Any?) = Unit
    override fun debug(format: String?, vararg arguments: Any?) = Unit
    override fun debug(msg: String?, t: Throwable?) = Unit

    // ---- INFO / WARN / ERROR：转发 ----
    override fun isInfoEnabled(): Boolean = true
    override fun info(msg: String?) = log('I', msg.orEmpty())
    override fun info(format: String?, arg: Any?) = log('I', fmt(format, arrayOf(arg)))
    override fun info(format: String?, arg1: Any?, arg2: Any?) = log('I', fmt(format, arrayOf(arg1, arg2)))
    override fun info(format: String?, vararg arguments: Any?) = log('I', fmt(format, arguments))
    override fun info(msg: String?, t: Throwable?) = log('I', msg.orEmpty(), t)

    override fun isWarnEnabled(): Boolean = true
    override fun warn(msg: String?) = log('W', msg.orEmpty())
    override fun warn(format: String?, arg: Any?) = log('W', fmt(format, arrayOf(arg)))
    override fun warn(format: String?, arg1: Any?, arg2: Any?) = log('W', fmt(format, arrayOf(arg1, arg2)))
    override fun warn(format: String?, vararg arguments: Any?) = log('W', fmt(format, arguments))
    override fun warn(msg: String?, t: Throwable?) = log('W', msg.orEmpty(), t)

    override fun isErrorEnabled(): Boolean = true
    override fun error(msg: String?) = log('E', msg.orEmpty())
    override fun error(format: String?, arg: Any?) = log('E', fmt(format, arrayOf(arg)))
    override fun error(format: String?, arg1: Any?, arg2: Any?) = log('E', fmt(format, arrayOf(arg1, arg2)))
    override fun error(format: String?, vararg arguments: Any?) = log('E', fmt(format, arguments))
    override fun error(msg: String?, t: Throwable?) = log('E', msg.orEmpty(), t)

    private fun fmt(format: String?, args: Array<out Any?>): String {
        if (format == null) return ""
        val sb = StringBuilder(format.length)
        var i = 0
        var cursor = 0
        while (true) {
            val at = format.indexOf("{}", cursor)
            if (at < 0) break
            sb.append(format, cursor, at)
            sb.append(if (i < args.size) args[i++].toString() else "{}")
            cursor = at + 2
        }
        sb.append(format, cursor, format.length)
        return sb.toString()
    }

    // Marker 变体由 MarkerIgnoringBase 委托到上面的非 Marker 方法，无需实现。
}