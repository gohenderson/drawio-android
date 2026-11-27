# draw.io for Android

A native Android app (Kotlin, no Electron) in this fork of draw.io desktop. For now it's a
**viewer**: it opens `.drawio` / `.xml` diagrams, `.drawio.png` and `.drawio.svg` files, with
pages, layers, zoom (toolbar or pinch) and links that open in your browser.

- **How it draws.** draw.io's own offline viewer, `viewer-static.min.js` (shapes embedded,
  the same script as the macOS Quick Look preview), runs in a WebView on a local page
  (`app/src/main/assets/viewer.html`). The app passes the diagram in through a small
  JavaScript bridge. The script comes from the `drawio` submodule at build time and isn't
  committed here.
- **Nothing leaves the phone.** The app has no INTERNET permission, and the WebView loads
  only the app's own assets.
- **Opening diagrams.** Tap **Open**, open one from a file manager or download, or share one
  to the app.

## Building

Needs JDK 17, the Android SDK and Gradle (the version in `gradle/wrapper/gradle-wrapper.properties`),
or Android Studio opened on `src/android`.

```bash
src/android/scripts/fetch-viewer.sh      # the viewer script, without the whole submodule
gradle -p src/android assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
gradle -p src/android testDebugUnitTest  # unit tests (DiagramFile)
gradle -p src/android connectedDebugAndroidTest  # UI tests, on a device or emulator
```

With the submodule checked out (`git submodule update --init drawio`), the build reads the
script from it instead. The app's version is draw.io's (`drawio/VERSION`).

## CI

`.github/workflows/android.yml` runs on changes to `src/android/`, the submodule pointer or
the workflow: build, unit tests, lint (report only), UI tests on an emulator, and the debug
APK as the **drawio-android-debug-apk** artifact to install.
