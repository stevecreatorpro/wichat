# Testing WiChat on your phone (USB)

Messaging needs **two accounts**, so you need two devices:

- **Device A:** your phone, connected to the PC with a USB cable
- **Device B:** an Android emulator on the PC (or a second phone)

Both talk to the relay server running on your PC.

---

## 1. Turn on USB debugging (one time)

1. On the phone open **Settings → About phone**.
2. Tap **Build number** 7 times until it says "You are now a developer".
   (On Samsung: Settings → About phone → Software information → Build number. On Xiaomi: tap **MIUI version**.)
3. Go back to **Settings → System → Developer options** (or search "Developer options").
4. Turn on **USB debugging**.
5. Xiaomi/Redmi only: also turn on **Install via USB**.

## 2. Connect the phone

1. Plug the phone into the PC with a cable that carries data (some charge-only cables don't).
2. If the phone asks what the USB connection is for, choose **File transfer**.
3. A popup appears on the phone: **Allow USB debugging?** Tick **Always allow from this computer** and tap **Allow**.
4. Check the PC can see it:

   ```bash
   "$LOCALAPPDATA/Android/Sdk/platform-tools/adb" devices
   ```

   You should see a line ending in `device`. If it says `unauthorized`, unlock the phone and accept the popup.

## 3. Start the relay server on the PC

**Easiest:** double-click **`Start Relay Control.bat`** in the `wichat` folder. A page opens in your
browser with **Start**/**Stop** buttons, the exact address to type on each device (with **Copy**
buttons), a **Connect USB phone** button that does step 4 for you, and the relay's live log.
Keep the black window that opens alongside it; closing it stops the relay.

**Or from a terminal** (leave it running):

```bash
cd ~/Downloads/wichat/relay-server && npm start
```

It prints `WiChat relay listening on :8080`. If Windows Firewall asks, click **Allow** for private networks.

## 4. Let the phone reach the relay through the USB cable

This forwards the phone's `localhost:8080` to the PC over USB, so it works whatever Wi-Fi you're on:

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb" reverse tcp:8080 tcp:8080
```

Re-run this every time you unplug and reconnect the phone.

## 5. Build and install the app

Build (skip this if `app/build/outputs/apk/debug/app-debug.apk` already exists and you haven't changed code):

```bash
cd ~/Downloads/wichat && ./gradlew :app:assembleDebug
```

Install on the phone (the APK is large because it carries the Signal crypto library for every CPU type):

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb" install -r ~/Downloads/wichat/app/build/outputs/apk/debug/app-debug.apk
```

`Success` means it's installed. If the phone shows "Install blocked" or a Play Protect warning, choose **Install anyway**. That's expected for apps you build yourself.

## 6. Create account A on the phone

1. Open **WiChat** on the phone and allow notifications.
2. Username: e.g. `favour`
3. Relay server: `http://localhost:8080`
4. Tap **Create account**. The chat list should say **online** at the top.

## 7. Create account B on an emulator

1. Open **Android Studio → Device Manager** (phone icon on the right) → **Create device** → pick e.g. *Pixel 7*, a recent system image → **Finish**, then press ▶ to start it.
2. With the emulator running, install the app on it:

   ```bash
   "$LOCALAPPDATA/Android/Sdk/platform-tools/adb" -e install -r ~/Downloads/wichat/app/build/outputs/apk/debug/app-debug.apk
   ```

   (`-e` targets the emulator. With both connected, `-d` targets the USB phone.)
3. Open WiChat on the emulator. Username e.g. `tester`, relay server `http://10.0.2.2:8080` (the default), **Create account**.

**Using a second phone instead?** Connect it by USB, run the `adb reverse` command for it with `-s <serial>` (serial from `adb devices`), install with `-s <serial>`, and use `http://localhost:8080` too.

## 8. Test checklist

On the phone tap ✎, type `tester`, **Start secure chat**, then:

| # | Try | Expected |
|---|-----|----------|
| 1 | Send a text | Appears on the emulator; ticks go 🕒 → ✓ → ✓✓ |
| 2 | Open the chat on the emulator | Phone shows **✓✓ read** |
| 3 | Reply from the emulator | Arrives on the phone, with a notification if the chat isn't open |
| 4 | 📎 → **Photo** | Upload progress bar, then the photo shows on both sides |
| 5 | 📎 → **Document or audio file** (a PDF, say) | File name and size appear; tap it on the other side to open |
| 6 | 🎤 → speak → send | Voice note with a ▶ button plays on the other side (allow microphone) |
| 7 | Tap 🔒 on both devices | Same 60-digit safety number on both |
| 8 | Close the emulator's app, send 2 messages from the phone, reopen it | Both messages arrive (the relay held them) |
| 9 | Stop the relay (Ctrl+C), send a message, start the relay again | Message waits at 🕒, then sends by itself |
| 10 | Unplug the USB mid-upload of a big file, plug back in, re-run `adb reverse` | Upload resumes instead of restarting |

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `adb devices` shows nothing | Try another cable/port, pick **File transfer** mode, re-toggle USB debugging |
| Chat list says **offline** on the phone | Relay not running, or re-run `adb reverse tcp:8080 tcp:8080` |
| "Username already taken" | Pick another name, or stop the relay and delete `relay-server/data.json` to reset |
| App crashes | Send me the output of `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb" logcat -d -s AndroidRuntime` |
| Starting over on a device | Uninstall WiChat from it (this wipes its keys), then install again |

## Without the USB cable (same Wi-Fi)

You can use the PC's Wi-Fi address instead of `localhost`, e.g. `http://10.197.243.46:8080`
(check yours with `ipconfig`). Campus and public Wi-Fi often block devices from talking to each
other, so the USB method above is more reliable. To reach people **miles away**, the relay has
to run on a public server with HTTPS (see README.md).
