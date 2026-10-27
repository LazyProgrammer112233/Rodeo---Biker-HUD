# Rodeo — Rider HUD

Landscape-only Android companion HUD for motorcycle riders in India. Prototype 1.

- Bike-themed UI (brand, model, year, paint colour)
- Embedded Google Maps two-wheeler navigation, ride tracking, SOS, crash detection
- Thermal guardian, vibration meter, pothole radar, pack mode, glove-free controls, garage wallet

## Build

GitHub Actions builds a debug APK on every push (see the Actions tab and the `latest-*` pre-releases).

Optional repository secrets:

| Secret | Purpose |
| --- | --- |
| `MAPS_API_KEY` | Google Maps Platform key with Navigation SDK for Android enabled |
| `FIREBASE_API_KEY`, `FIREBASE_APP_ID`, `FIREBASE_PROJECT_ID`, `FIREBASE_DB_URL` | Firebase Realtime Database for pack mode and shared pothole radar |

Without keys the app still runs: the map half becomes a radar view, and pack mode / hazard sharing run in demo mode.

Local builds: put the same names in `secrets.properties` at the repo root (untracked).
