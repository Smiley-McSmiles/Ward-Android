<h1 align="center">Ward for Android</h1>

<p align="center">
  <img src="docs/ward.svg" alt="Ward icon" width="128" height="128">
</p>

<p align="center">
  <a href="https://github.com/Smiley-McSmiles/Ward-Android/releases"><img src="https://img.shields.io/badge/version-1.0.2-blue.svg?style=flat-square" alt="Version 1.0.2"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green.svg?style=flat-square" alt="MIT License"></a>
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin 2.0">
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4.svg?style=flat-square" alt="Jetpack Compose and Material 3">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg?style=flat-square&logo=android&logoColor=white" alt="Android 8.0 and newer">
  <a href="https://xmpp.org"><img src="https://img.shields.io/badge/protocol-XMPP%20%7C%20OMEMO-purple.svg?style=flat-square" alt="XMPP and OMEMO"></a>
  <a href="https://github.com/Smiley-McSmiles/Ward"><img src="https://img.shields.io/badge/calls-Jingle%20%7C%20WebRTC-orange.svg?style=flat-square" alt="Calls: Jingle and WebRTC"></a>
</p>

<p align="center"><strong>Private messaging on the open XMPP network, in your pocket.</strong></p>

Ward for Android is the native Android front end for [Ward](https://github.com/Smiley-McSmiles/Ward). It speaks the
same XMPP protocols, uses the same encryption model and follows the same visual language as the GTK application, so
the desktop and the phone feel like one product.

It is a complete Android messenger rather than a thin web wrapper: Kotlin and Jetpack Compose on the screen, Smack
and a native OMEMO stack underneath, Room for local history, and a foreground service that keeps your accounts
connected and your messages arriving. It works with any XMPP server, including your own, and interoperates with
Conversations, Monal, Dino, Gajim and Ward for GTK.

## Features

- **Encrypted by default.** OMEMO 2 (XEP-0384 0.8 with Stanza Content Encryption) for one-to-one chats, with legacy
  OMEMO 0.3 available for contacts that only publish old devices. Per-device fingerprints, trust and distrust, blind
  trust before verification, a lock and trust state on every message, and automatic fallback only where it is safe.
- **Your conversations, wherever you are.** One-to-one and group chats, message requests from strangers, replies,
  reactions, edits, delete for everyone, delivery and read receipts, typing notifications, drafts, mentions, `/me`,
  pinned and archived chats, and full-text message search.
- **Markdown that looks right.** Bold and italic text, strikethrough, inline code, fenced code blocks, quotes,
  lists, tables and links are rendered natively, without showing the Markdown syntax.
- **Files, images and voice.** HTTP upload (XEP-0363) with image previews and file metadata, AES-256-GCM encrypted
  uploads (XEP-0454) in encrypted chats, local decryption, and voice messages recorded on the device.
- **Groups and channels.** Private groups and public channels with sensible presets, invites, a members panel, roles
  and moderation, topics, bookmarks with autojoin, and group search.
- **Audio and video calls.** One-to-one calls with Jingle Message Initiation, Jingle session negotiation,
  ICE/DTLS-SRTP through WebRTC, STUN/TURN support, mute, speaker and camera controls.
- **Several accounts.** Any XMPP server, custom host and port, STARTTLS, self-signed certificates with trust on first
  use, a profile and avatar, presence, password changes and account removal.
- **For server admins.** Every ad-hoc command (XEP-0050) a server advertises, including Prosody and ejabberd module
  and configuration commands, with native forms, notes, next/previous/finish/cancel, CAPTCHA media, user counts,
  online and registered user lists, uptime and account invites.
- **Tools when you need them.** A live XML console with raw stanza sending, a service discovery browser, a debug log,
  and a preference screen generated from a typed schema.
- **A good Android citizen.** A foreground connection service, notifications with chat deep links, boot autostart,
  share and open intents, adaptive Material 3 with light, dark and accent colours, and no bottom navigation bar
  stealing space from your conversations.

## Encryption

Ward for Android keeps legacy OMEMO and OMEMO 2 on the same account identity and device ID. That matters: other
clients cache one identity per device, so using a second, unrelated OMEMO 2 identity makes messages appear as
`PublicDataInconsistency` or `SenderNotFound`.

- **OMEMO 2** uses Dino's libomemo/libsignal fork, relocated into a private package, with the Ed25519 identity and
  Stanza Content Encryption used by current OMEMO clients.
- **Legacy OMEMO 0.3** uses Smack's OMEMO implementation. It remains available for older clients and can be selected
  per contact.
- **Per-chat choice.** Each contact can use `Default`, `OMEMO 2`, `Legacy OMEMO`, `Off` or, when enabled, `OpenPGP`.
- **No silent plaintext.** If encryption is expected and cannot be completed, Ward reports the problem instead of
  sending the message unencrypted.
- **Key management.** Device fingerprints can be reviewed or forgotten, and **Settings → Encryption → Repair OMEMO 2**
  rebuilds the local OMEMO 2 state from the legacy identity and republishes the bundle.

OpenPGP support is intentionally basic: text messages with XEP-0374, key generation, PEP publication and fetching.

## Install

The app requires **Android 8.0 (API 26) or newer**. Download the latest signed APK from the
[releases page](https://github.com/Smiley-McSmiles/Ward-Android/releases), then open it on the device and allow
installation from that source if Android asks.

There is no Play Store build. The APK is signed with a local development key; verify the SHA-256 published with each
release if you need to.

## Building from source

Requirements:

- JDK 17 or 21
- Android SDK 35 with build-tools 35.0.0
- Gradle 8.9 (the wrapper downloads it)

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. `./build-apk.sh` is a small wrapper around the
same command. `./sign-release.sh` builds a release APK and signs it with the local Android debug key so it can be
installed directly; use your own signing configuration for a store build.

### Trying it with a local Prosody

From the GTK Ward checkout, run:

```sh
tools/run-prosody
```

It prints a port and test users (`alice`, `bob`, `admin`, password `password`). In the Android emulator use
`10.0.2.2` and that port with the address `alice@localhost`. On a phone on the same network use the host's LAN
address and make Prosody listen on it.

## Architecture

```text
app/src/main/java/org/ward/android/
  data/          Room entities, DAO, typed preference schema and storage
  xmpp/          Smack session, OMEMO, XEP-0050 admin, forms, extensions
  service/       foreground connection service and notifications
  ui/            Compose screens: chats, contacts, admin, settings, dialogs
  util/          JIDs, colours, time formatting, Keystore-backed secrets
```

One `XmppSession` per account owns the Smack connection, roster, MUCs and encryption managers. Room is the source of
truth for the interface, so the UI is reactive and survives Android killing and recreating activities. The foreground
service keeps sessions alive and turns incoming messages into notifications.

`PARITY.md` tracks the GTK feature set against the Android implementation.

## Status

Ward for Android is usable for everyday one-to-one and group messaging, including encrypted conversations. The
following pieces are still being finished:

- OMEMO 2 group encryption
- Background ringing for incoming calls
- OpenPGP beyond the basic XEP-0374 text flow
- Chat export, global search and link previews

The app never silently downgrades an encrypted chat to plaintext. See `PARITY.md` for the complete matrix and
`THIRD_PARTY_NOTICES.md` for the libraries included in the APK.

## Contributing

Bug reports, interoperability reports and pull requests are welcome at
[Smiley-McSmiles/Ward-Android](https://github.com/Smiley-McSmiles/Ward-Android). When reporting an encryption problem,
include the app version, the other client and its version, the XML console output around the failure, and whether the
chat used OMEMO 2 or legacy OMEMO. Do not paste private keys or account passwords.

## License

Ward for Android is MIT-licensed, like Ward. The Ward icon is Ward's icon.

The distributed APK also contains the Dino libomemo/libsignal fork (GPL-3.0), Smack (Apache-2.0),
PGPainless/Bouncy Castle, Jetpack Compose, Coil and WebRTC. Because of the GPL components, the combined binary is
distributed under GPL-3.0-compatible terms. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
