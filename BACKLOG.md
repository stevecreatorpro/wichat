# WiChat backlog: things to fix later

## ✅ 1. Photos, documents and voice notes don't arrive (fixed 2026-11-11, needs a phone re-test)

**Cause:** the message format used a field named `kind` to tell text, files and receipts apart, and
file messages also have their own `kind` field (photo/document/voice note). The JSON library refuses
to encode that clash, so after each upload finished, building the "here's your file" message
threw an error and the app marked it failed. That matched the relay evidence: 3 complete uploads,
no follow-up messages. It affected debug builds too; it was not the release shrinker as first guessed.

**Fix:** the type field is now `type` (`Envelope.kt`), and `MessageContentTest` encodes every
message type so this can't come back silently.

**Note:** both phones need the new build. Old and new versions can't read each other's messages.

**Still to do:** send a photo, a PDF and a voice note between two phones on the new build.

## 2. Files over the offline mesh

The mesh carries text and receipts. Photos, documents and voice notes still need the relay (the app
says so when offline). Plan: split the encrypted chunks into pieces of under 30 KB, carry them as mesh
envelopes with a size cap (e.g. 2 MB, so a voice note or compressed photo fits), and add a
`BlobStore` that reassembles them on the recipient.

## 3. Mesh ↔ internet bridge

When one phone in the mesh has internet, it could upload envelopes it carries to the relay (and the
reverse), joining offline areas to the rest of the world (SYSTEM_DESIGN.md 8.4). Needs a relay
"forward" frame that accepts sealed-sender envelopes addressed by mesh tag.

## 4. Mesh hardening

- "Delivered" notices aren't authenticated, so a hostile phone could make others drop messages early.
  Sign them with the recipient's identity key.
- Rate-limit how much each neighbour can make us carry (cache size cap per peer).
- Usernames on an offline-only mesh aren't unique. Two people with the same name are detected and
  the app refuses to guess, but there's no in-app way yet to choose between them (compare safety numbers).

## 5. Before a public launch (from DEPLOY.md)

Relay rate limiting and abuse controls, phone/email verification, push notifications (FCM),
certificate pinning, encrypting received files at rest, and the release lint check (skipped once
when building offline).
