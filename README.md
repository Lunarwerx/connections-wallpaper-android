<div align="center">
  <h1>Connections Wallpaper</h1>
  <p><strong>A Material 3 wallpaper rotator for Android that also browses the Connections Wallpaper gallery.</strong></p>

  [![License](https://img.shields.io/github/license/Lunarwerx/connections-wallpaper-android?style=flat)](LICENSE)
</div>

---

Connections Wallpaper is a fork of [Paperize](https://github.com/Anthonyy232/Paperize) by Anthony La,
licensed under the GNU GPL v3.0 like the original. Paperize did the hard part (a fast, careful
wallpaper changer). This fork adds the [Connections Wallpaper](https://wallpaper.connections.icu)
gallery, a home-screen button, and a new name.

## Features

- **Rotate from your own photos.** Add single photos or whole folders to an album. Folders stay
  current: new pictures in a folder join the rotation.
- **When it changes.** On a time interval, when you unlock (live mode), when you double-tap the
  wallpaper (live mode), from the home-screen widget, the Quick Settings tile, or the app shortcut.
- **Where it shows.** Home and lock screen together, lock screen only, home screen only, or a
  different album for each.
- **How it looks.** Fill, fit or stretch; darken (brightness), blur, vignette and grayscale.
- **Order.** In order, or shuffled.
- **Connections gallery.** The Connections tab browses and searches the public gallery, with tag
  filters. Tap a wallpaper to add the full-size picture to any album.

## The Connections tab

Browsing is free and needs no account. Adding a full-size picture to an album needs a Connections
key with the "Browse and download wallpapers" scope:

1. Make a key at [Connections Studio](https://studio.connections.icu/dev).
2. In the app, open the Connections tab, tap the key button next to search, and paste it.

The key stays on the device. Pictures you add are saved to `Pictures/Connections Wallpaper` and
join your album like any other photo.

## What changed from Paperize

- New Connections tab (`connections/`): gallery client, key storage, and saving to albums through
  the system photo library.
- New "Next wallpaper" home-screen widget (`service/widget/`).
- "Change on screen off" is labelled "New Wallpaper on Unlock": the next picture is chosen as the
  screen turns off, so it is waiting when you unlock.
- New name, app id (`icu.connections.wallpaper`) and icon. It installs next to Paperize rather than
  over it.

## Building from source

| Requirement | Version |
|-------------|---------|
| Java | 17 |
| Android Gradle Plugin | 9.3.2 |
| Gradle | 9.7.1 |
| Compile SDK | 37 |
| Minimum SDK | 31 (Android 12) |
| Target SDK | 36 |

Set `ANDROID_HOME` to your Android SDK, or put `sdk.dir` in `local.properties`, then:

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. On Windows, use `gradlew.bat`.

Paperize's own checks still apply:

```bash
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
```

## Architecture

The Paperize architecture is unchanged; see its notes below. The Connections tab sits beside it:

- `ConnectionsApi` calls `wallpaper.connectionsapi.com`: `GET /v1/wallpapers`, `GET /v1/tags`, and
  `POST /v1/wallpapers/download` with the member's key.
- `SaveConnectionsWallpaperUseCase` downloads the original into the photo library and adds it to
  an album through `AlbumRepository`, so the rotation code treats it like any other photo.
- The widget opens the same invisible activity as the launcher shortcut, which starts
  `WallpaperChangeService`.

Paperize's notes:

- `AlbumRepository` owns library mutations. Imports, reordering, removal, covers,
  and queue invalidation use Room transactions; provider scans run outside them.
- `WallpaperRepository` owns rotation queues and current-wallpaper records. Static changes and
  the live wallpaper engine both dequeue through `ChangeWallpaperUseCase.dequeueNext`.
- `SettingsCoordinator` persists wallpaper settings edits and applies their side effects one at a
  time; `WallpaperChangeService` helpers are the single way to start a manual change.
- Repositories and use cases throw on failure. ViewModels, services, and workers catch at the
  edge, using `runCatchingCancellable`, so coroutine cancellation always propagates.

## License

GNU General Public License v3.0; see [LICENSE](LICENSE). Original work © Anthony La and the
Paperize contributors.
