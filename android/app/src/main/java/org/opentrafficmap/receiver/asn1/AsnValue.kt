package org.opentrafficmap.receiver.asn1

import java.math.BigInteger

/** A decoded ASN.1 value. [tn] is the ASN.1 type name ("" for anonymous types). */
sealed class AsnValue {
    abstract val tn: String

    class Seq(val fields: List<Pair<String, AsnValue>>, override val tn: String) : AsnValue() {
        operator fun get(name: String): AsnValue? = fields.firstOrNull { it.first == name }?.second
    }
    class Choice(val alt: String, val value: AsnValue, override val tn: String) : AsnValue()
    class SeqOf(val items: List<AsnValue>, override val tn: String) : AsnValue()
    class Int(val v: BigInteger, val named: String?, override val tn: String) : AsnValue()
    class Enum(val name: String, override val tn: String) : AsnValue()
    class Bool(val v: Boolean, override val tn: String) : AsnValue()
    class Null(override val tn: String) : AsnValue()
    /** [bits] as '0'/'1' characters; [set] lists the named bits that are 1. */
    class Bits(val bits: String, val set: List<String>, override val tn: String) : AsnValue()
    class Bytes(val b: ByteArray, override val tn: String) : AsnValue()
    class Str(val s: String, override val tn: String) : AsnValue()
    /** Open type / unknown extension that could not be decoded further. */
    class Raw(val b: ByteArray, override val tn: String) : AsnValue()

    /** Path lookup, e.g. value.at("cam", "camParameters", "basicContainer"). Choices are traversed by alternative name. */
    fun at(vararg path: String): AsnValue? {
        var cur: AsnValue? = this
        for (p in path) cur = when (val c = cur) {
            is Seq -> c[p]
            is Choice -> if (c.alt == p) c.value else null
            else -> null
        }
        return cur
    }

    fun asLong(): Long? = (this as? Int)?.v?.toLong()
}

class AsnDecodeException(msg: String) : Exception(msg)
