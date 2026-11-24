package org.opentrafficmap.receiver

import org.junit.Test
class BW { val bits = StringBuilder(); fun w(v: Long, n: Int) { for (i in n-1 downTo 0) bits.append(if ((v shr i) and 1L == 1L) '1' else '0') }
  fun bytes(): ByteArray { while (bits.length % 8 != 0) bits.append('0'); return ByteArray(bits.length/8) { i -> bits.substring(i*8, i*8+8).toInt(2).toByte() } } }
fun denm(term: Boolean, lat: Double, lon: Double, cause: Int): ByteArray {
  val b = BW()
  b.w(0,1); b.w(1,1); b.w(0,1); b.w(0,1)            // ext, situation, location, alacarte
  b.w(if (term) 1 else 0,1); b.w(0,1); b.w(0,1); b.w(1,1); b.w(0,1) // term, relDist, relDir, validity, transInt
  b.w(123456789,32); b.w(42,16)                      // actionID
  b.w(1,42); b.w(1,42)                               // times
  if (term) b.w(0,1)
  b.w(Math.round(lat*1e7)+900000000L,31); b.w(Math.round(lon*1e7)+1800000000L,32); b.w(0,36+20+4)
  b.w(300,17)                                        // validity 300 s
  b.w(5,8)                                           // stationType
  b.w(0,1); b.w(0,1); b.w(1,1)                      // situation: ext, linkedCause, eventHistory (present)
  b.w(6,3)                                           // informationQuality
  b.w(0,1); b.w(cause.toLong(),8); b.w(0,8)          // CauseCode: ext, cause, subCause
  return byteArrayOf(0x07,(0xD2).toByte(),0,0, 2, 1, 0,0,0,1) + b.bytes()
}
class V2xHubTest {
@Test fun denmEventPositionAndCancellation() {
  val ev = DenmParser.extractEvent(denm(false, 48.2027, 16.3101, 2), 0)!!
  println(ev); check(Math.abs(ev.lat!! - 48.2027) < 1e-6 && ev.cause!!.causeCode == 2 && ev.validitySec == 300 && ev.sequenceNumber == 42)
  fun frame(p: ByteArray, e: DenmParser.Event?) = Frame(1,0,0,p,null,ItsG5Decoder.MsgType.DENM,99L,5,48.0 to 16.0,null,null,denmEvent = e, denmCause = e?.cause)
  V2xHub.ingest(frame(byteArrayOf(), ev))
  val h = V2xHub.snapshot().hazards; println(h); check(h.size == 1 && h[0].label == "accident" && Math.abs(h[0].pos.lat-48.2027)<1e-6)
  val cancel = DenmParser.extractEvent(denm(true, 48.2027, 16.3101, 2), 0)!!
  check(cancel.terminated); V2xHub.ingest(frame(byteArrayOf(), cancel))
  check(V2xHub.snapshot().hazards.isEmpty()); println("cancellation removes hazard\nHUB PASSED")
}
}
