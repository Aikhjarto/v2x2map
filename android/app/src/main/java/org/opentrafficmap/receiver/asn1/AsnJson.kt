package org.opentrafficmap.receiver.asn1

/**
 * Canonical JSON of a decoded value (also produced by tools/asn1gen/compare_pycrate.py from
 * pycrate's results, so both decoders can be compared field by field):
 * SEQUENCE -> object, CHOICE -> {"alt": value}, SEQUENCE OF -> array, BIT STRING -> "b:0101",
 * OCTET STRING / undecoded open type -> "h:<hex>", ENUMERATED -> its name.
 */
object AsnJson {
    fun toJson(v: AsnValue): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: AsnValue) {
        when (v) {
            is AsnValue.Seq -> {
                sb.append('{')
                v.fields.forEachIndexed { i, (k, x) -> if (i > 0) sb.append(','); str(sb, k); sb.append(':'); write(sb, x) }
                sb.append('}')
            }
            is AsnValue.Choice -> { sb.append('{'); str(sb, v.alt); sb.append(':'); write(sb, v.value); sb.append('}') }
            is AsnValue.SeqOf -> {
                sb.append('[')
                v.items.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(sb, x) }
                sb.append(']')
            }
            is AsnValue.Int -> sb.append(v.v.toString())
            is AsnValue.Enum -> str(sb, v.name)
            is AsnValue.Bool -> sb.append(v.v)
            is AsnValue.Null -> sb.append("null")
            is AsnValue.Bits -> str(sb, "b:" + v.bits)
            is AsnValue.Bytes -> str(sb, "h:" + hex(v.b))
            is AsnValue.Raw -> str(sb, "h:" + hex(v.b))
            is AsnValue.Str -> str(sb, v.s)
        }
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}
