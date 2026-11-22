package org.opentrafficmap.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.receiver.nav.LatLon

class ReplayTimelineTest {
    private fun frame(seq: Long, sec: Long, usec: Long, receivedMs: Long) =
        Frame(seq, sec, usec, ByteArray(0), null, ItsG5Decoder.MsgType.CAM, 1L, latLon = null, headingDeg = null, speedMps = null, receivedMs = receivedMs)

    @Test fun ownTrackFile() {
        val t = ReplayTimeline.parseTrack(sequenceOf(TrackRecorder.HEADER, "1000,48.2000000,14.3000000,13.90,87.5,4.0", "2000,48.2000100,14.3001000,,,", "garbage"))
        assertEquals(2, t.size)
        assertEquals(TrackPoint(1000, LatLon(48.2, 14.3), 13.9, 87.5, 4.0), t[0])
        assertNull(t[1].speedMps); assertNull(t[1].bearingDeg)
    }

    @Test fun playStoreCsvGivesFrameTimesAndADeduplicatedTrack() {
        val csv = ReplayTimeline.parsePlayStoreCsv(sequenceOf(
            "time_iso,ts_sec,ts_usec,msg_type,station_id,station_type,lat,lon,heading_deg,speed_kmh,secured,denm_cause,own_lat,own_lon",
            "2026-01-01T10:00:00.000Z,1234567890,999,UNKNOWN,,,,,,,,,48.2000000,14.3000000",
            "2026-01-01T10:00:00.500Z,144,1,CAM,7,5,48.3,14.4,0,0,true,,48.2000000,14.3000000",
            "2026-01-01T10:00:01.000Z,145,1,CAM,7,5,48.3,14.4,0,0,true,,48.2000000,14.3001000"))!!
        assertEquals(3, csv.frameTimes.size)
        assertEquals(1_767_261_600_500L, csv.frameTimes[1])
        assertEquals(2, csv.track.size)                                         // same position twice → once
        assertEquals(90.0, csv.track[0].bearingDeg!!, 1.0)                        // moving east
        assertEquals(7.4, csv.track[0].speedMps!!, 0.2)                           // 7.4 m in 1 s
    }

    @Test fun wallTimesWinAndTrackIsMerged() {
        val frames = listOf(frame(1, 5, 0, 10_000), frame(2, 6, 0, 12_000))
        val ev = ReplayTimeline.build(frames, null, listOf(TrackPoint(11_000, LatLon(0.0, 0.0), null, null, null)))
        assertEquals(listOf(10_000L, 11_000L, 12_000L), ev.map { it.atMs })
        assertEquals(10_000L, ev[0].recordedAtMs)
        assertTrue(ev[1].fix != null)
    }

    @Test fun uptimeOnlyRecordingsReplayRelativeWithoutShift() {
        val frames = listOf(frame(1, 100, 500_000, 0), frame(2, 101, 0, 0))
        val ev = ReplayTimeline.build(frames, null, listOf(TrackPoint(1, LatLon(0.0, 0.0), null, null, null)))
        assertEquals(listOf(100_500L, 101_000L), ev.map { it.atMs })           // track cannot be aligned → dropped
        assertTrue(ev.all { it.recordedAtMs == null })
    }

    @Test fun fakeTimeOfTheBootTestFrameIsIgnored() {
        val now = 1_795_300_000_000L
        val frames = listOf(frame(1, 1234567890, 999, 1_234_567_890_999L), frame(2, 0, 0, now), frame(3, 0, 0, now + 1000))
        val ev = ReplayTimeline.build(frames, null, emptyList())
        assertEquals(listOf(now, now, now + 1000), ev.map { it.atMs })
        assertNull(ev.first { it.frame?.seq == 1L }.recordedAtMs)
    }

    @Test fun longPausesAreShortenedButRecordedTimesKept() {
        val frames = listOf(frame(1, 0, 0, 1_000_000), frame(2, 0, 0, 1_005_000), frame(3, 0, 0, 1_605_000))
        val ev = ReplayTimeline.build(frames, null, emptyList())
        assertEquals(listOf(1_000_000L, 1_005_000L, 1_007_000L), ev.map { it.atMs })    // 10 min → 2 s
        assertEquals(1_605_000L, ev[2].recordedAtMs)
    }

    @Test fun csvTimesFillFramesWithoutReceiveTime() {
        val frames = listOf(frame(1, 1234567890, 999, 0), frame(2, 144, 0, 0))
        val ev = ReplayTimeline.build(frames, listOf(null, 50_000L), emptyList())
        assertEquals(listOf(50_000L, 50_000L), ev.map { it.atMs })             // test frame without time → next known
        assertEquals(50_000L, ev[1].recordedAtMs)
    }
}
