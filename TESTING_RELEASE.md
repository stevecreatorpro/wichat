# Testing the release APK

The release APK (`app/build/outputs/apk/release/app-release.apk`) only connects to a secure
`https://` relay. Without hosting anything, the quickest way to get one is a free Cloudflare
quick tunnel: it gives your PC's relay a temporary `https://` address that works from anywhere
with internet, which also tests messaging between distant phones.

You need: internet on the PC and phones, two accounts (two phones, or one phone plus an emulator).

---

## 1. Start the relay

Double-click **`Start Relay Control.bat`** and press **Start**. Wait for the green light and
**Relay is running on port 8080**. Opening the page alone doesn't start the relay. Keep both windows open.

## 2. Give the relay an https:// address

1. Install Cloudflare's tunnel tool (one time). In a terminal:

   ```bash
   winget install --id Cloudflare.cloudflared
   ```

   Close and reopen the terminal afterwards. No account is needed.
2. Start the tunnel and leave this window open for the whole test:

   ```bash
   cloudflared tunnel --url http://localhost:8080
   ```

3. In its output, find a line like `https://random-words-here.trycloudflare.com`. That is your
   **relay address**.
4. Check it works: open `https://<that-address>/health` in a browser. It should show `{"ok":true}`.

> The address changes every time you restart `cloudflared`, and the app can't switch addresses
> yet. Accounts made with an old address stop connecting, so keep the tunnel running until you're done.

## 3. Remove the debug app from the phone (if installed)

The debug and release builds are signed with different keys, so Android won't install one over
the other ("App not installed"). On the phone: long-press **WiChat → App info → Uninstall**.
This deletes the debug account and chats.

## 4. Put the release APK on the phone

**Without a cable:**

1. Send `app-release.apk` (148 MB) to yourself: upload it to Google Drive, or send it in a
   WhatsApp/Telegram chat with yourself.
2. On the phone, tap the file → when asked, allow **Install unknown apps** for that app → **Install**.
3. If Play Protect warns about an unknown app, tap **More details → Install anyway**. That's
   expected for apps you build yourself.

**Or with the USB cable:**

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb" install ~/Downloads/wichat/app/build/outputs/apk/release/app-release.apk
```

## 5. Create account A

Open WiChat, pick a username, and replace the `https://` in **Relay server** with your full
tunnel address (e.g. `https://random-words-here.trycloudflare.com`). Tap **Create account**.
The chat list should say **online**.

## 6. Create account B (pick one)

- **A second phone** (best test): repeat steps 3–5 on it with the **same** tunnel address. It
  can be on mobile data or in another city: anything with internet works.
- **The emulator on your PC:** the release APK doesn't run on the emulator (it's built for real
  phones only), so install the **debug** APK there instead and use `http://10.0.2.2:8080`.
  Both builds talk to the same relay, so they can message each other:

  ```bash
  "$LOCALAPPDATA/Android/Sdk/platform-tools/adb" -e install -r ~/Downloads/wichat/app/build/outputs/apk/debug/app-debug.apk
  ```

## 7. What to test

| # | Try | Expected |
|---|-----|----------|
| 1 | Send a text from A to B | Arrives; ticks go 🕒 → ✓ → ✓✓ → ✓✓ read |
| 2 | Put one phone on **mobile data** (Wi-Fi off) and chat | Still works: this is the "miles away" path |
| 3 | 📎 → Photo, 📎 → Document, 🎤 voice note | Upload progress, then it opens or plays on the other phone |
| 4 | Tap 🔒 on both | Same 60-digit safety number |
| 5 | Close the app on B, send from A, reopen B | Messages arrive when B reconnects |
| 6 | Leave the app in the background 10+ minutes, then message it | It reconnects and receives (no push notifications yet) |

Anything that works in the debug build but fails here is likely the release build's code
shrinking (R8). Tell me what you did and what happened.

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `cloudflared` shows `Unable to reach the origin service … actively refused it` | The relay isn't running: press **Start** in Relay Control and wait for the green light |
| "Enter your relay's https:// address" | Paste the full `https://…trycloudflare.com` address |
| "App not installed" | Uninstall the debug WiChat first (step 3) |
| Chat list says **offline** | Is the relay running (green in Relay Control)? Is `cloudflared` still open? Same address as when you signed up? |
| The tunnel address stopped working | You restarted `cloudflared`: uninstall/reinstall the app and sign up with the new address |
| App crashes | Connect by USB and send me the output of `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb" logcat -d -s AndroidRuntime` |

## After testing

The quick tunnel only works while your PC, the relay and `cloudflared` are running. For a
permanent address that doesn't depend on your PC, deploy the relay (see DEPLOY.md) and rebuild
the APK with that address built in.
