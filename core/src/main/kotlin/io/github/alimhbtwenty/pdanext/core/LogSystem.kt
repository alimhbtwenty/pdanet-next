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
    val sequence: Long = 0L,
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
    /** Changes every time the relay starts, so the PC can tell a restarted phone from a reconnect. */
    val bootId: String = java.lang.Long.toHexString(System.currentTimeMillis()) + "-" +
        java.lang.Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() ushr 16)
    private val sequenceCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val entries = ArrayDeque<LogEntry>(capacity)
    private val listeners = CopyOnWriteArrayList<(LogEntry, String) -> Unit>()

    @Synchronized
    fun log(level: LogLevel, source: LogSource, message: String): LogEntry {
        val redacted = SecretRedactor.redact(message)
        val seq = sequenceCounter.incrementAndGet()
        val entry = LogEntry(System.currentTimeMillis(), level, source, redacted, seq)
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
    fun dump(since: Long? = null): List<LogEntry> {
        return if (since == null) {
            entries.toList()
        } else {
            entries.filter { it.sequence > since }
        }
    }

    @Synchronized
    fun dumpText(since: Long? = null): String {
        val sb = StringBuilder()
        val list = if (since == null) entries else entries.filter { it.sequence > since }
        for (e in list) {
            sb.append(e.format()).append("\n")
        }
        return sb.toString()
    }

    @Synchronized
    fun lastSequence(): Long = sequenceCounter.get()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}

class FailureFileLogger(
    val dir: File,
    val baseName: String = "failures.log",
    val maxBytes: Long = 5 * 1024 * 1024L,
) {
    private val lock = Object()

    fun append(entry: LogEntry) {
        if (entry.level >= LogLevel.WARN) {
            append(entry.format())
        }
    }

    fun append(line: String) {
        synchronized(lock) {
            try {
                if (!dir.exists()) dir.mkdirs()
                val targetFile = getActiveFile(line.toByteArray(Charsets.UTF_8).size + 1L)
                FileWriter(targetFile, true).use { writer ->
                    writer.write(line)
                    writer.write("\n")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun getActiveFile(incomingBytes: Long): File {
        val baseFile = File(dir, baseName)
        if (!baseFile.exists()) return baseFile

        var part = 2
        while (File(dir, partFileName(part)).exists()) {
            part++
        }

        val latestFile = if (part == 2) baseFile else File(dir, partFileName(part - 1))
        if (latestFile.length() + incomingBytes <= maxBytes) {
            return latestFile
        }

        return File(dir, partFileName(part))
    }

    private fun partFileName(part: Int): String {
        val nameWithoutExt = baseName.removeSuffix(".log")
        return "$nameWithoutExt-$part.log"
    }

    fun listLogFiles(): List<File> {
        val files = mutableListOf<File>()
        val base = File(dir, baseName)
        if (base.exists()) files.add(base)
        var part = 2
        while (true) {
            val f = File(dir, partFileName(part))
            if (f.exists()) {
                files.add(f)
                part++
            } else {
                break
            }
        }
        return files
    }

    fun readAllText(): String {
        synchronized(lock) {
            val sb = StringBuilder()
            for (f in listLogFiles()) {
                try {
                    sb.append(f.readText(Charsets.UTF_8))
                } catch (_: Exception) {
                }
            }
            return sb.toString()
        }
    }
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
