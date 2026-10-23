# Ward for Android

Ward is a private XMPP messenger, encrypted with OMEMO, with a full Markdown
composer, groups and channels, files, message search, and the server
administration console that Ward for GTK is known for.

This is the Android front end, written in Kotlin with Jetpack Compose and
Smack. It uses the same visual language and the same concepts as the GTK
application, so the two feel like one product.

## What is here

- **Accounts**: sign in to any XMPP server, several accounts, custom server
  and port, STARTTLS, self-signed certificates (trust on first use), a
  profile, presence, password change, account removal.
- **Chats**: one-to-one and group chats, search and filters, pinned chats,
  archive, unread badges, drafts, requests from strangers.
- **Rich messages**: Markdown rendering (bold, italic, strike, code blocks,
  quotes, lists, tables, links), replies, reactions, edits, delete for
  everyone, delivery receipts, read markers, typing notifications, mentions,
  `/me`, attachments and images.
- **Groups**: join and create groups, private and public presets, members,
  roles, moderation, topics, invites, bookmarks with autojoin and group
  chat. Messages from members are shown with their nicknames.
- **Files**: HTTP upload (XEP-0363) with image previews, file metadata,
  AES-256-GCM encrypted uploads for encrypted chats (XEP-0454), local
  decryption and voice messages recorded on the device.
- **History**: local SQLite history and server-side message archive
  catch-up (XEP-0313).
- **OMEMO**: OMEMO 2 (XEP-0384 0.8, Stanza Content Encryption) for one-to-one
  chats, with per-device fingerprints, trust and distrust, blind trust before
  verification and automatic encryption. Legacy OMEMO 0.3 remains available
  and is used automatically for contacts that only publish legacy devices.
- **Server administration**: every ad-hoc command (XEP-0050) a server
  offers, including Prosody's module and config commands, with native forms,
  notes, next/prev/finish/cancel, CAPTCHA media, user counts, registered and
  online user lists, uptime, and one-time account invites.
- **Tools**: XML console (live stanzas and raw send), service discovery
  browser, debug log, and an extensive preference screen generated from a
  typed schema.
- **Calls**: one-to-one audio and video calls with Jingle Message Initiation,
  Jingle session negotiation, ICE/DTLS-SRTP through WebRTC, STUN/TURN settings,
  mute, speaker and camera controls.
- **Android integration**: foreground connection service, notifications
  with chat deep links, boot autostart, share/open intents, adaptive Material
  3 UI, light/dark theme and accent colours.

## Building

Requirements: JDK 17 or 21, Android SDK 35 (build-tools 35.0.0), and Gradle
8.9 (the wrapper downloads it).

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
`./sign-release.sh` builds a release APK and signs it with the local debug key
so it can be installed directly.

### Trying it with a local Prosody

From the GTK Ward checkout:

```sh
tools/run-prosody
```

It prints a port and test users (`alice`, `bob`, `admin`, password
`password`). In the Android emulator use `10.0.2.2` and that port, with the
address `alice@localhost`. On a phone on the same network use the host's
LAN address and make Prosody listen on it.

## Architecture

```
app/src/main/java/org/ward/android/
  data/          Room entities, DAO, typed preference schema and storage
  xmpp/          Smack session, OMEMO, XEP-0050 admin, forms, extensions
  service/       foreground connection service and notifications
  ui/            Compose screens: chats, contacts, admin, settings, dialogs
  util/          JIDs, colours, time formatting, Keystore-backed secrets
```

One `XmppSession` per account owns the Smack connection, roster, MUCs and
OMEMO manager. Room is the source of truth for the UI, so the interface is
reactive and survives Android killing and recreating activities. The
foreground service keeps sessions alive and turns incoming messages into
notifications.

## Known limitations

OMEMO 2 group encryption and background ringing for calls are not wired up
yet; one-to-one OMEMO 2 and legacy OMEMO both work. OpenPGP is basic (text XEP-0374, key generation,
publication and fetching) and encrypted files/voice notes are included.
See `PARITY.md` for the complete matrix. The app never silently falls back
to plaintext when encryption is expected: it reports the problem instead.

## License

The Ward for Android code is MIT, like Ward. The icon is Ward's icon.
The distributed APK also contains the Dino libomemo/libsignal fork
(GPL-3.0), Smack (Apache-2.0), PGPainless/Bouncy Castle, Jetpack Compose
and Coil; because of the GPL components the combined binary is distributed
under GPL-3.0-compatible terms. See `THIRD_PARTY_NOTICES.md`.
