# Rodeo — Rider HUD

Landscape-only Android companion HUD for motorcycle riders in India. Prototype 1.

- Bike-themed UI (brand, model, year, paint colour)
- Keyless in-app map (OpenStreetMap) with Google Maps app hand-off for two-wheeler turn-by-turn
- Ride tracking, SOS by SMS from the default SIM, crash detection with a drop test
- Camera hand gestures for music (swipe right / left, palm over the lens to pause)
- Thermal guardian, vibration meter, pothole radar, pack mode, glove-free controls, garage wallet

## Build

GitHub Actions builds a debug APK on every push (see the Actions tab and the `latest-*` pre-releases).

Optional repository secrets:

| Secret | Purpose |
| --- | --- |
| `FIREBASE_API_KEY`, `FIREBASE_APP_ID`, `FIREBASE_PROJECT_ID`, `FIREBASE_DB_URL` | Firebase Realtime Database for pack mode and shared pothole radar |

No API keys are needed. Without the Firebase secrets, pack mode and hazard sharing run in demo / on-device mode.

Local builds: put the same names in `secrets.properties` at the repo root (untracked).
