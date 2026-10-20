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
- **Explore.** Browse and search the Connections gallery, filter by tag, and swipe through it in a
  full-screen viewer. Set a picture on the home screen, the lock screen or both, or add it to any
  album so it joins the rotation.

## Sign in with Connections

For the first 7 days everything works without signing in; after that, the app asks you to sign in
with Connections before showing its screens. Wallpaper rotation never stops, signed in or not.

Full-size pictures from the gallery (Set wallpaper, Add to album) need a sign-in from day one.
Browsing never does. Pictures you download are saved to `Pictures/Connections Wallpaper` and
reused, so each downloads once.

## What changed from Paperize

- New Explore tab and viewer (`connections/`): gallery client, Connections sign-in after a 7-day
  start, and setting or saving originals through the system photo library.
- A Google Material 3 look: Google Sans Flex, a blue-and-neutral palette that steps surfaces in
  tone, bottom navigation, pill search and chips. Dynamic color still follows the wallpaper when
  it is on in Settings.
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
  `POST /v1/wallpapers/download` with the member's access token.
- `ConnectionsAccount` holds the sign-in: the PKCE start, the code redeem and token refresh at
  `accounts.connectionsapi.com/auth/refresh` (every call carries `Origin: https://wallpaper.connections.icu`),
  sign-out, and the 7-day clock. The code is bound to the phone browser's own Connections session,
  so sign-out only drops the app's tokens: revoking them would sign the browser out too.
  `SignInReturnActivity` is the link the browser comes back to; a link whose state is not the
  pending one is ignored, and a matching one redeems once.
- `ConnectionsOriginals` downloads an original into the photo library once, then adds it to an
  album through `AlbumRepository` or sets it through `WallpaperManager`.
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

The app's typeface is Google Sans Flex, © The Google Sans Flex Authors, under the SIL Open Font
License 1.1 ([licenses/GoogleSansFlex-OFL.txt](licenses/GoogleSansFlex-OFL.txt)). It ships slimmed
to its weight and optical-size axes and Latin characters.
