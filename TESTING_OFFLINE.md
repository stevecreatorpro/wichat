# Testing offline chat (no internet)

WiChat phones connect to each other directly over Bluetooth and Wi-Fi when there's no internet,
and pass encrypted messages along for each other, so a message can hop across a chain of phones
to reach someone out of range. Relaying phones can't read the message or see who sent it.

## What you need

- 2+ Android phones (Android 8 or newer) with **Google Play Services** (almost all phones sold with the Play Store).
- The new build on every phone (old versions can't read new messages).
- **Bluetooth and Wi-Fi turned on** (Wi-Fi doesn't need to be connected to anything). On Android 11 and
  older, also turn on **Location**.

## 1. Install

Same as before (TESTING_ON_PHONE.md / TESTING_RELEASE.md). If a phone already has WiChat, install
over it: your account and chats are kept.

## 2. Allow nearby devices

Open WiChat. If a banner says **"Allow nearby devices…"**, tap **Allow** and accept. The top of the
chat list then shows e.g. `@favour · no internet · 1 phone nearby`.

## 3. Test direct phone-to-phone chat

1. Turn on **Airplane mode** on both phones, then turn **Bluetooth and Wi-Fi back on**
   (airplane mode kills mobile data; Bluetooth/Wi-Fi can be re-enabled inside it).
2. Hold the phones within a few metres. Within ~30 seconds the status should say **1 phone nearby**.
3. Tap ✎. Under **Reachable without internet** you'll see the other person. Tap them.
4. Send a text. Ticks: 🕒 while nobody's in range, ✓ once handed to the nearby phone, ✓✓ when the
   other person's phone confirms it.

**Brand-new users with no internet:** on sign-up, tap **"No internet? Create it on this phone only"**.

## 4. Test hopping across phones (3+ phones)

Line up phones A, B, C so that A and C are too far apart to connect (or put them in different
rooms) but each can reach B.

1. Wait until A and C each show **1 phone nearby** and B shows **2 phones nearby**.
2. On A, start a chat with C (C appears under **Reachable without internet**: its keys hopped through B).
3. Send. B's status shows **carrying 1** briefly, C receives the message, and A gets ✓✓.
4. B never sees the text, the sender or the recipient's name.

**Carry test:** A sends while C is out of everyone's range. B shows **carrying 1**. Walk B over to C:
C receives it.

## Limits to know

- Range between two phones is roughly **10 to 30 m** (Bluetooth finds phones; data moves over Wi-Fi).
  Walls and bodies shorten it.
- Each phone links to up to **4 neighbours**; messages travel up to **16 hops** and are carried for **48 hours**.
- **Photos, files and voice notes need internet for now.** Text and receipts work offline (BACKLOG.md item 2).
- Keeping the mesh on uses some battery. Turn it off in **Settings → Offline mesh**.
- If a phone with internet is in the mesh, it doesn't yet forward messages to the internet (BACKLOG.md item 3).

## Settings

- **Offline mesh** on/off.
- **Relay server**: connect an offline account to a relay, or switch relays, without reinstalling.

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Stays at "0 phones nearby" | Bluetooth + Wi-Fi on, Location on (Android ≤ 11), app open on both, phones close together; try toggling Settings → Offline mesh |
| Banner: "Couldn't start…" | Same as above, then tap **Retry** |
| "@x isn't reachable…" | Their phone hasn't been in your mesh yet: get within range of them or someone connected to them |
| "Two different people nearby call themselves @x" | Someone may be impersonating them; meet and compare safety numbers (🔒) |
| No banner, nothing happens | The phone may lack Google Play Services (required for Nearby Connections) |
