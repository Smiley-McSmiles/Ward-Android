# F-Droid build metadata (draft)

This is a draft of the file that belongs in
[`fdroiddata`](https://gitlab.com/fdroid/fdroiddata) as
`metadata/org.ward.android.yml`. It is kept here so the release and the store
recipe stay in sync; it is not read by the Android build.

The matching store listing text, icon, screenshots and changelogs live in
[`../fastlane/metadata/android/en-US/`](../fastlane/metadata/android/en-US/).

## Submitting

1. Push the `v1.0.9` tag to GitHub. F-Droid builds the tag, not a branch.
2. Fork `fdroiddata` and create a branch named `org.ward.android`.
3. Copy this file to `metadata/org.ward.android.yml` in that fork.
4. Test it with `fdroid readmeta`, `fdroid rewritemeta org.ward.android`,
   `fdroid checkupdates --allow-dirty org.ward.android`,
   `fdroid lint org.ward.android` and `fdroid build org.ward.android`.
5. Commit with a `New App: org.ward.android` message and open a merge request.

## Notes

- `AntiFeatures: NonFreeNet` is declared because the optional GIF picker uses
  Tenor. The rest of the app works with any XMPP server. Remove the entry if
  the Tenor feature is ever removed.
- The update checker is opt-in and off by default, so the app is not marked
  `Tracking`. The manual "Check for updates" button only opens the GitHub
  release page and never installs an APK.
- Reproducible builds are optional. If a stable release signing key is used,
  add its SHA-256 to `AllowedAPKSigningKeys` and publish the same APK on
  GitHub.
- If a future release adds ABI splits, add one build block per ABI and a
  `VercodeOperation`, following the VercodeOperation guidance in the F-Droid
  quick start guide.
