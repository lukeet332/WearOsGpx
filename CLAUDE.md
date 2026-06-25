# WearOsGpx

Standalone, sports-watch-style GPX navigation + run-tracking app for **Wear OS 4+**, optimized for the
**OnePlus Watch 2R** dual-chip architecture. Records a run fully standalone (no phone), then syncs to
a phone companion that writes it into Health Connect (for Strava / OHealth).

The developer is an experienced **React Native** engineer who is new to native Android — explain native
structures explicitly (services, manifests, Gradle, Compose), don't assume Android idioms are known.

## Hardware target: OnePlus Watch 2R

- Dual-engine: **Snapdragon W5** (Wear OS application processor) + **BES2700** low-power MCU.
- **Only one usable physical button** — the **bottom** one. The top button is the system/home button
  and cannot be captured by third-party apps. Design everything else around **touch + swipe**.
- Round display. **No rotating crown assumption** — don't rely on rotary input.

## Modules

- **`:wear`** (minSdk 33) — the watch app. WHS `ExerciseClient` foreground service → Room → Compose UI.
- **`:mobile`** (minSdk 26) — headless companion. `WearableListenerService` → Health Connect
  `ExerciseSessionRecord` + `ExerciseRoute`. (Phase 4, not built yet.)
- **`applicationId` must be identical (`com.wearosgpx`) in both modules** and they must be signed with the
  same key, or the Data Layer won't pair. Namespaces differ (`com.wearosgpx` / `com.wearosgpx.mobile`) —
  that's intentional, don't "fix" it.

## Build / verify

- No standalone Gradle installed — always use `./gradlew`. Android SDK at `~/Library/Android/sdk`.
- **Verify every change by compiling before handing off:** `./gradlew :wear:assembleDebug`.
- `compileSdk`/`targetSdk` = **35** (androidx.core 1.15 requires it). Kotlin 2.x uses the `kotlin-compose`
  Gradle plugin, not `composeOptions`. Dependencies are pinned in `gradle/libs.versions.toml`.
- **Shared signing** (required so the Data Layer pairs): both modules load `keystore.properties`
  (gitignored) and sign `release` with `keystore/wearosgpx-release.jks` (dev creds `wearosgpx`). Verified
  both release APKs share cert SHA-256 `9715…4648`. Debug pairs via the shared debug keystore. Both
  modules `lint { disable += "InvalidFragmentVersionForActivityResult" }` (false positive from
  `registerForActivityResult` in a no-Fragment Compose app; it was failing `lintVitalRelease`).
- targetSdk 35 = **edge-to-edge by default** (Android 15) — phone Compose UI uses `systemBarsPadding()`
  so content clears the status/nav bars.
- Watch route list is **live**: `RouteUpdates` (in-process StateFlow) ticks on import/delete (bumped by
  `RouteImportListenerService`); the list `produceState` keys on it. Phone "Send GPX" picker filters to
  GPX-ish MIME types and validates the `.gpx` extension after pick.
- **Route creator** (phone): `RouteCreatorActivity` uses **osmdroid** (OpenStreetMap, no API key; needs
  INTERNET). Tap to plot waypoints → live polyline + distance → `GpxBuilder` makes a GPX → `PhoneRouteStore`
  saves a local copy (in `getExternalFilesDir("routes")`) → `WatchRoutes.sendRoute` pushes to the watch.
  **Share/download** any route with a local copy via the share sheet (`FileProvider` authority
  `com.wearosgpx.fileprovider`, `res/xml/file_paths.xml`) — from the creator and the route detail dialog.
  **Elevation** is fetched per plotted point from the Open-Meteo Elevation API (free, no key) on Save and
  baked into the GPX (`ElevationService`) — so created routes get real ascent + profile. **Location
  search** at the top geocodes via OSM Nominatim (`GeocodingService`, no key, needs User-Agent) and
  recenters the map. Map has pinch + on-map +/- zoom buttons (`CustomZoomButtonsController`). The creator
  is a full-screen modal styled dark/neon with an **X to close** at the top; **Share is only on the route
  list's detail dialog**, not in the creator. Phone keeps local copies of routes it creates/sends so
  they're shareable; watch-only routes (imported elsewhere) can't be shared (no bytes).
- **Phone route list is local-first**: `MainActivity.buildRows()` merges phone-local GPX (parsed by
  `GpxMeta`, the bytes the phone holds) with the watch index, de-duped by file name, each tagged
  `onPhone`/`onWatch`. So a created/imported route shows immediately as "On phone · syncing…" even with no
  watch; `refreshRoutes()` auto-pushes pending routes to the watch on every open (DataClient also persists/
  delivers once queued). Detail dialog actions: Share (if onPhone), Send (if pending), Delete (both ends).
- The **route creator is a View activity** (NoActionBar theme) drawing edge-to-edge — its top/bottom bars
  get system-bar insets via `ViewCompat.setOnApplyWindowInsetsListener`. Flow is **two steps**: name the
  route (step 1), then the map (step 2). Map step header = route name + ✕ (inline). Search has **type-ahead
  suggestions** (debounced `GeocodingService.searchMany`, Nominatim). Save uses the step-1 name.
  **Road-snapping**: taps are control points (markers); `RoutingService` calls **OpenRouteService**
  (foot-walking) to snap them to roads — that road geometry is drawn and saved (with elevation), not the
  straight taps. Falls back to straight lines if routing fails. **Loop** button / tap-near-start closes a
  loop. Tiles are raster images (no road data) — routing needs the OSM graph, hence ORS. ORS key lives in
  `local.properties` as `ORS_API_KEY=...` (gitignored) → `mobile` `BuildConfig.ORS_API_KEY` (needs
  `buildFeatures { buildConfig = true }`) = the bundled default. Users can override it in **Settings**
  (`AppSettings` → SharedPreferences); `AppSettings.effectiveOrsKey()` = user key ?: BuildConfig default.
  A **spinner** shows while ORS responds (`setLoading`), not a premature straight line. An LLM is NOT used
  for routing (would hallucinate coordinates).
- Live run **stats overlay** uses fixed-width `StatCell`s (icon + value + unit) so growing values never
  shift the row; icons are `res/drawable/ic_stat_*` vectors; block lifted off the bottom so the round
  screen doesn't clip the columns.

## Testing

- **JVM unit tests** (JUnit4, no Android/Robolectric): `./gradlew :wear:testDebugUnitTest :mobile:testDebugUnitTest`.
  34 deterministic tests guard the **pure, regression-prone logic** we keep tuning.
- **Convention:** keep tests pure and deterministic — NO UI (Compose), GPX-parsing (`android.util.Xml`),
  network (Overpass/Nominatim/ORS), Data Layer, or Health Connect client tests (flaky / need a device).
  When risky logic lives inside an Activity/Service/UI, **extract it into a pure unit and test that**
  (e.g. `presentation/Formatting.kt`, `health/HealthConnectPrep.kt`; `BaseMapService.classify/simplify`
  and `RouteDiscoveryService.assemble` are `internal` for this). Add a test whenever you change a
  threshold/format.
- **New features:** add a **bang-for-buck** deterministic unit test for a new feature's pure,
  regression-prone logic (math, parsing, format, state transitions) so it lands in the **CI gate** —
  extract that logic out of the UI/Activity/Service into a pure unit if needed. Prefer **TDD where
  practical**: write the failing test for that pure unit first, then implement to green. Stay pragmatic:
  skip tests that would be **flaky or low value** (UI/Compose, network, Data Layer, device-only behaviour)
  or that only exercise trivial glue. A small, reliable test that guards real logic > a brittle one
  chasing coverage.
- **Covered:** wear — `GeoUtils` (distance/bearing), `RouteNavigator` (off-course 40/25 hysteresis,
  progress, 50° "proper bend" turn threshold), `Formatting` (pace/elapsed/distance), `RouteProjector`
  (fit/centered projection), `GpxRoute` (distance/ascent). mobile — `HealthConnectPrep` (dedupe dup
  timestamps + HR 1..300 clamp/drop — the past sync bug), `BaseMapService` (OSM classify + Douglas–Peucker),
  `RouteDiscoveryService.assemble` (way chaining/flip), `RoutingPayloads` (ORS shaping),
  `GpxBuilder` (GPX output incl. `renamed`), `AiRouteLogic` (action protocol + geometry).

## Phased plan

1. ✅ GPX parser + Room data layer
2. ✅ WHS `ExerciseClient` foreground engine
3. 🟡 Compose UI — done: route list, course preview, breadcrumb map (overview + follow), live overlay,
   finished summary, and the **live recorded track** drawn on the follow map (observed from Room via
   `observeTrackPoints`, lifecycle-gated; current marker from `state.latestLocation`). The preview screen
   shows **GPS acquisition status** via WHS `prepareExercise` warm-up (greys Start until lock but keeps it
   clickable). Navigation between warm-up and a real run is distinguished by `state.isTracking` (set in
   `startRun`, not by `exerciseState`, since PREPARING must NOT navigate to the run screen).
   **Laps/splits**: `LapEntity` table; auto-lap every 1 km (lap-relative) + manual Lap button; distinct
   triple-buzz + `ToneGenerator` beep; a `LapBanner` overlay pops over everything (incl. ambient) with
   lap #/time/pace/dist and best-effort screen-wake (`setTurnScreenOn` + `FLAG_TURN_SCREEN_ON`); splits
   listed in the finished summary. Service exposes `lapEvents: SharedFlow<LapEntity>`. DB is now **v2**
   (added `laps`) with `fallbackToDestructiveMigration()` — dev-only, wipes on schema change.
   TODO: dedicated data-fields screen, elevation profile.
4. 🟡 Phone handoff → Health Connect. **This is the priority** — the app's value is recording standalone
   then syncing; OHealth/Strava are the source of truth, so detailed in-app history is NOT a goal.
   Implemented: on finish, `:wear` serializes the run (`RunPayload`, gzipped JSON) and sends it via
   Wearable `DataClient` at path `/run/<startMs>` (persists + delivers when phone in range), then marks
   the run synced. `:mobile` `RunSyncListenerService` (WearableListenerService) receives, ungzips,
   and `HealthConnectWriter` writes `ExerciseSessionRecord` + `ExerciseRoute` + `DistanceRecord` +
   `HeartRateRecord`. `:mobile` `MainActivity` is a plain-View screen that requests Health Connect write
   permissions (required before the service can write). **OHealth reads from Health Connect; Strava is
   out of scope — OHealth feeds it.** `RunPayload` is duplicated in both modules (keep in sync).
   `Metadata()` (not the internal `Metadata.EMPTY`). HC permission gate uses `CORE_PERMISSIONS` (session/
   distance/HR) only — NOT the route permission, which HC grants via a separate flow and may not report
   in `getGrantedPermissions()` (gating on it left the Grant button stuck). The phone app is **Compose**,
   styled dark + neon to match the watch; it shows HC status + grant, "Send GPX route to watch", and a
   **"routes on watch" list** (detail dialog + delete). Watch publishes its catalog as a `/routes_index`
   DataItem (`RouteIndexPublisher`, on startup/import/delete); phone reads it (`WatchRoutes.fetchIndex`)
   and deletes via a `/route/delete` MessageClient message handled in `RouteImportListenerService`.
   `RouteIndex`/`RouteIndexEntry` duplicated in both modules. Needs on-device testing: watch+phone paired,
   same signing key, Health Connect installed, permissions granted on the phone.

**Navigation engine (`com.wearosgpx.navigation.RouteNavigator`):** snaps live position onto the planned
route to compute cross-track (off-course w/ hysteresis 40m enter / 25m exit), distance-along / remaining /
% complete, bearing-to-rejoin, distance+bearing back to start, and geometry-derived turns. The service
runs it per WHS batch and **vibrates** on off-course transitions (re-buzz every 20s) and approaching
turns (≤60m). UI: top `NavigationCue` banner (off-course red → turn amber → "km to go") + back-to-start
arrow on the controls page. The selected route is threaded UI → activity (`routePoints`) → `startRun`.
NOTE: "back to start" is currently straight-line bearing/distance, not full reverse-route guidance.

## Product decisions (follow these)

- **Interaction model:** bottom button = Start / Pause / Resume (short) and Stop (long); every button
  action also has an on-screen touch equivalent (the hardware button may not deliver KeyEvents — never
  make it the only path). Swipe between screens. App-exit is blocked during an active run.
- **Navigation flow:** RouteList → Preview (course overview + edge Start button) → Activity (map first,
  swipe right to Pause/Stop controls) → Finished summary → back to list. Transitions slide
  (forward = in from right, back = in from left).
- **Map modes are automatic, never a tap-toggle:** run ACTIVE → follow (track-up, zoomed, heading
  chevron); idle/paused/preview → overview (whole route fitted, north-up). Mirrors how dedicated running watches work.
- **Visual style:** dark background, **neon-green** route/track line with a layered glow; big-number stats
  with small units (sports-watch-style).
- **Round-screen safe area:** do NOT use a rectangular inscribed box (Horologist `fillMaxRectangle`) — it
  was tried and looked cramped. Center content in the circle's widest band with proportional padding;
  use `ScalingLazyColumn` for lists; edge buttons use the custom `BottomEdgeShape` (flat top, bottom arc
  = screen radius) so they hug the bezel flush with no gap.

## Efficiency: rely on the BES2700, keep the W5 asleep

This is a priority. Third-party apps cannot pin work to the efficiency chip directly — the **only**
sanctioned way to offload sensing to the low-power hub is **Health Services `ExerciseClient`**, which we
use. The rule: **never force the application processor (W5) awake.**

- Let WHS **batch** sensor deliveries; process each batch and go back to sleep. Don't request higher
  sample rates than needed, don't add per-second UI timers (live time updates per WHS batch, not a ticker).
- **Never re-read the whole track from Room per sample.** Cumulative distance is tracked **in memory** in
  `ExerciseService` and points are **batch-inserted** per delivery (one transaction). The old per-sample
  full-track query was removed — don't reintroduce that pattern.
- Don't hold our own `WakeLock`; the foreground exercise owns wake management.
- Phase 4: defer the phone sync to when **charging / docked**, never sync mid-run.
- Always-on/ambient is implemented via `AmbientLifecycleObserver` (needs the `<uses-library
  com.google.android.wearable>` manifest entry + `WAKE_LOCK`). In ambient the UI swaps to a low-power
  mostly-black render (`AmbientScreen`): dim thin map (no glow, `BreadcrumbCanvas(ambient = true)`),
  stats refreshed only on `onUpdateAmbient` (~1/min) with a small burn-in shift. The per-second elapsed
  ticker stops because the interactive tree leaves composition. The observer is registered **only during
  a run** (`setAlwaysOn` toggled by `inWorkout`), so non-run screens keep normal screen-off + wrist-raise/
  touch relight. Exiting ambient on touch/wrist-raise is OS-handled (`onExitAmbient` → interactive).

## Key gotchas learned

- **Health Connect write validation is strict and throws (silently, if you swallow it).** Two real
  failures that killed run sync: (1) `HeartRateRecord.Sample` requires `beatsPerMinute` in **1..300** —
  WHS emits **0-bpm** sensor-gap samples, so filter `hr >= 1` (and clamp). (2) `ExerciseRoute` requires
  **strictly increasing timestamps** — WHS can deliver multiple points sharing a millisecond, so
  **sort + `distinctBy { epochMillis }`** the points before building the route. `HealthConnectWriter`
  does both. The run reaching the phone is NOT the issue — the **HC insert** is where it breaks.
- **Sync resilience:** `RunImporter` (mobile) is the single write path for both the live
  `RunSyncListenerService` and an app-open **catch-up** (`MainActivity.onResume` → `importQueued`, plus a
  "Re-sync watch runs" button). It scans the Data Layer for queued `/run` items and writes any not already
  imported, **de-duped by run start time** in SharedPreferences — so the listener and the catch-up can't
  double-insert. A missed/failed delivery self-heals on next open.
- **Debugging the phone↔watch↔HC pipeline over adb:** watch + phone connect via **wireless adb** (mDNS;
  `adb mdns services` to find the live `_adb-tls-connect` endpoint — **IP and port change** when the device
  re-joins wifi / wireless-debugging pauses on screen-off, and stale serials make `adb -s` *hang*, so
  always re-resolve). Watch package = `com.wearosgpx`, activity is under the `.mobile` namespace
  (`am start` needs `com.wearosgpx/com.wearosgpx.mobile.MainActivity`, or just
  `monkey -p com.wearosgpx -c android.intent.category.LAUNCHER 1`). No `sqlite3` on the watch — pull the
  Room DB with `adb exec-out run-as com.wearosgpx cat databases/wear_gpx.db` and query locally.
  `RunImporter.logRecentSessions` dumps existing HC sessions (origin/route/title) for format comparison;
  needs READ_* health perms which **can't be `pm grant`-ed** (HC manages them) — best-effort only.
- WHS returns Guava `ListenableFuture` → needs `com.google.guava:guava` on the compile classpath plus
  `androidx.concurrent:concurrent-futures-ktx` for `.await()`.
- The `health` foreground-service type requires `BODY_SENSORS` granted at runtime (API 34+) or
  `startForeground` throws — the service adds the `health` type only when granted, else runs `location`-only.
  Body sensors are optional (HR only); **location is the single required permission** to start a run.
- WHS live data only flows on **physical hardware** or a Wear OS 4 emulator with **synthetic exercise
  data** — a stock emulator shows zeros and may never reach `ACTIVE`.
- Routes: `RouteCatalog` lists imported `.gpx` from `getExternalFilesDir("routes")` first, then the
  bundled `res/raw/*.gpx` samples. **Import paths:** (1) phone → watch: the phone `MainActivity` "Send GPX
  route to watch" button picks a file (SAF) and pushes it via `DataClient` at `/route/<ts>`; the watch's
  `RouteImportListenerService` saves it to the routes dir. (2) dev: `adb push my.gpx
  /sdcard/Android/data/com.wearosgpx/files/routes/`. Note: the route list loads once (produceState) — a
  newly imported route shows after reopening the list.
