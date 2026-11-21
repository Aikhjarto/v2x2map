# V2X2MAP on Android Auto — navigation with live V2X

This adds an Android Auto **navigation app** to V2X2MAP: a moving map on the car screen with
turn-by-turn guidance, plus the live ITS-G5 picture from your ESP32-C5:

| V2X message | What you see in the car |
|---|---|
| **SPATEM** (traffic lights) | Traffic-light pins on the map, and a large traffic-light card (red/amber/green + distance) for the next signal **on your route** |
| **DENM** (hazards) | Orange warning triangles at the DENM *event position*. A hazard on your route triggers a pop-up alert, a voice warning ("Caution, accident in 800 meters") and an **Avoid** button that reroutes around it |
| **CAM** (vehicles) | Live vehicles (buses/trams purple, trucks brown). Emergency vehicles (StationType 10) flash red/blue and trigger a "make way" warning |

Routing is done by the open-source Valhalla server run by FOSSGIS (the one openstreetmap.org uses).
Search uses Photon. The map uses the OpenStreetMap standard tiles, darkened at night. All three are free and
need no API key, but they **need mobile data**. If there is no network, the map falls back to tiles
you downloaded in the phone app ("Download visible map").

## 1. Build & install

```
cd android
./gradlew assembleDebug          # Windows: .\gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or open `android/` in Android Studio and press Run. The new dependency is
`androidx.car.app:app:1.4.0`.

## 2. Let Android Auto show a sideloaded app (one time)

Android Auto hides apps that weren't installed from the Play Store until you allow it:

1. On the phone, open **Android Auto settings** (Settings → Connected devices → Android Auto).
2. Scroll down and tap **Version** about 10 times until "Developer settings" is unlocked.
3. ⋮ menu → **Developer settings** → enable **Unknown sources**.
4. Reconnect to the car. **V2X2MAP** now appears in the car's app launcher, next to Google Maps.

## 3. Use it in the car

1. On the phone, open V2X2MAP and connect the ESP32-C5 **via Bluetooth**. If your car uses
   *wired* Android Auto, the phone's USB port is taken by the car, so USB-OTG to the ESP32 won't
   work at the same time. Power the ESP32 from a car USB port or a power bank instead.
2. Leave the phone app running in the background (don't swipe it away). It owns the BLE connection
   and feeds the car screen.
3. On the car screen, open V2X2MAP. The card at the top shows the live V2X status
   (`V2X live · 120/min · 14 vehicles · 2 signals · 0 hazards`).
4. **Search** → type a destination (Android Auto only allows typing while parked) or pick a recent
   one. **By voice:** with V2X2MAP open, press the microphone and say "Navigate to …". The
   Assistant hands the request to V2X2MAP (tested with Gemini on Android Auto 18). If another
   navigation app is open, the request goes there instead.
5. During guidance: **Exit** stops navigation, **Avoid** (shown when a hazard is on your route)
   reroutes around it, and the speaker icon mutes voice guidance.

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
ESP32-C5 ──BLE/USB──► MainActivity.handleFrames ──► V2xHub (process-wide live store)
                                                        │
Android Auto ◄── NavScreen (NavigationTemplate) ◄── NavController ──► Valhalla / Photon
                    │                                   │
                    └─ MapRenderer (draws on car surface: tiles, route, V2X, HUD)
```

New files:

* `V2xHub.kt`: thread-safe store of vehicles, hazards and signals, with expiry. DENM cancellations remove the hazard.
* `nav/`: pure Kotlin, no Android dependencies, unit-tested. Geometry, polyline decoding, Valhalla/Photon client, map matching and progress (`NavigationEngine`), V2X-on-route analysis (`V2xAnalyzer`), voice prompt timing (`GuidanceScheduler`), German/English text.
* `car/`: Android Auto. `NavCarAppService` / `NavSession` / `NavScreen` / `SearchScreen`, `MapRenderer` (surface drawing), `TileCache`, `CarIcons` (turn arrows), `TripBuilder` (cluster/HUD trip info), `NavForegroundService` (keeps GPS alive during guidance).

Changed files (small): `DenmParser.kt` now also extracts the event position, ActionID, validity and
cancellation flag. `ItsG5Decoder.kt`, `Frame.kt` and `FrameReader.kt` carry that through.
`MainActivity.kt` gets one line (`V2xHub.ingest(f)`). The manifest and `build.gradle.kts` are
updated too.

## Known limitations (read before trusting it)

* **Which signal phase?** The existing `SpatTemParser` reports the phase of the *first* signal
  group of the intersection. That is not necessarily your lane or direction. Matching your lane
  needs MAPEM lane geometry (not implemented yet), so treat the light card as a hint, not as truth.
  The same caution applies to the phone app's traffic-light indicator.
* **No countdown / green-light speed advice** yet: the SPATEM parser doesn't decode timing.
* **Secured (IEEE 1609.2-signed) DENMs/SPATEMs** are only decoded where the existing parser
  manages it. Many deployments sign messages, so you may see markers without cause codes.
* **Roundabout icons assume right-hand traffic** (fine for Austria/Germany).
* **Online routing only.** No offline routing; rerouting needs data coverage.
* The free FOSSGIS Valhalla and Photon servers are for light personal use. For heavier use, run
  your own Valhalla and set the `valhalla_url` preference in `CarPrefs`.
* Android Auto rules: typing only while parked. Google only lets reviewed navigation apps onto the
  Play Store, so this stays a sideloaded or personal build.
* This is a driver *aid*. V2X reception is partial and unverified; always follow the real signals.
