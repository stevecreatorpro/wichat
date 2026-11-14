# Developing WiChat on a new device

Everything needed to build and run WiChat is in this repo, except two things kept out on purpose:

| Not in the repo | Why | What to do |
|-----------------|-----|------------|
| `keystore/` (release signing key + passwords) | Secret: anyone with it could publish fake updates of your app | Copy the folder from your old device privately (USB, password manager). Only needed for **signed release** builds; debug builds and tests work without it. Never commit it. |
| `local.properties` | Machine-specific path to the Android SDK | Created automatically when you open the project in Android Studio |

## 1. Install the tools

- **Android Studio** (recent). It brings the Android SDK. In *SDK Manager*, install **Android 15 (API 35)**
  and **Build-Tools 35**.
- **JDK 17** (Android Studio's bundled JDK is fine; Gradle runs on 17).
- **Node.js 18+**, for the relay server.
- **Git**.

## 2. Get the code

```bash
git clone https://github.com/stevecreatorpro/wichat.git && cd wichat
```

If you have the signing key, copy your old `keystore/` folder into the project root
(`wichat/keystore/wichat-release.jks` and `wichat/keystore/keystore.properties`).

## 3. Build and test

Open the folder in Android Studio and let it sync, or from a terminal:

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:assembleDebug
```

```bash
cd relay-server && npm ci && npm test
```

Release APK (signed only if `keystore/` is present):

```bash
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
```

Outputs: `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/release/app-release.apk`.

The first build downloads about 1 GB of dependencies (the Signal crypto library alone is 130 MB) and can
take a long time on a slow connection. Later builds reuse the cache. If the internet drops mid-build,
add `--offline` once everything has been downloaded.

## 4. Run the relay

Double-click `Start Relay Control.bat` (Windows) or run `node relay-server/control-panel.js`.
It opens a page with Start/Stop and the address for each device.

## Where to read next

| File | What's in it |
|------|--------------|
| `README.md` | What works today and the code layout |
| `SYSTEM_DESIGN.md` | Architecture, security design, routing, offline mesh |
| `BACKLOG.md` | Known issues and what to build next |
| `TESTING_ON_PHONE.md`, `TESTING_RELEASE.md`, `TESTING_OFFLINE.md` | Step-by-step phone testing |
| `DEPLOY.md` | Putting the relay online and shipping the release APK |

## Things learned the hard way

- **Phones on different app versions may not understand each other.** Any change to the message
  format (`Envelope`, `MessageContent`) needs every phone updated. Add a round-trip test to
  `MessageContentTest` for every new message type: a missing one broke attachments once.
- **Changing database tables needs a migration.** Phones already have version 2 installed. Bump
  `version` in `AppDatabase` and add an `AutoMigration` (Room checks it against `app/schemas/`), or
  users' apps crash on update.
- **Keep the same signing key forever.** Android refuses updates signed with a different key.
- **Debug and release builds can't replace each other** on a phone (different keys): uninstall first.
- **Release builds only accept `https://` relays.** Debug builds allow `http://` for local testing.
- On Windows, Defender sometimes quarantines PowerShell scripts that start hidden processes.
  The relay control panel is Node.js for that reason.
