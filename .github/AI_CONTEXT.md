# AI_CONTEXT.md — context for the automated fix bot

This file is fed to the AI fix bot (whichever primary + fallback model `ai_model.json`
configures) before it proposes a fix, so its changes stay on-brand and respect existing
decisions. Keep it
**accurate and concise**. The bot may *append* a learning to the bottom section; humans
own the rest. (For the full developer spec, see `CLAUDE.md` at the repo root.)

## What the app is

**WearOsGpx** — a standalone, Garmin-like **GPX navigation + run-tracking** app for
**Wear OS 4+**, optimised for the **OnePlus Watch 2R** (Snapdragon W5 + BES2700 low-power
MCU). It records a run **fully standalone on the watch** (no phone needed), then syncs the
finished run to a phone companion that writes it into **Health Connect** → Samsung Health
(primary) / Strava (optional) / OHealth.

## Modules

- **`:wear`** (minSdk 33, namespace `com.wearosgpx`) — the watch app. Wear Health Services
  `ExerciseClient` foreground service → Room → Jetpack Compose (Wear) UI.
- **`:mobile`** (minSdk 26, namespace `com.wearosgpx.mobile`) — phone companion. Compose UI +
  `WearableListenerService` → Health Connect, plus an OSM route creator/discovery screen.
- Both modules share **`applicationId com.wearosgpx`** and the **same signing key** (required
  or the Wearable Data Layer won't pair). The differing *namespaces* are intentional — do not
  "fix" them to match.

## Build / toolchain (do not silently upgrade)

- AGP 8.9.2, Kotlin 2.1.0, **compileSdk/targetSdk = 35**. Newer AndroidX (core ≥1.16,
  lifecycle ≥2.9, room ≥2.7, compose-BOM 2025+) requires **compileSdk 36 / AGP 9** and will
  break the build — those artifacts are pinned/ignored in `.github/dependabot.yml`. Do **not**
  raise compileSdk or those versions to fix a dependency warning.
- Versions are centralised in `gradle/libs.versions.toml`. Kotlin 2.x uses the `kotlin-compose`
  Gradle plugin (not `composeOptions`).
- Verify locally with `./gradlew :wear:assembleDebug`. CI runs the JVM unit tests only
  (no emulator): `./gradlew :mobile:testDebugUnitTest :wear:testDebugUnitTest`.

## Product decisions (respect these — don't regress them)

- **Theme:** dark background, **neon-green** (`Color(0xFF39FF14)`) route/track line with a
  layered glow; big-number stats with small units (Garmin-like). Shared across watch + phone.
- **One usable hardware button** (bottom): short = Start/Pause/Resume, long = Stop. **Every**
  button action must also have an on-screen touch equivalent — the hardware key may not deliver
  KeyEvents, so it can never be the only path. Swipe between screens.
- **Round screen:** do NOT use a rectangular inscribed box; center content in the circle's
  widest band; lists use `ScalingLazyColumn`; edge buttons use `BottomEdgeShape`.
- **Map modes are automatic, never a tap-toggle:** run ACTIVE → follow (track-up, zoomed,
  heading chevron); idle/paused/preview → overview (whole route fitted, north-up).
- **Nav flow:** RouteList → Preview → Activity (map first, swipe to Pause/Stop) → Finished
  summary → back to list. Forward transitions slide in from the right, back from the left.

## Efficiency rule (a priority — don't violate)

**Never force the application processor (W5) awake.** Offload sensing to the low-power hub only
via WHS `ExerciseClient`. So: let WHS **batch** sensor deliveries and process per batch; no
per-second UI tickers (live time updates per batch); **never re-read the whole track from Room
per sample** (cumulative distance is kept in memory, points batch-inserted per delivery); don't
hold our own `WakeLock`; defer phone sync to when charging/docked, never mid-run.

## Known gotchas

- WHS returns Guava `ListenableFuture` → needs `com.google.guava:guava` plus
  `androidx.concurrent:concurrent-futures-ktx` for `.await()`.
- The `health` foreground-service type requires `BODY_SENSORS` granted at runtime (API 34+) or
  `startForeground` throws — add the `health` type only when granted, else run `location`-only.
  **Location is the single required permission to start a run; body sensors (HR) are optional.**
- WHS live data only flows on physical hardware or a Wear OS 4 emulator with synthetic exercise
  data; a stock emulator shows zeros.
- Health Connect: use `Metadata()` (not the internal `Metadata.EMPTY`). The permission gate uses
  core permissions (session/distance/HR) only — NOT the route permission (HC grants it via a
  separate flow and may not report it in `getGrantedPermissions()`).
- Some types are intentionally duplicated in both modules and must be kept in sync: `RunPayload`,
  `RouteIndex` / `RouteIndexEntry`.

## Testing

- JVM unit tests live in both modules and are the CI gate. When you fix logic, prefer changes
  that keep those green; do not delete or weaken a test to make CI pass.

## Learnings (appended by the bot)

<!-- The fix bot may append one concise, durable bullet here per PR. Humans review every PR. -->
