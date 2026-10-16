package io.github.alimhbtwenty.pdanext.core.xray

/**
 * Minimal parser for JSON objects and string arrays used by RelayServer endpoints.
 * Returns null on malformed syntax so callers can reject with HTTP 400 Bad Request.
 */
object JsonHelper {

    fun parseObject(json: String): Map<String, String?>? {
        val s = json.trim()
        if (!s.startsWith("{") || !s.endsWith("}")) return null
        val content = s.substring(1, s.length - 1).trim()
        if (content.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, String?>()
        var i = 0
        while (i < content.length) {
            i = skipWhitespace(content, i)
            if (i >= content.length) break
            if (content[i] != '"') return null // key must be string
            val keyEnd = findStringEnd(content, i) ?: return null
            val key = unescapeJsonString(content.substring(i + 1, keyEnd)) ?: return null
            i = skipWhitespace(content, keyEnd + 1)
            if (i >= content.length || content[i] != ':') return null
            i = skipWhitespace(content, i + 1)
            if (i >= content.length) return null

            val value: String?
            if (content[i] == '"') {
                val valEnd = findStringEnd(content, i) ?: return null
                value = unescapeJsonString(content.substring(i + 1, valEnd)) ?: return null
                i = valEnd + 1
            } else if (content.startsWith("null", i)) {
                value = null
                i += 4
            } else if (content.startsWith("true", i)) {
                value = "true"
                i += 4
            } else if (content.startsWith("false", i)) {
                value = "false"
                i += 5
            } else {
                // Number or other primitive token
                val tokenStart = i
                while (i < content.length && content[i] != ',' && !content[i].isWhitespace()) {
                    i++
                }
                value = content.substring(tokenStart, i)
            }
            result[key] = value

            i = skipWhitespace(content, i)
            if (i < content.length) {
                if (content[i] == ',') {
                    i++
                    i = skipWhitespace(content, i)
                    if (i >= content.length) return null // trailing comma
                } else {
                    return null
                }
            }
        }
        return result
    }

    fun parseStringArray(json: String): List<String>? {
        val s = json.trim()
        if (!s.startsWith("[") || !s.endsWith("]")) return null
        val content = s.substring(1, s.length - 1).trim()
        if (content.isEmpty()) return emptyList()

        val list = mutableListOf<String>()
        var i = 0
        while (i < content.length) {
            i = skipWhitespace(content, i)
            if (i >= content.length) break
            if (content[i] != '"') return null // array elements must be strings
            val strEnd = findStringEnd(content, i) ?: return null
            val item = unescapeJsonString(content.substring(i + 1, strEnd)) ?: return null
            list.add(item)
            i = skipWhitespace(content, strEnd + 1)
            if (i < content.length) {
                if (content[i] == ',') {
                    i++
                    i = skipWhitespace(content, i)
                    if (i >= content.length) return null // trailing comma
                } else {
                    return null
                }
            }
        }
        return list
    }

    private fun skipWhitespace(s: String, startIndex: Int): Int {
        var i = startIndex
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun findStringEnd(s: String, quoteIndex: Int): Int? {
        var i = quoteIndex + 1
        var escaped = false
        while (i < s.length) {
            val c = s[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                return i
            }
            i++
        }
        return null
    }

    private fun unescapeJsonString(s: String): String? {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                i++
                if (i >= s.length) return null
                when (val esc = s[i]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 >= s.length) return null
                        val hex = s.substring(i + 1, i + 5)
                        val code = hex.toIntOrNull(16) ?: return null
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> return null
                }
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
