# VideoX 2.12 — Android Video & Audio Downloader

VideoX is a native Android app built with **Kotlin + Jetpack Compose + Material 3**,
inspired by the Seal app's workflow.
It downloads supported-site links with the **yt-dlp engine**
(`youtubedl-android` 0.18.1 + FFmpeg, the library Seal uses), and **direct
video file links** (MP4, WebM, MOV, M4V, 3GP) with Android `DownloadManager`.

- **App name:** VideoX
- **Package:** `com.videox.app`
- **Min. Android:** Android 10 (API 29)
- **Target / Compile SDK:** 34
- **Language:** Kotlin 2.0.20, AGP 8.5.2, Gradle 8.7, Java 17
- **Download folder:** `Downloads/VideoX` on the phone
- **APK size:** about 109 MB (arm64) — yt-dlp bundles Python + FFmpeg + native libraries.

> **Important — what VideoX does and does not do**
>
> VideoX supports only links that the source technically permits downloading.
> yt-dlp downloads what a site's public page makes available; it does
> **not** bypass DRM, paywalls, login restrictions or platform download
> protections, and it does **not** claim that every social-media URL can be
> downloaded. Sites change their protections often — YouTube and others
> sometimes block yt-dlp until a newer yt-dlp version ships. When a
> download is blocked, VideoX shows that error instead of pretending it
> worked. VideoX never collects passwords or private account information.

## Features

- **New app logo (v2.12):** the launcher icon was redesigned to match the
  premium identity — a white play triangle with a download arrow cut
  through it, on the app's purple gradient (#8F63FF → #6A3DF0). The icon
  is a vector adaptive icon (foreground + gradient background), so it
  stays sharp at every size and on round/square masks. The app name
  stays **VideoX**.
- **Premium redesign (v2.11):** the full UI was rebuilt on a premium design
  system — exact purple/dark palette (#101019 background, #8050FF primary),
  floating cards with soft shadows and subtle borders, gradient primary
  actions, segmented controls, status pills, file-type tiles, a refined
  type scale with 14–20dp rounded corners, and a floating bottom bar with
  a gradient active pill. UI layer only: the download engine, URL logic,
  settings, navigation and every feature work exactly as before. The Home
  screen privacy notice card was removed at the user's request (the same
  policy is documented in Settings > About).
- **Video-to-text extraction (v2.10):** **Extract text from video** saves a
  video's subtitles/captions as a clean `.txt` file in `Downloads/VideoX`
  without downloading the video itself. Manual subtitles and auto-generated
  captions are both fetched; the languages come from the Settings subtitle
  choice. Timestamps, cue numbers and formatting tags are stripped, and
  multi-line cues are joined. When a video has **no** subtitles, VideoX
  transcribes the speech on the phone instead (offline Vosk speech
  recognition, English or Hindi voice model downloaded once, about 40 MB,
  no audio leaves the phone), so text can be extracted from any video with
  spoken audio. A video with neither subtitles nor recognisable speech
  reports that honestly instead of creating an empty file. Verified
  against real yt-dlp 2026.10.26 (subtitle files, the no-subtitles case,
  SRT + WebVTT parsing, and 16 kHz mono WAV extraction for the
  transcriber) and against the real Vosk 0.3.75 AAR.
- **New in v2.9:** a warning before downloading on a
  metered connection (mobile data), an optional proxy setting for yt-dlp
  (validated — an invalid proxy is ignored, never passed on), subtitle
  embedding into downloaded videos (including auto-generated captions, with
  English / Hindi / All language choice), chapter markers embedded when the
  video has chapters, settings backup and restore
  (`Downloads/VideoX/videox-settings-backup.json`), and a cleaner Audio only
  screen that hides the video quality list. Subtitle and chapter flags were
  verified against real yt-dlp 2026.10.26 with ffprobe before shipping.
- **Self-updating yt-dlp engine (v2.2):** YouTube regularly changes its
  streaming and blocks old yt-dlp versions with HTTP 403. VideoX now
  updates its engine to the latest stable yt-dlp when the app starts, uses
  the Android player client for YouTube (which YouTube does not force into
  SABR streaming), and if a download still fails with 403 it updates the
  engine and retries once automatically. **Settings → Update yt-dlp engine**
  does the same manually and shows the current engine version.
- **HD quality selection (v2.1):** after pasting a link, tap **Formats** and
  VideoX shows every resolution the source genuinely offers — 144p, 240p,
  360p, 480p, 720p HD, 1080p Full HD, 1440p 2K, 2160p 4K — plus **Best Quality
  (Original)**. The chosen video stream is always merged with the best audio
  stream (FFmpeg), so HD downloads have sound. The app shows the real
  maximum ("Max available: 1080p Full HD") and never upscales or fakes a
  quality the source does not offer. Quality logic is covered by unit tests
  (`QualitySelectorTest`, run in CI with `testDebugUnitTest`).
- Dark theme with purple + blue accents (light theme in Settings)
- Home: URL input, **Paste URL** and **Download** buttons
- Audio-only downloads (MP3 / M4A / Opus), custom filenames
- Format list before download, download queue (1–3 at a time), clipboard detection
- Live progress, status, ETA and file size (where the server provides it)
- Downloads tab: All / Running / Completed / Failed filters
- Download history saved in Room, with **Clear history** (files are not deleted)
- Open / Share downloaded files, retry failed downloads, cancel running ones
- Settings: Wi-Fi-only toggle, dark theme, simultaneous downloads, download folder, storage summary, About
- URL validation with clear error messages
- Handles no-internet, failed and paused downloads, and shows the reason

## Project structure

```
VideoX/
├── README.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── gradlew
├── gradlew.bat
├── .github/workflows/build-apk.yml
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/videox/app/
        │   ├── MainActivity.kt
        │   ├── VideoXApp.kt
        │   ├── data/
        │   │   ├── AppDatabase.kt
        │   │   ├── DownloadDao.kt
        │   │   ├── DownloadItem.kt
        │   │   └── DownloadRepository.kt
        │   ├── download/
        │   │   ├── DownloadManagerHelper.kt
        │   │   ├── YtDlpEngine.kt          # yt-dlp engine (formats + download)
        │   │   ├── QualitySelector.kt      # HD quality logic (unit-tested)
        │   │   └── DownloadService.kt      # foreground service
        │   └── ui/
        │       ├── theme/Color.kt, Theme.kt, Type.kt
        │       ├── viewmodel/DownloaderViewModel.kt
        │       └── screens/HomeScreen.kt, DownloadsScreen.kt, SettingsScreen.kt
        └── res/
            ├── values/strings.xml, colors.xml, themes.xml
            ├── drawable/ic_launcher_foreground.xml, ic_launcher_background.xml
            ├── mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml
            └── xml/backup_rules.xml, data_extraction_rules.xml
```

## How to use VideoX (in the app)

1. Copy a direct video link, e.g. `https://example.com/video.mp4`
2. Open VideoX, tap **Paste URL**, then **Download**.
3. Watch progress on Home. Completed videos are in the **Downloads** tab
   and in the phone's **Files > Downloads > VideoX**.
4. Tap a completed video to play it.

## Build the APK using only an Android phone — step by step

You do **not** need a computer. GitHub builds the APK for you.

### Step 1 — Get a GitHub account

1. On your phone, open Chrome and go to https://github.com
2. Sign up / sign in. A free account is enough.

### Step 2 — Create a repository

1. Tap **+** (top right) → **New repository**.
2. Repository name: `VideoX`
3. Choose **Public** (or Private — Actions works on both for this workflow).
4. Tap **Create repository**. Do **not** add a README, .gitignore or licence here — the ZIP already has them.

### Step 3 — Upload the project ZIP from your phone

1. Download `VideoX.zip` from this chat to your phone.
2. Unzip it if needed — you need the **folder** `VideoX` with files inside.
   - On many phones the Files app can unzip. If not, install the free
     **ZArchiver** app from Google Play, open the ZIP in it and Extract.
3. On GitHub, open your new `VideoX` repository → tap **Add file → Upload files**.
4. Tap **Choose your files**, select **all files and folders inside** the
   `VideoX` folder — including the hidden `.github` folder if your file
   picker shows it. If `.github` is not visible in the picker:
   - Upload everything else first and commit, then on GitHub tap
     **Add file → Create new file**, type `.github/workflows/build-apk.yml`
     as the name, paste the contents of that file from the ZIP, and commit.
   - The workflow file **must** be at exactly
     `.github/workflows/build-apk.yml` or no build will run.
5. Tap **Commit changes**. Wait for the upload to finish.

> Tip: uploading from the GitHub **mobile app** does not support full-folder
> upload well. Use **Chrome in Desktop mode** if the picker hides folders:
> Chrome menu → Desktop site, then upload again.

### Step 4 — Let GitHub Actions build it

1. After the push to `main`, GitHub automatically starts the workflow
   **“Build VideoX APK”**.
2. Go to the **Actions** tab in your repository. You should see a run
   named “Build VideoX APK” / “Build debug APK”.
3. Wait 3–8 minutes for it to turn green (✓). If it is red, tap the run,
   tap the failed step, and read the error log.

**Manual build:** Actions tab → **Build VideoX APK** (left side) →
**Run workflow** → choose `main` → **Run workflow**.

### Step 5 — Download the APK artifact

1. In the green (successful) run, scroll down to **Artifacts**.
2. Tap **app-debug-apk**. A ZIP file downloads to your phone.
3. Unzip that artifact ZIP (Files app or ZArchiver). Inside are
   **`app-arm64-v8a-debug.apk`** (most phones, including POCO X6 Pro 5G)
   and `app-armeabi-v7a-debug.apk` (older phones).

### Step 6 — Install the APK on Android

1. Open **Files** → **Downloads** → tap `app-debug.apk`.
2. If Android asks, allow **Install unknown apps** for the app you are
   installing from (Chrome / Files). This is normal for APKs not from
   Google Play.
3. Tap **Install**, then **Open**.
4. On first run, allow the notification permission — DownloadManager
   uses it to tell you when a download finishes. No storage permission
   is needed on Android 10+ for `Downloads/VideoX`.

## Troubleshooting

| Problem | Fix |
|---|---|
| No run in Actions | Check the workflow is at `.github/workflows/build-apk.yml` on the `main` branch, and that Actions is enabled in Settings → Actions → General. |
| Build fails: `gradlew: Permission denied` | The workflow already runs `chmod +x gradlew`. If you edited the file, re-upload the original `gradlew`. |
| Build fails: SDK / Java error | Do not change Java 17 or AGP 8.5.2 in the workflow / Gradle files. Those versions are matched. |
| “Only direct video file links are supported” | The link is a web page, not a file ending `.mp4` / `.webm` / `.mov` / `.m4v` / `.3gp`. Use the direct file link from a source that permits downloading. |
| Download failed / paused | Check internet, storage space, and that the server allows the download. Tap the same link again from Home to retry. |
| Can't find the video | Files app → Downloads → VideoX. Clearing history in VideoX does not delete files. |

## Build status — please read

This project was **not built or tested on a local device in this chat**.
The first real build happens in GitHub Actions when you push to `main`.
If that first build is green and the APK installs, the project is verified
for your phone. Do not treat this README as proof that an APK was built.

## Licence

For personal use. Only download videos you have the right to download.
