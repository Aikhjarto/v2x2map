# V2X2MAP on Android Auto — navigation with live V2X

This adds an Android Auto **navigation app** to V2X2MAP: a moving map on the car screen with
turn-by-turn guidance, plus the live ITS-G5 picture from your ESP32-C5:

| V2X message | What you see in the car |
|---|---|
| **SPATEM** (traffic lights) | Traffic-light pins on the map, and a large traffic-light card (red/amber/green + distance) for the next signal ahead. If the light sends timing, the card counts down the seconds to the next change |
| **MAPEM** (intersection lanes) | The lanes of the intersection, each ingress lane in the colour of its signal; the map zooms in on an intersection ahead whose lanes are known |
| **DENM** (hazards) | Orange warning triangles at the DENM *event position*. A hazard ahead triggers a pop-up alert, a voice warning ("Caution, accident in 800 meters") and, on a route, an **Avoid** button that reroutes around it |
| **CAM** (vehicles) | Other vehicles as arrows in their driving direction (buses/trams purple, trucks brown). Emergency vehicles (StationType 10) flash red/blue and trigger a "make way" warning |
| **IVIM** (road signs) | Speed limits from V2X road signs, shown with a small "V2X" label; they take precedence over OpenStreetMap |

Routing is done by the open-source Valhalla server run by FOSSGIS (the one openstreetmap.org uses).
Search uses Photon. The map uses the OpenStreetMap standard tiles, darkened at night. All three are free and
need no API key, but they **need mobile data**. If there is no network, the map falls back to tiles
you downloaded in the phone app ("Download visible map").

## 1. Build

```
cd android
./gradlew assembleDebug          # Windows: .\gradlew.bat assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```
Or open `android/` in Android Studio and build there. GitHub Actions also builds the APK on every
push (artifact `V2X2MAP-android-auto-debug`).

## 2. Install it so that it appears in Android Auto

**A normal install (`adb install`, Android Studio "Run", opening the APK in a file manager) is not
enough.** Android Auto only shows apps built with the Car App Library — like V2X2MAP — if Android
recorded *Google Play* as their source. The developer switch "Unknown sources" in Android Auto no
longer helps for navigation apps (seen with Android Auto 18.0, November 2026: the log says
`CAR.VALIDATOR: Package DENIED; failed all other checks`). Even `adb install -i com.android.vending`
is not enough, because the *initiating* package stays the shell.

What works is installing through [UnknownAuto](https://github.com/GitMag/UnknownAuto) (MIT, free)
with [Shizuku](https://shizuku.rikka.app/), which runs Android's own installer so that Google Play
is recorded as source:

1. **Android Auto developer settings (one time):** Settings → Connected devices → Connection
   preferences → **Android Auto** (or search Settings for "Android Auto"). Scroll down, tap
   **Version** about 10 times, confirm. Then ⋮ → **Developer settings** → enable **Unknown sources**.
2. **Install Shizuku** from the Play Store and open it once.
3. **Start Shizuku.** Either on the phone via *Wireless debugging* (the phone must be connected to a
   Wi-Fi network — its own hotspot does not count), or from a computer over USB:
   ```
   adb shell "$(adb shell pm path moe.shizuku.privileged.api | sed 's/package://; s#/base.apk##' | tr -d '\r')/lib/arm64/libshizuku.so"
   ```
   Shizuku must be started again after every phone restart.
4. **Install UnknownAuto** from its GitHub releases page and allow it Shizuku access.
5. **Copy the V2X2MAP APK to the phone**, e.g. `adb push app-debug.apk /sdcard/Download/`.
6. In UnknownAuto: **Load APK or XAPK…** → pick the APK → **Install for Android Auto**. If Play
   Protect asks, let it scan or choose *Install without scanning*.
7. Check Android Auto settings → **Customize launcher**: V2X2MAP must be listed and ticked. Then
   connect to the car.

**Every update must be installed the same way.** A plain `adb install -r` of a newer build records
the shell as source again and V2X2MAP disappears from the car screen; reinstalling via UnknownAuto
brings it back (settings and recent destinations are kept).

Notes:
* The same works for other sideloaded Car App Library apps (e.g. OsmAnd from F-Droid). Apps that
  also exist in the Play Store under the same package name may then be offered Play updates that
  fail because the signature differs.
* Google regularly changes Android Auto; this workaround may break at any time. The clean long-term
  route is distributing V2X2MAP through a Google Play (internal) test track.

## 3. Use it in the car

1. Power the ESP32-C5 in the car (car USB port or power bank). If your car uses *wired* Android
   Auto, the phone's USB port is taken by the car, so the ESP32 is connected **via Bluetooth**.
2. On the car screen, open V2X2MAP. It connects to the ESP32 by itself (also with the phone locked
   in a pocket); the phone app does not need to be opened. ⚙ → **V2X receiver (ESP32)** switches
   the connection off and on.
3. **Status chips** (bottom left): `V2X ● 3🚗 2🚦 0⚠ · 120/min` while V2X arrives (otherwise the
   link state), `OBD ●` while AndrOBD delivers car data, and an orange **REC off** while frame
   recording is switched off on the phone.
4. **Search** → type a destination (Android Auto only allows typing while parked) or pick a recent
   one. **By voice:** with V2X2MAP open, press the microphone and say "Navigate to …". The
   Assistant hands the request to V2X2MAP (tested with Gemini on Android Auto 18). If another
   navigation app is open, the request goes there instead.
5. During guidance: **Exit** stops navigation, **Avoid** (shown when a hazard is on your route)
   reroutes around it, and the speaker icon mutes voice guidance.
6. **Map:** it zooms by speed (always with a route, without one if *Auto-zoom by speed* is on) and
   closer near a traffic light whose lanes are known. Zooming or panning by hand fixes the scale and
   stops following; the **recenter** button brings both back.
7. **Speed limit:** a round sign (bottom left) shows the limit at your position: from V2X road signs
   (IVIM, also without a route, labelled "V2X"), otherwise during guidance from OpenStreetMap via
   Valhalla. More than 5 km/h over it (5 % above 100 km/h), your current speed is shown next to the
   sign; more than 10 % over, the sign also flashes red.
8. **Options (⚙):** V2X receiver, **Start AndrOBD** (opens AndrOBD on the phone, only while
   parked; needs "Display over other apps" for V2X2MAP, otherwise it posts a notification to tap),
   snap to road, north up, show vehicles, auto-zoom.

**Recordings** (on the phone in `Android/data/org.opentrafficmap.receiver/files/`):
* `itsg5-<time>.pcap` with `-track.csv` (own GPS): while *recording* is switched on in the phone
  app. A file starts with the first V2X frame and is closed when the drive is over (10 minutes
  without V2X, car screen and movement).
* `androbd-<time>.csv`: everything AndrOBD sends to the V2X2MAP plugin.
* `car-<time>.csv`: whatever the car's head unit delivers through Android Auto (car GPS, wheel
  speed, compass, gyroscope, accelerometer, odometer, fuel level and range, model), with a status per
  value, while the car screen is open. Cars differ in what they send; the file shows which values
  this car delivers.

### Set the destination from the phone

In Google Maps, OsmAnd, Contacts, a chat or a web page, tap **Share** on a place or address and pick
**Navigate with V2X2MAP** ("Mit V2X2MAP navigieren"). Tapping a `geo:` link also offers V2X2MAP.

* If V2X2MAP is open in Android Auto, guidance starts right away.
* Otherwise the destination is kept for 6 hours and guidance starts the next time you open
  V2X2MAP in Android Auto. It is also added to the recent destinations.
* A short message on the phone says what was found. Supported: `geo:` links, Google Maps links
  (including `maps.app.goo.gl` short links, which are expanded via Google), OpenStreetMap and
  OsmAnd links, plain `lat, lon` text, and otherwise the shared text as an address search (Photon).

## 4. Testing at your desk (no car, no V2X traffic)

Use the **Desktop Head Unit (DHU)** from Android Studio's SDK Manager
(SDK Tools → Android Auto Desktop Head Unit Emulator):

```
# phone: Android Auto developer settings → ⋮ → "Start head unit server"
adb forward tcp:5277 tcp:5277
$ANDROID_SDK/extras/google/auto/desktop-head-unit
```

Start navigation to any destination, then press **Demo** (debug builds only). The car drives along
the route by itself, and a **simulated** traffic light (cycling green/amber/red) and a simulated
roadworks DENM are placed on the route, so you can check the light card, the hazard alert, the
voice prompt and **Avoid** without real V2X traffic. Simulated objects are labelled "SIM". If the
host sends Android Auto's "auto drive" request (`NavigationManagerCallback.onAutoDriveEnabled`),
the same demo starts.

Unit tests for the routing/guidance/V2X logic: `./gradlew test`.

## How it's built

```
ESP32-C5 ──BLE/USB──► BtSession ──► FramePipeline ──► V2xHub (process-wide live store)
                                        │  └─► FrameRecorder (pcap + own track), MQTT
                                        └─► MainActivity (phone UI, if open)
Android Auto ◄── NavScreen (NavigationTemplate) ◄── NavController ──► Valhalla / Photon
                    │                                   │
                    └─ MapRenderer (draws on car surface: tiles, route, lanes, V2X, HUD, chips)
```

* `V2xHub.kt`: thread-safe store of vehicles, hazards, signals, intersections (MAPEM + SPATEM) and
  IVIM speed zones, with expiry. DENM cancellations remove the hazard.
* `asn1/`: generated ASN.1 tables and UPER/OER decoders for all ITS messages and the IEEE 1609.2
  security envelope; `SpatemParser` reads the signal states and timing.
* `nav/`: pure Kotlin, no Android dependencies, unit-tested. Geometry, polyline decoding,
  Valhalla/Photon client, map matching and progress (`NavigationEngine`), V2X-ahead analysis
  (`V2xAnalyzer`), intersections and lanes, speed zoom, speed warning, voice prompt timing, texts.
* `car/`: Android Auto. `NavCarAppService` / `NavSession` / `NavScreen` / `SearchScreen` /
  `MapOptionsScreen`, `MapRenderer` (surface drawing), `TileCache`, `CarIcons`, `TripBuilder`
  (cluster/HUD trip info), `CarDataRecorder` (car hardware data), `NavForegroundService` (keeps GPS
  alive during guidance).
* `obd/`: AndrOBD plugin (car data from an ELM327 adapter) and the launcher for AndrOBD.

## Known limitations (read before trusting it)

* **Speed limits:** V2X road signs (IVIM) only where a roadside unit sends them; otherwise
  OpenStreetMap, and that only during guidance and while on the route. OSM misses temporary limits
  (roadworks, variable motorway signs, school zones by time), and some roads have no limit tagged.
* **Which signal applies?** The light ahead is chosen by direction of travel: MAPEM lane geometry
  gives each signal group its approach direction. Phone GPS (2–5 m) cannot tell which *lane* you
  are in, so with several signal groups for one approach (left/straight/right) the light card is a
  hint, not the truth. Lane-level position is in [FUTURE_WORK.md](FUTURE_WORK.md).
* **Countdown only where the light sends timing.** Many traffic lights send only the current state
  (on the first real drives, 4 of 5 intersections sent no timing). No green-light speed advice yet.
* **Signatures are decoded, not verified.** The IEEE 1609.2 / ETSI TS 103 097 envelope (signer,
  certificate) is decoded and shown in the frame details, but the signature is not checked against
  the EU C-ITS trust list, so a forged or test message looks the same as a real one.
* **Roundabout icons assume right-hand traffic** (fine for Austria/Germany).
* **Online routing only.** No offline routing; rerouting needs data coverage.
* The free FOSSGIS Valhalla and Photon servers are for light personal use. For heavier use, run
  your own Valhalla and set the `valhalla_url` preference in `CarPrefs`.
* Android Auto rules: typing only while parked. Google only lets reviewed navigation apps onto the
  Play Store, so this stays a sideloaded or personal build.
* This is a driver *aid*. V2X reception is partial and unverified; always follow the real signals.

Ideas that were looked into but not started (e.g. lane-level position with RTK corrections) are in
[FUTURE_WORK.md](FUTURE_WORK.md).
