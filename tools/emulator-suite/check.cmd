@echo off
rem The whole local check: unit tests, lint, the release build and every device test, then the suite.
rem Device tests install on every attached device, so they are pinned to the emulator: a phone that
rem happens to be plugged in is never touched (WallpaperUtilInstrumentedTest sets wallpapers).
set ANDROID_SERIAL=emulator-5554
if not defined SIGNING_KEYSTORE_PATH (
    rem The standard Android debug keystore; its passwords are the public defaults, not a secret.
    set "SIGNING_KEYSTORE_PATH=%USERPROFILE%\.android\debug.keystore"
    set SIGNING_STORE_PASSWORD=android
    set SIGNING_KEY_ALIAS=androiddebugkey
    set SIGNING_KEY_PASSWORD=android
)
pushd "%~dp0..\.."
call "%~dp0..\..\gradlew.bat" testDebugUnitTest lintDebug assembleRelease connectedDebugAndroidTest --continue --console=plain %*
set CHECK_EXIT=%ERRORLEVEL%
popd
exit /b %CHECK_EXIT%
