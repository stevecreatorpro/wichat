# Going live: relay on the internet + release APK

Once the relay runs on a public HTTPS server, phones message each other from anywhere,
with no PC involved. The relay only ever sees encrypted data.

## 1. Put the relay online (pick one)

### Option A: Fly.io (recommended: keeps data on a disk, supports WebSockets)

1. Create a free account at fly.io and install `flyctl`.
2. In `relay-server/fly.toml` change `app = "wichat-relay"` to a unique name.
3. From `relay-server/`:

   ```bash
   fly launch --copy-config --no-deploy
   ```

   ```bash
   fly volumes create wichat_data --size 3
   ```

   ```bash
   fly deploy
   ```

4. Your relay is now `https://<your-app-name>.fly.dev`. Check `https://<your-app-name>.fly.dev/health` shows `{"ok":true}`.

### Option B: Any VPS (DigitalOcean, Hetzner, AWS Lightsail…)

```bash
docker build -t wichat-relay relay-server && docker run -d --restart=always -p 127.0.0.1:8080:8080 -v wichat-data:/data wichat-relay
```

Put Caddy in front for automatic HTTPS (`relay.yourdomain.com { reverse_proxy 127.0.0.1:8080 }`).

> Hosts without a persistent disk (e.g. Render's free tier) wipe every account on restart. Don't use them.

## 2. Build the release APK pointing at your relay

```bash
./gradlew :app:assembleRelease -Pwichat.relayUrl=https://<your-app-name>.fly.dev
```

Output: `app/build/outputs/apk/release/app-release.apk`. Without `-Pwichat.relayUrl`, users type the
address on first launch.

## 3. Get it onto phones (no PC needed after this)

- Send `app-release.apk` by WhatsApp/Telegram/email/Google Drive, open it on the phone, allow
  **Install unknown apps** for that app when asked, then **Install**.
- Or publish on Google Play: build an App Bundle with `./gradlew :app:bundleRelease`.

## The signing key: back it up

`keystore/wichat-release.jks` and `keystore/keystore.properties` sign every release. Android
only accepts updates signed with the **same** key. If you lose them, existing users must uninstall
and lose their chats to get updates. Copy both files somewhere private (password manager,
encrypted USB). Never commit or share them.

## Still to do before a public launch

| Gap | Why it matters |
|-----|----------------|
| Rate limiting and abuse controls on the relay | Anyone can register unlimited usernames or flood the server |
| Phone number or email verification | Usernames are first-come, first-served |
| Firebase push (FCM) | Offline phones only get messages when they reopen the app |
| Certificate pinning | Extra protection against a compromised certificate authority |
| Encrypting received files at rest | They're in app-private storage, but not encrypted there |
| Testing the release build on real phones | R8 shrinking can break things that work in debug builds |
