package org.opentrafficmap.receiver.nav

/**
 * Tiny JSON reader/writer. Kept dependency-free so the routing code compiles and
 * tests on a plain JVM as well as on Android. Objects → Map<String, Any?>,
 * arrays → List<Any?>, numbers → Double, plus String / Boolean / null.
 */
object MiniJson {

    fun parse(s: String): Any? = Parser(s).run { val v = value(); ws(); v }

    fun stringify(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> {
                sb.append('"')
                for (c in v) when (c) {
                    '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
                sb.append('"')
            }
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(','); first = false
                    write(sb, k.toString()); sb.append(':'); write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) { if (!first) sb.append(','); first = false; write(sb, e) }
                sb.append(']')
            }
            else -> write(sb, v.toString())
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) error("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) num() else error("bad char '$c' at $i")
            }
        }
        fun expect(w: String) { if (!s.startsWith(w, i)) error("expected $w at $i"); i += w.length }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                if (s[i] != ':') error("expected : at $i"); i++
                m[k] = value(); ws()
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> error("expected , or } at $i") }
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> error("expected , or ] at $i") }
            }
        }
        fun str(): String {
            if (s[i] != '"') error("expected string at $i")
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
    }
}

// Small typed accessors for the parsed tree.
@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(key: String): Map<String, Any?>? = (this as? Map<String, Any?>)?.get(key) as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
internal fun Any?.arr(key: String): List<Any?>? = (this as? Map<String, Any?>)?.get(key) as? List<Any?>
internal fun Any?.str(key: String): String? = (this as? Map<*, *>)?.get(key) as? String
internal fun Any?.num(key: String): Double? = ((this as? Map<*, *>)?.get(key) as? Number)?.toDouble()
