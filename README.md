# Harmonica

Android app for putting a harmonica bar on a video and correcting the holes by hand.

Upload a video, or record video or audio. An audio-only take becomes a portrait video with a dark background. The harmonica bar is drawn on the result. Tap a take to play it, or correct it frame by frame. Every change is saved, and the overlaid video is rewritten in the background.

Automatic note detection is not in this version.

**Package:** `com.orangames.harmonica`

## Build

Open the project in Android Studio, or:

```bash
./gradlew assembleRelease
```

APK output: `app/build/outputs/apk/release/`.

## Releases

APKs are published from CI on pushes to `main`. Download builds from the repository **GitHub Releases** page.
