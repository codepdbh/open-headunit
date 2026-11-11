# OpenHeadunit Lite (flavor `lite`)

An edition of Open Headunit that does one job: be the Android Auto receiver for **AionLink Sonda**, which
shows it on a GAC AION car display through the AionLink surface bridge (`AionLinkSurfaceService`, video,
touch and sound).

It installs next to the full app as `org.aionlink.headunit.lite` and shares no identifier with it: its custom
permissions are `org.aionlink.headunit.lite.permission.AIONLINK_SURFACE` and `...NAVIGATION_UPDATE` (manifest
placeholder `permissionPrefix`), so the full app, whoever signed it, can stay installed. AionLink Sonda talks
only to this package. Lite and AionLink Sonda must be signed with the same key, because the bridge permission is
`signature`; install Lite first, since it defines that permission.

What differs from the `github` flavor; the shared code is unchanged:

- **Fixed settings.** `LiteDefaults` writes them on every start: Self Mode only, 1080p, H.264, 30 FPS,
  landscape, audio sink on, AudioTrack output, no auto-start, and the onboarding wizard marked done.
- **One screen.** `LiteActivity` asks once for the safety notice and the permissions, shows status and can
  stop a session. The full UI is not reachable from the launcher.
- **No other system roles.** It is not a launcher, navigation app, deep-link target, home screen or USB
  accessory handler.
- **Smaller.** Only arm64-v8a native libraries and English/Spanish resources are included.

Build: `./gradlew :app:assembleLiteDebug` (or `assembleLiteRelease` with a signing key).

Android Auto still needs its developer setting **Start head unit server** turned on. Turn it on again after
every Android Auto update and every phone restart.

License: AGPL-3.0, like Open Headunit. The source of this edition is the `openheadunit-lite` branch of
github.com/codepdbh/open-headunit.
