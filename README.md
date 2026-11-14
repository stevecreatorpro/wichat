# WiChat

Secure Android messenger built for when the network is down: phones chat directly over Bluetooth/Wi-Fi
and pass end-to-end encrypted messages along for each other across a crowd, and use an internet
relay when one is reachable. See [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md) for the full design.

## Status

| Area | Status |
|------|--------|
| Signal Protocol E2EE (PQXDH + Double Ratchet via `libsignal-android`) | ✅ working, unit-tested |
| Encrypted local storage (Room + SQLCipher, passphrase wrapped by Android Keystore) | ✅ |
| Registration, prekey upload/refresh, weekly signed-prekey rotation | ✅ |
| Relay transport (WebSocket, server acks, reconnect with backoff) | ✅ |
| Outbox with retry/backoff, dedup across transports, delivery + read receipts | ✅ |
| Safety numbers, key-change warning | ✅ |
| Compose UI: onboarding, chat list, new chat, conversation | ✅ |
| Development relay server (Node.js) | ✅ tested |
| **Phase 2:** encrypted documents, photos, audio files and voice notes (via relay) | ✅ bug fixed, needs a phone re-test ([BACKLOG.md](BACKLOG.md)) |
| Resumable chunked uploads/downloads via relay blob store, SHA-256 + per-chunk GCM checks | ✅ |
| Photos downscaled and re-encoded (strips EXIF/GPS); file names sanitised | ✅ |
| **Offline mesh:** direct phone-to-phone chat + multi-hop store-and-forward (Nearby Connections), sealed sender, daily recipient tags, key gossip | ✅ simulated 10-phone chain tested; not yet tried on phones ([TESTING_OFFLINE.md](TESTING_OFFLINE.md)) |
| Offline-only sign-up; connect or switch relay in Settings | ✅ |
| Files over the mesh, mesh ↔ internet bridge | 🔲 [BACKLOG.md](BACKLOG.md) |
| WebRTC direct transfer | 🔲 stub (Phase 6) |
| FCM wake-ups for offline recipients | 🔲 needs `google-services.json` |

## Layout

```
app/src/main/java/com/wichat/
  model/       Envelope (wire format), MessageContent, ids
  crypto/      SignalStore, KeyManager, SessionManager, FileCipher, DbKeyProvider
  data/        Room DB + DAOs, AccountStore, RelayApi, repositories
  transport/   Transport interface, RelayTransport, MeshTransport (Nearby), Router
  mesh/        MeshEngine (store-carry-forward), MeshCrypto (sealed sender, bundle checks), frames
  domain/      SendMessageUseCase, IncomingMessageProcessor, account use cases
  service/     MessagingService (foreground), OutboxProcessor, NetworkMonitor, Notifier
  ui/          Compose screens + ViewModels
  di/          Hilt module
relay-server/  Development relay (prekey directory + store-and-forward)
```

## Run it

1. Start the relay: double-click **`Start Relay Control.bat`**. It opens a control page with
   Start/Stop, the address for each device and a "Connect USB phone" button. Or from a terminal:
   ```bash
   cd relay-server && npm install && npm start
   ```
2. Open the project in Android Studio (or `./gradlew installDebug`) and run on two emulators.
   The default relay URL `http://10.0.2.2:8080` reaches your PC from an emulator.
   On a real phone, use your PC's Wi-Fi IP, e.g. `http://192.168.1.20:8080`.
3. Register a different username on each device, tap ✎ and message the other.

To reach someone **miles away**, deploy `relay-server` to any public host behind HTTPS
(e.g. a small VPS with Caddy/nginx) and enter `https://your-domain` on both phones.
Debug builds allow plain HTTP; release builds require TLS.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

```bash
cd relay-server && npm test
```

The crypto tests run the real Signal Protocol on the JVM (desktop `libsignal-client` build):
session setup, out-of-order delivery, replay and tamper rejection, persistence across restarts,
safety numbers, and key-change detection.

## Not production-ready yet

- The relay authenticates by username + bearer token only: no phone verification, rate limiting or abuse controls.
- Single device per account.
- Certificate pinning is left as a TODO in `network_security_config.xml`.
- Decrypted attachments are stored in app-private storage but not encrypted at rest yet.
- Files over 25 MB wait for a tap before downloading; the dev relay keeps blobs for 30 days.
