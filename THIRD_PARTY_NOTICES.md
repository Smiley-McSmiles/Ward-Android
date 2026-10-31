# Third-party notices

Ward for Android's own source code is released under the MIT License; see
[LICENSE](LICENSE). The app also links third-party libraries, several of which
are under the GNU General Public License version 3. **The combined APK is
therefore distributed under GPL-3.0-only**, and the complete corresponding
source is this repository plus the upstream projects listed below.

The full license texts are in [LICENSES/](LICENSES/). SPDX identifiers are used
throughout; where a project offers a choice, the identifier reflects the terms
the app relies on.

## Encryption libraries

| Component | Version | License | Source |
| --- | --- | --- | --- |
| dino/libomemo-java (libsignal-protocol fork) | v0.5.2 | GPL-3.0-only | <https://github.com/dino/libomemo-java> |
| signal-protocol-java (via Smack) | 2.6.2 | GPL-3.0-only | <https://github.com/signalapp/libsignal-protocol-java> |
| curve25519-java / curve25519-android (Dino fork) | 0.5.1 | GPL-3.0-only | <https://github.com/dino/curve25519-java> |
| Protocol Buffers Java Lite | 3.10.0 | BSD-3-Clause | <https://github.com/protocolbuffers/protobuf> |
| PGPainless | 1.7.6 | Apache-2.0 | <https://github.com/pgpainless/pgpainless> |
| Bouncy Castle | 1.80 | MIT | <https://www.bouncycastle.org/> |

`dino/libomemo-java` is built from its tagged source by JitPack and relocated
from `org.whispersystems.libsignal` into `org.ward.omemo2.signal` at build time
by the `:omemo2` Gradle module. Its protobuf runtime is relocated to
`org.ward.omemo2.protobuf` for the same reason. No prebuilt encryption JARs are
checked into this repository.

## Networking and messaging

| Component | Version | License | Source |
| --- | --- | --- | --- |
| Smack (Android, TCP, IM, OMEMO) | 4.4.8 | Apache-2.0 | <https://github.com/igniterealtime/Smack> |
| JXMPP | 1.0.2 | Apache-2.0 | <https://github.com/jxmpp/jxmpp> |
| OkHttp (via Coil) | 4.12.0 | Apache-2.0 | <https://github.com/square/okhttp> |

## UI, storage and media

| Component | License | Source |
| --- | --- | --- |
| Jetpack Compose, Material 3, Activity, Lifecycle, Navigation | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| AndroidX Core, Room, WorkManager, DataStore, Security Crypto, WebKit, Emoji2, ExifInterface, Media3 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| Coil | Apache-2.0 | <https://github.com/coil-kt/coil> |
| WebRTC Android (`im.conversations.webrtc`) | 129.0.0 / Apache-2.0 | <https://github.com/conversations-im/webrtc-android> |
| ZXing Core and ZXing Android Embedded | Apache-2.0 | <https://github.com/zxing/zxing>, <https://github.com/journeyapps/zxing-android-embedded> |
| Gson | Apache-2.0 | <https://github.com/google/gson> |
| Kotlin standard library and coroutines | Apache-2.0 | <https://github.com/JetBrains/kotlin> |

The WebRTC build ships native libraries for the supported ABIs. These are
fetched from Maven Central as a normal dependency; the app does not download
code at runtime.

## Artwork and sounds

- The Ward icon (`docs/ward.svg`, the launcher mipmaps and
  `fastlane/metadata/android/en-US/images/icon.png`) is part of Ward and is
  MIT-licensed.
- `app/src/main/res/raw/phone_incoming_call.oga` and
  `phone_outgoing_calling.oga` are unmodified copies from the freedesktop.org
  sound theme 0.8, from the Ekiga sound set. Copyright Damien Sandras;
  **GPL-2.0-or-later**. The theme's credits are reproduced in
  [LICENSES/sound-theme-freedesktop-CREDITS.txt](LICENSES/sound-theme-freedesktop-CREDITS.txt),
  and the source archive is
  <https://people.freedesktop.org/~mccann/dist/sound-theme-freedesktop-0.8.tar.bz2>.

## Build-only components

Gradle, the Android Gradle Plugin, the Kotlin compiler and the Android SDK are
used to build the app but are not part of its source. The Gradle wrapper is the
standard one from the Gradle project.
