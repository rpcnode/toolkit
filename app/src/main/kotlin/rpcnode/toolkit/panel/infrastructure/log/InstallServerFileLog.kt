package rpcnode.toolkit.panel.infrastructure.log

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

/** Logback treats `\` as escape in rolling patterns — always forward slashes (Windows-safe). */
fun logbackPath(file: Path): String =
    file.toAbsolutePath().normalize().toString().replace('\\', '/')

/** File log next to STDOUT. Call before Toolkit.production() and the first application logger. */
fun installServerFileLog(file: Path)
{
    val absolute = file.toAbsolutePath().normalize()
    val parent = absolute.parent
    if (parent != null)
    {
        Files.createDirectories(parent)
    }
    val path = logbackPath(absolute)
    val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
    val encoder = PatternLayoutEncoder()
    encoder.context = ctx
    encoder.pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
    encoder.start()

    val appender = RollingFileAppender<ILoggingEvent>()
    appender.context = ctx
    appender.file = path
    appender.encoder = encoder

    val policy = SizeAndTimeBasedRollingPolicy<ILoggingEvent>()
    policy.context = ctx
    policy.setParent(appender)
    policy.setFileNamePattern("$path.%d{yyyy-MM-dd}.%i.gz")
    policy.setMaxFileSize(FileSize.valueOf("10MB"))
    policy.setMaxHistory(14)
    policy.setTotalSizeCap(FileSize.valueOf("200MB"))
    appender.rollingPolicy = policy
    policy.start()
    appender.start()

    val root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    root.addAppender(appender)
}

/** IDEA / `RPCNODE_DEV`: our packages at DEBUG. HTTP access stays INFO. Netty stays WARN. */
fun applyDevLogLevels(enabled: Boolean)
{
    val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
    (ctx.getLogger("rpcnode.http") as Logger).level = Level.INFO
    if (!enabled)
    {
        return
    }
    (ctx.getLogger("rpcnode.toolkit") as Logger).level = Level.DEBUG
    (ctx.getLogger("rpcnode-server") as Logger).level = Level.DEBUG
}
