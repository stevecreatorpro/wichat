# WiChat

Android messenger (Kotlin, Compose, Hilt, Room + SQLCipher, libsignal) that works through an internet
relay or, with no network, as an offline Nearby Connections mesh. Node.js dev relay in `relay-server/`.
Setup on a new machine: `DEVELOPING.md`. Design: `SYSTEM_DESIGN.md`. Open work: `BACKLOG.md`.

## Commands

- Unit tests: `./gradlew :app:testDebugUnitTest` (crypto tests run real libsignal on the JVM)
- Debug APK: `./gradlew :app:assembleDebug`
- Release APK: `./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease`
  (signed only when `keystore/` exists; it is gitignored and must never be committed)
- Relay tests: `cd relay-server && npm test`

Gradle builds are slow (first build ~1 GB of downloads); run them in the background.
The owner often works from a phone: every push runs `.github/workflows/build.yml` (tests + APK
artifacts), so keep it green and tell them to grab the APK from the run's Artifacts.
Debug builds are signed with the committed `app/debug.keystore` (standard Android test key) so
builds from any machine update each other.

## Rules

- Wire format changes (`model/Envelope.kt`, `model/MessageContent.kt`, `mesh/MeshProtocol.kt`) break
  older installs: add round-trip tests (`MessageContentTest`) and note it for the user.
- `WireJson` uses `classDiscriminator = "type"`; no serialized class may have a property with that name.
- DB schema changes: bump `AppDatabase.version` and add an `AutoMigration`; keep `app/schemas/` committed.
- Mesh logic lives in pure-Kotlin `mesh/MeshEngine` and is tested with simulated phones in
  `MeshChainTest`; keep Android/Nearby code in `transport/MeshTransport` thin.
- Never put the signing key, relay `data.json`, or tokens in the repo.
