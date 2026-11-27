# Possible future work

Ideas that were looked into but not started. Each entry says what it would bring and what it costs.

## Better own position with RTK corrections (RTK2go / NTRIP)

**Why:** the phone's GNSS position is typically off by 2–5 m. Lanes are about 3 m wide, so V2X2MAP
cannot reliably tell from the MAPEM lane geometry which lane the car is in, and therefore which
signal group of a traffic light applies. Lane-level position would make the traffic-light display
and a future green-light speed advice trustworthy.

**Corrections:** [RTK2go](http://rtk2go.com) is a free community NTRIP caster with many base
stations (RTCM 3). RTK works best within about 10 km of the base. Its sourcetable
(`http://rtk2go.com:2101/`) lists every base with its position, so the app can pick the nearest
one. RTK2go requires a valid email address as the NTRIP user name; it would be a user setting,
never built in.

### Option 1: corrections into the phone's own measurements

V2X2MAP fetches the corrections over NTRIP and computes its own position from the phone's raw GNSS
measurements (`GnssMeasurementsEvent`), for example with RTKLIB (BSD-2, compatible with MIT) built
through the NDK.

- Phone: the test phone (Fairphone 5, Qualcomm) delivers raw measurements on L1 and L5, but reports
  carrier-phase (accumulated delta range) support only as "unknown".
- Realistic gain: from 2–5 m to about 0.5–1.5 m (code-based DGNSS / float solution). Centimetre
  fixes are unlikely with a phone antenna inside a moving car (multipath, unreliable carrier phase).
- Cost: high. Porting and tuning a positioning engine is the risky part. GNSS must run without duty
  cycling (full tracking), which uses more battery.

### Option 2: external RTK receiver

A dedicated receiver (for example u-blox ZED-F9P with a roof antenna, about 250–300 €) connected to
the phone by Bluetooth/USB, or to the ESP32 receiver by UART. V2X2MAP only forwards the RTCM
corrections from NTRIP to the receiver and reads its position (NMEA/UBX) back.

- Realistic gain: centimetre-level RTK fixes, i.e. lane-level position.
- Cost: moderate in the app: an NTRIP client, a parser for the receiver's position, and using it
  instead of the phone's location (car map, lane matching, recordings).

**Recommendation:** option 2 if lane-level position is the goal. For the speed limit and the
traffic-light display as they are, the phone's GNSS is good enough.
