# Harmonica

Android app for putting a harmonica bar on a video and correcting the holes by hand.

Upload a video, or record video or audio. The first time a take is added, the phone runs the harmonica detector and draws the holes it heard. An audio-only take becomes a portrait video with a dark background. Tap a take to play it, or correct it frame by frame.

**Package:** `com.orangames.harmonica`

## Build

Open the project in Android Studio, or:

```bash
./gradlew assembleRelease
```

APK output: `app/build/outputs/apk/release/`.

## Releases

APKs are published from CI on pushes to `main`. Download builds from the repository **GitHub Releases** page.
