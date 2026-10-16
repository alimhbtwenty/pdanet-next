package io.github.alimhbtwenty.pdanext.core

import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel { INFO, WARN, ERROR }

enum class LogSource {
    ROUTER, LINK, DNS, SERVICE;

    val tag: String get() = name.lowercase()
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val source: LogSource,
    val message: String,
) {
    fun format(): String {
        val iso = Instant.ofEpochMilli(timestamp).toString()
        return "$iso [${level.name}] [${source.tag}] $message"
    }
}

object SecretRedactor {
    private val VPN_URL_REGEX = Regex("""(?i)\b(vless|vmess|trojan|ss)://[^\s'"]+""")
    private val UUID_REGEX = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val URL_CREDENTIALS_REGEX = Regex("""(?i)\b([a-zA-Z][a-zA-Z0-9+.-]*://)([^:@\s]+):([^@\s]+)@""")
    private val KEY_VALUE_REGEX = Regex("""(?i)\b(password|secret|key|token|auth|bearer)\s*[:=]\s*([^\s,;]+)""")

    fun redact(text: String): String {
        var s = VPN_URL_REGEX.replace(text, "$1://[REDACTED]")
        s = UUID_REGEX.replace(s, "[REDACTED-UUID]")
        s = URL_CREDENTIALS_REGEX.replace(s, "$1[REDACTED]:[REDACTED]@")
        s = KEY_VALUE_REGEX.replace(s, "$1=[REDACTED]")
        return s
    }
}

class LogRingBuffer(val capacity: Int = 2000) {
    private val entries = ArrayDeque<LogEntry>(capacity)
    private val listeners = CopyOnWriteArrayList<(LogEntry, String) -> Unit>()

    @Synchronized
    fun log(level: LogLevel, source: LogSource, message: String): LogEntry {
        val redacted = SecretRedactor.redact(message)
        val entry = LogEntry(System.currentTimeMillis(), level, source, redacted)
        if (entries.size >= capacity) {
            entries.removeFirst()
        }
        entries.addLast(entry)
        val formatted = entry.format()
        listeners.forEach { it.invoke(entry, formatted) }
        return entry
    }

    fun addListener(listener: (LogEntry, String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (LogEntry, String) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun dump(): List<LogEntry> = entries.toList()

    @Synchronized
    fun dumpText(): String {
        val sb = StringBuilder()
        for (e in entries) {
            sb.append(e.format()).append("\n")
        }
        return sb.toString()
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}

class RotatingFileLogger(
    val dir: File,
    val baseName: String = "pdanext.log",
    val maxBytes: Long = 1024 * 1024,
    val maxFiles: Int = 2,
) {
    private val lock = Object()
    val mainFile: File get() = File(dir, baseName)

    fun append(line: String) {
        synchronized(lock) {
            try {
                if (!dir.exists()) dir.mkdirs()
                rotateIfNeeded(line.toByteArray(Charsets.UTF_8).size + 1L)
                FileWriter(mainFile, true).use { writer ->
                    writer.write(line)
                    writer.write("\n")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun rotateIfNeeded(incomingBytes: Long) {
        if (!mainFile.exists() || mainFile.length() + incomingBytes <= maxBytes) return
        for (i in (maxFiles - 1) downTo 1) {
            val src = if (i == 1) mainFile else File(dir, "$baseName.${i - 1}")
            val dest = File(dir, "$baseName.$i")
            if (dest.exists()) dest.delete()
            if (src.exists()) src.renameTo(dest)
        }
    }

    fun listLogFiles(): List<File> {
        val files = mutableListOf<File>()
        if (mainFile.exists()) files.add(mainFile)
        for (i in 1 until maxFiles) {
            val f = File(dir, "$baseName.$i")
            if (f.exists()) files.add(f)
        }
        return files
    }
}
