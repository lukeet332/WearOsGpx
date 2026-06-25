# WearOsGpx

A standalone, sports-watch-style GPX navigation and run-tracking app for **Wear OS 4+**, built natively in
Kotlin + Jetpack Compose and optimized for the OnePlus Watch 2R's dual-chip architecture.

## Modules
- **`:wear`** — the watch app. Records runs with Wear Health Services (`ExerciseClient`) offloaded to the
  low-power co-processor, stores them in Room, and renders a follow/overview breadcrumb map, live stats,
  GPX route navigation (off-course alerts, turn cues, back-to-start), laps/splits, and always-on ambient.
- **`:mobile`** — a phone companion. Receives finished runs over the Wearable Data Layer and writes them
  to **Health Connect** (`ExerciseSessionRecord` + route + HR + distance) for OHealth/Strava. Also a
  route manager: create routes on an OpenStreetMap map (road-snapped via OpenRouteService), import GPX,
  and push routes to the watch.

## Setup
Create `local.properties` in the project root with:
```
sdk.dir=/path/to/Android/sdk
ORS_API_KEY=your_openrouteservice_key   # optional; users can also set it in-app under Settings
```
For watch↔phone pairing, both modules must be signed with the same key — see `keystore.properties`
(gitignored). Build with `./gradlew :wear:assembleDebug :mobile:assembleDebug`.

See `CLAUDE.md` for architecture notes and conventions.
