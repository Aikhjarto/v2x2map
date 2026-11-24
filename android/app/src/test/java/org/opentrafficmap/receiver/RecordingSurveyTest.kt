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
                if (System.getenv("RECORDING_DETAIL") != null) detail(ev)
                println("   at the end: ${snap.vehicles.size} vehicles, ${snap.hazards.size} hazards, ${snap.signals.size} lights, " +
                    "${snap.intersections.size} intersections, ${V2xHub.speedZones().size} IVIM speed zones")
            } finally {                                      // V2xHub is global: leave it as other tests expect
                V2xHub.clock = System::currentTimeMillis; V2xHub.messageDecoder = null; V2xHub.clear()
            }
        }
    }

    /**
     * Every CAM station and DENM event with the distance to the own car at that moment, and the
     * hazard alerts the car screen would have raised (same analysis as [car.NavController] without a route).
     */
    private fun detail(ev: List<ReplayTimeline.Event>) {
        var me: TrackPoint? = null
        val cams = LinkedHashMap<Long, MutableList<String>>()
        val alerted = HashSet<String>()
        val t0 = ev.first().atMs
        fun t(ms: Long) = "%5ds".format((ms - t0) / 1000)
        V2xHub.clear()
        val real0 = System.currentTimeMillis()
        for (e in ev) {
            V2xHub.clock = { real0 + (e.atMs - t0) }
            e.fix?.let { me = it }
            val f = e.frame ?: continue
            V2xHub.ingest(f, e.recordedAtMs)
            val pos = f.latLon?.let { org.opentrafficmap.receiver.nav.LatLon(it.first, it.second) }
            val dist = pos?.let { p -> me?.let { "%.0f m".format(org.opentrafficmap.receiver.nav.Geo.distance(it.pos, p)) } } ?: "?"
            when (f.msgType) {
                ItsG5Decoder.MsgType.CAM -> cams.getOrPut(f.stationId ?: -1) { mutableListOf() } +=
                    "${t(e.atMs)} type=${f.stationType} speed=${f.speedMps?.let { "%.1f".format(it) }} dist=$dist pos=${f.latLon}"
                ItsG5Decoder.MsgType.DENM -> {
                    val d = f.denmEvent
                    val evPos = d?.lat?.let { la -> d.lon?.let { lo -> org.opentrafficmap.receiver.nav.LatLon(la, lo) } }
                    val evDist = evPos?.let { p -> me?.let { "%.0f m".format(org.opentrafficmap.receiver.nav.Geo.distance(it.pos, p)) } } ?: "?"
                    println("   DENM ${t(e.atMs)} from=${f.stationId} type=${f.stationType} action=${d?.originStationId}:${d?.sequenceNumber} " +
                        "cause=${d?.cause?.causeCode}/${d?.cause?.subCauseCode} (${d?.cause?.label()}) validity=${d?.validitySec}s " +
                        "terminated=${d?.terminated} sender=$dist event=$evDist eventPos=${evPos} senderPos=${f.latLon}")
                }
                else -> {}
            }
            val m = me ?: continue
            val a = org.opentrafficmap.receiver.nav.V2xAnalyzer.analyze(V2xHub.snapshot(), m.pos, m.bearingDeg, null)
            if (f.msgType == ItsG5Decoder.MsgType.DENM && System.getenv("RECORDING_DEBUG_DENM") != null)
                println("   dbg ${t(e.atMs)} own=${m.pos} brg=${m.bearingDeg} spd=${m.speedMps} hub=${V2xHub.snapshot().hazards.map { it.key + "@" + it.pos }} ahead=${a.hazards.map { it.hazard.key }}")
            a.hazards.firstOrNull { it.hazard.key !in alerted }?.let {
                alerted += it.hazard.key
                println("   ALERT ${t(e.atMs)} ${it.hazard.key} ${it.hazard.label} ahead ${"%.0f".format(it.distM)} m, own=${m.pos} " +
                    "speed=${m.speedMps?.let { s -> "%.0f km/h".format(s * 3.6) }} bearing=${m.bearingDeg?.let { b -> "%.0f".format(b) }}")
            }
        }
        if (System.getenv("RECORDING_DENM_TREE") != null) ev.mapNotNull { it.frame }.filter { it.msgType == ItsG5Decoder.MsgType.DENM }
            .distinctBy { it.denmEvent?.let { d -> "${d.originStationId}:${d.sequenceNumber}" } }.forEach { f ->
                V2xHub.messageDecoder?.invoke(f.payload)?.let { println(org.opentrafficmap.receiver.asn1.AsnFormat.tree(it, "DENM")) }
            }
        cams.forEach { (id, l) -> println("   CAM station $id: ${l.size} msgs"); l.take(3).forEach { println("      $it") }; if (l.size > 3) println("      … ${l.last()}") }
    }
}
