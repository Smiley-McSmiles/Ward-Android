# Parity with Ward for GTK

Ward for Android is a native reimplementation of the same product. This file
tracks the desktop feature set and what the Android front end does today.

## Implemented

| GTK feature | Android |
| --- | --- |
| Sign in, several accounts, custom host/port/TLS, invites | Yes |
| Self-signed certificate handling | Trust on first use, with the server fingerprint in the XML log |
| Roster, presence, subscriptions, blocking | Yes |
| Contacts, contact requests, rename, groups | Yes |
| Profile photos: PEP (XEP-0084) and vCard fallback (XEP-0153) | Yes, cached locally and shown in chats |
| One-to-one chats, carbons, MAM catch-up, history | Yes |
| Markdown, code blocks, quotes, lists, tables, links | Yes |
| Replies, reactions, edits, retractions, receipts, read markers | Yes |
| Typing notifications, drafts, mentions, `/me`, message search | Yes |
| Message layout, text scale, accent colours, light/dark | Yes |
| File/image upload (XEP-0363), previews | Yes, including aesgcm-encrypted files (XEP-0454) |
| Voice messages | Yes, recorded with the device microphone |
| Groups and channels, private/public presets, members, roles, moderation | Yes |
| Topics, nicknames, invites, bookmarks with autojoin, group search | Partial (search UI is address based) |
| Server administration (XEP-0050), every Prosody/ejabberd command, forms, CAPTCHA | Yes, tested against Prosody 13 |
| User counts, online/registered user lists, uptime, account invites | Yes |
| XML console, service discovery browser, debug log | Yes |
| Notifications, background connection, boot autostart | Yes |
| Preferences generated from a typed schema | Yes, grouped by General, Appearance, Chats, Calls, Attachments, Notifications, Encryption, Privacy, Security, Accounts, Storage and Advanced |
| OMEMO (XEP-0384 legacy), device fingerprints, trust, BTBV | Yes |
| OMEMO 2 / SCE (XEP-0384 v0.8) | Yes: one-to-one, Ed25519 device bundles, SCE payloads, encrypted replies/reactions/edits/retractions; legacy OMEMO 0.3 fallback for contacts with only legacy devices |
| Encrypted files (XEP-0454 aesgcm) | Yes |
| OpenPGP (XEP-0373/0374) | Basic XEP-0374 text, key generation, PEP key publication/fetch |
| Voice messages | Yes |
| Audio/video calls (Jingle, WebRTC, XEP-0353) | Yes: JMI propose/accept, Jingle session/ICE/DTLS, audio and video, mute/speaker/hangup; bundled Ward ringtone and dial tone with per-sound settings |
| OMEMO 2 group encryption | Not yet |
| Voice messages, link previews, global search, chat export | Not yet |
| Tray icon, XDG desktop search, systemd/runit, custom CSS | Desktop-only, no Android equivalent |

## Notes

- The legacy OMEMO implementation interoperates with Conversations, Dino,
  Gajim and Ward for GTK when Ward's legacy OMEMO library is enabled
  (Preferences > Encryption > Allow legacy OMEMO). The GTK app includes that
  library in its official packages.
- OMEMO 2 uses Dino's libomemo/libsignal fork, relocated to a private
  package and with a small session-state patch that preserves the Ed25519
  identity bytes. It interoperates with python-omemo/twomemo (Ward for GTK)
  in both directions and can coexist with Smack's legacy OMEMO engine.
- Sending is blocked rather than silently downgraded when encryption is
  expected but unavailable.

The admin console is deliberately generic: it lists every command the
server advertises and runs the server's form, exactly as the GTK version
does. There is no Prosody-only hard-coded UI, so Prosody module management,
host activation, config reload, invites and every XEP-0133 command work.
