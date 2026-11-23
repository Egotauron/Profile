# Waypoint

A personal "better Google Maps" for **multi-stop trips**. It's built from the *Better Google Maps spec v0.7* on open-source components.
- You list the places you want to go, in your order.
- It plans one timeline through them by car, on foot, by bike, by bike share or by public transport (or **Fastest**, which picks per leg).
- It guides you stop to stop on the phone and in **Android Auto**, with a music shortcut in the navigation screen.

No Google account and no billing anywhere. The stack: MapLibre + OpenFreeMap tiles, Ferrostar turn-by-turn, Valhalla routing, Photon search, TfL in London and Transitous elsewhere for transit, GBFS and TfL BikePoint for bike share.

## What's in this folder

| Module | What it is |
|---|---|
| `trip/` | Pure Kotlin, no Android (spec §3–4). It holds the data model and the planner (one router request per run of same-mode legs, stays, parking, Arrive-by, Fastest with runner-up). It also has the vehicle rule, bike-share dock pairing and the Congestion Charge check. The rest of the module: the self-calibrating drive-time factor, the trip runtime (Auto/Manual stops, skip, re-planning triggers) and the "Open in…" links. |
| `providers/` | HTTP clients behind the `trip` interfaces: Valhalla (Stadia or the public FOSSGIS server), Photon, TfL (journeys, live arrivals, BikePoint), Transitous and GBFS. |
| `app/` | The Android app: trip builder, search, timeline, navigation, arrival cards, transit companion, bike-share cards, music chip, saved trips/places/presets, settings, and Android Auto. |
| `tools/ferrostar-check/` | A Rust check that runs the per-leg routes through Ferrostar's own parser and navigation controller. |

## Install on your phone

You need Android 10 or newer and a computer with [Android Studio](https://developer.android.com/studio).

1. Clone this repo and open the **`waypoint`** folder in Android Studio. Let it sync. It downloads Gradle and the Android SDK pieces it needs.
2. On the phone: Settings → About phone → tap *Build number* 7 times. Then Settings → System → Developer options → turn on *USB debugging*.
3. Plug the phone in, pick it in the device menu and press **Run**. The app is called *Waypoint*. The debug build installs as `app.waypoint.dev`.

From a terminal instead: `./gradlew :app:installDebug`.

Install from Android Studio or `adb`, not by opening an APK file. Android blocks notification access, which the music controls need, for apps sideloaded from a file. If that already happened: App info → ⋮ → **Allow restricted settings**.

### Keys (optional)

The app works without any keys:
- With no Stadia key, routing uses the public FOSSGIS Valhalla server.
- TfL works anonymously (50 requests a minute).

For heavier use, add free keys in **Settings → Routing**, or in `waypoint/local.properties` (never committed):

```properties
stadia.apiKey=your-stadia-key      # https://client.stadiamaps.com (free, non-commercial)
tfl.appKey=your-tfl-app-key        # https://api-portal.tfl.gov.uk
```

A Stadia key also lets the app actually drive around the Congestion Charge zone. The public server rejects polygons that large, so without a key the app shows the charge-zone warning instead.

### Android Auto

1. On the phone, open Android Auto settings and tap *Version* 10 times to unlock developer settings. Then turn on **Unknown sources**.
2. Connect to the car, or to the [Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu) on a computer.
3. Waypoint appears in the launcher. It lists the trip you planned on the phone plus your saved trips. Pick one → route preview → **Start**.

If a trip is already running when the car connects, the car opens straight on it.

## Using it

- **Build a trip:** add up to 10 stops. Set *How (all legs)* to one mode or to **Fastest** with mode chips (Walk · Bus · Train · Tube · Tram · Cycling · Bike share · Drive).
  - Each stop can override the mode used to get to it (*To here*).
  - Each stop is **Auto** (the next leg starts after the stay) or **Manual** (waits for *Start next leg*).
  - Drives can go to a car park (*Navigate to car park*). Mark *Park here* / *Lock bike here* to leave the vehicle behind.
- The plan refreshes about a second after you stop editing. You get one ETA, warnings, and for Fastest legs the runner-up with a one-tap *Use* button.
- **Start:** turn-by-turn by voice. Music ducks while it speaks.
  - The top bar shows *Stop 2 of 5*, the music chip (tap to open your player; long-press to choose a default one) and ⋯ (*Open in Google Maps / Organic Maps / OsmAnd*, mute, *End trip*).
  - At each stop you get an Auto countdown (*Start now · Pause*) or a Manual card (*Start next leg · Skip next stop*).
- **Transit legs:** you walk to the stop with guidance. The app shows TfL live arrivals while you wait, alerts *Get off at the next stop*, and re-plans if you miss the connection.
- **Bike share:** *Unlock a bike* → ride → *Dock the bike* (it re-checks the dock about 2 minutes before you arrive) → walk.
- **Try it at home:** Settings → Testing → *Simulate location*. The app then follows each route without moving.

## How it fits together

- **Preview = guidance:**
  1. A run of consecutive drive/walk/cycle legs goes to Valhalla as one request, with each stop as a `break`.
  2. `OsrmSplitter` cuts the response into one route per leg (that leg's steps, annotations and line).
  3. At each leg, that exact route goes to Ferrostar via `Route.fromOsrm`.
  4. Ferrostar asks Valhalla again only when you go off route, using the same costing and options.
- **Live traffic:** none. Drive times are Valhalla × a factor per area (central London / rest of London / elsewhere) × time (weekday peaks / other).
  - Starting factors: ×1.4 / ×1.2 / ×1.0.
  - Every finished drive nudges its factor (exponentially weighted).
  - You can see the factors and reset them in Settings.
- **The trip runtime (`TripRuntime`) is plain Kotlin.** `TripSession` (with the `TripSessionService` foreground service) carries out what it decides. Phone and car screens are both views of that one session, so the trip carries on when Android Auto connects or disconnects.

## Tests

```bash
./gradlew :trip:test :providers:test           # unit tests, using recorded real responses
./gradlew :providers:test -Pwaypoint.live=true # also call the real public services
./gradlew :app:lintDebug :app:assembleDebug
# Ferrostar check of the per-leg routes (needs Rust):
./gradlew :trip:test && (cd tools/ferrostar-check && cargo run --release -- ../../trip/build/split-legs)
```

## Not built yet / known gaps

- **Not tried on a real phone or car yet.** Everything above builds, lints and passes its tests, but the app was written without a device or emulator.
- **Phase 4 offline mode** (on-device Valhalla + PMTiles regions) isn't built. Neither is *start a playlist on trip start*, nor iOS/CarPlay.
- **The charge-zone boundary is hand-drawn.** It runs just inside the Inner Ring Road, with Mon–Fri 07–18 and weekend 12–18 hours. Bank holidays aren't modelled; check TfL before relying on it.
- **Shop and restaurant search is weaker than Google's** (Photon). Star the places you use.
- **TfL doesn't give positions for intermediate stops.** In London, *get off next stop* fires by distance to your stop rather than by stop count.
- **The Santander Cycles button opens TfL's web page** unless one of the app package names it guesses is installed.
- **The APK is large (~80 MB per CPU type)** because code shrinking (R8) is off. It needs keep rules for Ferrostar's native bindings that haven't been tested on a device.

## Data and licences

Map data © OpenStreetMap contributors. Tiles by OpenFreeMap. Routing by Valhalla via Stadia Maps or the FOSSGIS server. Search by Photon (komoot). Powered by TfL Open Data. Transit outside London from Transitous. Navigation by Ferrostar (BSD-3).
