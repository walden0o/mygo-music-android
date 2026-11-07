# MyGo Music for Android

An Android plugin-host shell for [MyGo Music](https://github.com/egoist/mygo-music).
MyGo's framework has no Android backend, so this app reimplements the desktop
plugin host in Kotlin: it embeds a QuickJS engine (via
[dokar3/quickjs-kt](https://github.com/dokar3/quickjs-kt)), implements the
host API (`host.storage`, `host.crypto`, `host.signInPrompt`, `fetch`,
timers, … — full contract in [HOST_SPEC.md](HOST_SPEC.md)), and runs the
official plugins **unmodified**.

## UI

The interface is Jetpack Compose + Material 3, reworked to follow the desktop
app's visual language (near-black canvas, a single warm-red accent, hairline
grid, cover-driven headers) while respecting Android's navigation patterns:
bottom navigation, mini player, full-screen now playing with artwork/lyrics
pages, and a bottom-sheet for plugin switching, settings and sign-in.

- Dark / light / follow-system themes, plus dynamic color on Android 12+
- Design tokens and the mapping from MyGo's desktop UI: see
  [REDESIGN.md](REDESIGN.md) and [DESIGN_SPEC.md](DESIGN_SPEC.md)
- UI source lives under `app/src/main/java/dev/walden/mygomusic/ui/`
  (`AppState.kt` is the single state holder, `ui/screens/` the pages,
  `ui/sheets/` the sheets, `ui/theme/` the design system)

## Features (MVP)

- QR-code sign-in (prompts rendered from `host.signInPrompt`)
- Home / daily recommendations, search (tracks, playlists, albums, artists,
  shows), playlists, album / artist / show pages
- Playback with Media3 (foreground media session, notification controls)
- Synced lyrics (word-timed lines or LRC, with translation)
- Per-plugin settings UI driven by each plugin's `plugin.json`

## Bundled plugins

Bundled under `app/src/main/assets/plugins/`, loaded from their original
`plugin.json` + main JS file, unchanged:

| Plugin | Source |
| --- | --- |
| NetEase Cloud Music (official) | egoist/mygo-music-netease-cloud-music |
| YouTube Music (official) | egoist/mygo-music-youtube-music |
| QQ Music | Amamiya23/mygo-music-qq-plugin |
| Kugou | fcmfcm1999/mygo-music-kugou |
| Kugou Concept | bitbyte404/mygo-music-kugou-lite |
| Bilibili | GentleM4dman/mygo-music-bilibili |
| Spotify | xingkaixin/mygo-music-spotify |
| MyGotify | r0k1s-i/mygotify |
| Li Zhi | djyde/mygo-lizhi |
| Navidrome | TengYangTuoHai/mygo-music-navidrome |
| Ampache | benhaotang/mygo-music-Ampache |
| fnOS Music | yusgsysh/mygo-music-fnos-plugin |
| YunLu Music | biaobiaobiao108/mygo-yun-music |
| Music Download | yunforge/mygo-music-download |
| NetEase (variant) | hubandad/mygo-music-netease |
| NetEase (variant) | SixWa12138/mygo-music-netease-music |

Plugins that depend on desktop helper processes (`host.spawn`: yt-dlp,
ffmpeg, librespot, …) or web sign-in load and can browse/search, but some
actions are unavailable on Android; the in-app plugin list marks them as
partially supported. Qishui (needs a desktop Node process) and Apple Music
(macOS remote playback) are not bundled.

## Build

Requires JDK 17 and the Android SDK (API 34).

```sh
./gradlew assembleDebug
```

The `harness/` directory is an independent JVM project that runs the
official NetEase plugin against the live service using the same host
prelude — useful for validating host semantics without a phone.

## Notes

- Package: `dev.walden.mygomusic`
- Plugins needing a server (Navidrome, Ampache, fnOS, YunLu) are configured
  in the in-app plugin settings page.

## License

AGPL-3.0. This is a derivative work in the MyGo Music ecosystem; bundled
plugins remain under their own authors' licenses and copyrights.
