# Background Stream

Keep YouTube audio playing with the screen off. Grant access once, add the Quick Settings tile, then tap it while a video plays — Background Stream takes over playback so you can lock your phone and keep listening.

## Demo

https://github.com/balasravatsal/background_stream/raw/main/docs/bg-stream_demo.mp4

<video controls width="720" playsinline>
  <source src="https://github.com/balasravatsal/background_stream/raw/main/docs/bg-stream_demo.mp4" type="video/mp4">
</video>

[Download / open demo](docs/bg-stream_demo.mp4)

## How it works

1. While YouTube is playing, the app reads the active media notification (title / URL / position).
2. You tap the **Background Stream** Quick Settings tile.
3. The app pauses YouTube, extracts an audio stream, and continues playback via a foreground media service — including with the screen off.

## Requirements

- Android 8.0+ (API 26)
- YouTube app with an actual video playing (not paused on the home feed)
- Permissions below granted once during setup

## Setup

1. Install the app (APK under [`share/`](share/) or build from source).
2. Open **Background Stream** and tap **Enable everything**, or grant each item:
   - **Notification access** — read the active YouTube title
   - **Playback notifications** — show the media playback notification
   - **Unrestricted battery** — survive with the screen off
   - **Quick Settings tile** — one-tap takeover from the shade
3. Play a YouTube video.
4. Pull down Quick Settings and tap **Background Stream**.

Tip: play a YouTube video, pull down Quick Settings, then tap Background Stream.

## Build

```bash
./gradlew assembleRelease
```

On Windows:

```bat
gradlew.bat assembleRelease
```

Release APK output: `app/build/outputs/apk/release/`  
A prebuilt share APK is also available in [`share/BackgroundStream-1.0.apk`](share/BackgroundStream-1.0.apk).

## Project layout

| Path | Role |
|------|------|
| `app/src/main/java/.../MainActivity.kt` | Setup UI and permission flow |
| `BackgroundStreamTileService.kt` | Quick Settings tile — capture & handoff |
| `YouTubeNotificationListener.kt` | Notification listener for YouTube media |
| `YouTubeTitleResolver.kt` | Resolve title, URL, position, speed |
| `StreamUtils.kt` | Extract audio stream |
| `AudioService.kt` | Media3 foreground playback service |

## Notes

This is a proof-of-concept. Behavior depends on YouTube’s notification format and device OEM battery policies. Use at your own risk.
