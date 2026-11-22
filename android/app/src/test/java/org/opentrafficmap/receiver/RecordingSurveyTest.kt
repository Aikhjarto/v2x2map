package org.opentrafficmap.receiver

import org.junit.Test
import java.io.File

/**
 * Local diagnostics for real recordings (they contain real positions — never commit them):
 *     RECORDING_PCAP=/path/a.pcap:/path/b.pcap ./gradlew testDebugUnitTest --tests '*RecordingSurvey*' -i
 * Prints per recording the message types, BTP ports and EtherTypes of unclassified frames, and
 * frames with impossible positions. Without the variable the test does nothing.
 */
class RecordingSurveyTest {

    @Test fun survey() {
        val paths = System.getenv("RECORDING_PCAP")?.split(':')?.filter { it.isNotBlank() } ?: return
        for (path in paths) {
            val frames = File(path).inputStream().use { PcapReader.read(it) }
            println("== ${File(path).name}: ${frames.size} frames")
            println("   types: " + frames.groupingBy { it.msgType }.eachCount())
            val unknown = frames.filter { it.msgType == ItsG5Decoder.MsgType.UNKNOWN }
            println("   unknown by EtherType: " + unknown.groupingBy { it.etherType?.let { e -> "0x%04x".format(e) } ?: "none" }.eachCount())
            println("   unknown by BTP port: " + unknown.groupingBy { ItsG5Decoder.decodeFull(it.payload).btpDstPort }.eachCount())
            unknown.take(5).forEach { println("   unknown len=${it.len} secured=${it.secured} hex=${it.hexPreview(64)}") }
            val noPos = frames.count { it.msgType != ItsG5Decoder.MsgType.UNKNOWN && it.latLon == null }
            println("   classified without position: $noPos")

            // replay time line and what the live picture would hold at the end of a replay
            val base = File(path).let { File(it.parentFile, it.name.removeSuffix(".pcap")) }
            val csv = File("$base.csv").takeIf { it.isFile }?.useLines { ReplayTimeline.parsePlayStoreCsv(it) }
            val track = File("$base-track.csv").takeIf { it.isFile }?.useLines { ReplayTimeline.parseTrack(it) } ?: csv?.track.orEmpty()
            val ev = ReplayTimeline.build(frames, csv?.frameTimes, track)
            if (ev.isEmpty()) continue
            println("   replay: ${ev.size} events over ${(ev.last().atMs - ev.first().atMs) / 1000} s, " +
                "${ev.count { it.recordedAtMs != null }} frames with wall time, ${track.size} own fixes")
            val decoder = org.opentrafficmap.receiver.asn1.ItsFrameDecoder { set -> File("src/main/assets/its_asn1/$set.json").inputStream() }
            V2xHub.messageDecoder = { payload -> decoder.decode(payload).lastOrNull()?.value }
            V2xHub.clear()
            try {
                val t0 = ev.first().atMs; val real0 = System.currentTimeMillis()
                for (e in ev) { val f = e.frame ?: continue; V2xHub.clock = { real0 + (e.atMs - t0) }; V2xHub.ingest(f, e.recordedAtMs) }
                val snap = V2xHub.snapshot()
                println("   at the end: ${snap.vehicles.size} vehicles, ${snap.hazards.size} hazards, ${snap.signals.size} lights, " +
                    "${snap.intersections.size} intersections, ${V2xHub.speedZones().size} IVIM speed zones")
            } finally {                                      // V2xHub is global: leave it as other tests expect
                V2xHub.clock = System::currentTimeMillis; V2xHub.messageDecoder = null; V2xHub.clear()
            }
        }
    }
}
