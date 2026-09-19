# You-Tube (You-Tube Lite Client)

A production-structured, ultra-lightweight third-party YouTube client for Android.
100% Kotlin + Jetpack Compose (Material 3), Media3/ExoPlayer playback, NewPipeExtractor
stream extraction, and a direct InnerTube (youtubei) data layer — **client-side only,
zero backend**.

> **Note:** This is a personal/educational client. "You-Tube" naming and the play-button
> logo are user-provided branding decisions — YouTube is a trademark of Google LLC, and
> you are responsible for how you use this project (see *Legal* at the bottom).

---

## 1. Build the APK from GitHub (no local setup needed)

The repo ships a GitHub Actions workflow (`.github/workflows/build.yml`):

1. Push any commit to `main` (or open the **Actions** tab → **Build APK** → **Run workflow**).
2. When the run finishes, open it → **Artifacts** → download **`You-Tube-apk`**
   (contains `app-release.apk`, ready to sideload).
3. Tag a release (`v1.0.0`) and the APK is attached to a GitHub Release automatically.

Optional reproducible release signing via repo secrets: `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them the APK is signed with the
ephemeral debug key (installable; re-install over a previous build requires uninstall
first because the key changes).

Local builds (JDK 17 + Android SDK 35 + Gradle 8.10.2): `gradle :app:assembleRelease`.

## 2. App identity

| Property | Value |
|---|---|
| App name | **You-Tube** |
| Package / applicationId | `com.youtubelite.app` |
| minSdk / targetSdk | 26 (Android 8.0) / 35 |
| Icon | Adaptive vector: white rounded play triangle on red (Android 13 themed-icon aware) |

minSdk 26 is deliberate: `Bitmap.Config.HARDWARE` and adaptive icons work everywhere,
which keeps the image pipeline branch-free.

## 3. Architecture

```
ui/            Compose M3 screens (zero-recomposition discipline)
  nav/           hand-rolled stack navigation (no nav library)
  components/    VideoCard + draw-phase Shimmer skeletons
player/
  PlaybackService   MediaSessionService owning ONE ExoPlayer
  PlaybackHub       MediaController bridge, StateFlow-only API
  PlayerEngine      LowRamLoadControl + renderers factory
extractor/     NewPipeExtractor bridge (serialized CPU lane, Rhino deciphering)
innertube/     InnerTube client + string-indexed JSON parsers
auth/          EncryptedSharedPreferences session, SAPISIDHASH interceptor, WebView login
data/          Feeds / Comments / Actions repositories, DataStore prefs
```

Dependency graph is a plain Kotlin `object` (`AppGraph`) — no DI framework, no
reflection, no codegen. Everything is constructor-visible and R8-friendly.

### InnerTube layer
- WEB client context (`clientName: WEB`), `?prettyPrint=false`
- Home `FEwhat_to_watch`, Subscriptions `FEsubscriptions`, Library `FElibrary`,
  History `FEhistory`, Liked videos `VLLM`
- `next` (watch next + comment continuations), `search`, `like/like`,
  `like/dislike`, `like/removelike`, `subscription/subscribe|unsubscribe`,
  `comment/create_comment`
- Auth: cookies (`SID`, `HSID`, `SSID`, `APISID`, `SAPISID`, `LOGIN_INFO`, …) captured
  in-app via WebView, stored encrypted; header computed per request:
  `Authorization: SAPISIDHASH <ts>_<SHA1(ts + " " + SAPISID + " " + origin)>`

### Streams
NewPipeExtractor v0.26.x resolves formats (player JS deciphering included). The service
merges **video-only + audio-only** progressive URLs with `MergingMediaSource`
(144p→1080p adaptive), falls back to standard **muxed** streams on errors, and
auto-demotes quality after repeated rebuffers in **Auto** mode.

## 4. Performance engineering (the rules the code follows)

**Player (RAM/CPU/battery)**
- `SurfaceView` (`app:surface_type="surface_view"`) — overlays bypass GPU compositing
- `LowRamLoadControl`: min 10s / max 25s, 1.5s start (3s rebuffer),
  **targetBufferBytes hard-capped at 20 MB**, zero back-buffer
- Asynchronous `MediaCodecAdapter` queueing (Media3 default on API 23+), decoder
  fallback enabled, join time 0; `clearVideoSurface()` releases the video codec the
  moment playback goes background/audio-only
- One `OkHttpClient` per concern (API vs media), HTTP/2 + pooled connections

**Compose (frame pacing)**
- All models `@Immutable` + `compose-stability.conf` ⇒ skippable rows, stable lambdas
- Every `LazyColumn` item declares `key = { video.id }` and `contentType`
- Scroll-position flags derive through `derivedStateOf`; shimmer phase is read in the
  **draw phase** (`drawBehind`) — placeholders never recompose
- Fast values (playback position) are never read in composition: `PlayerView` renders
  its own timeline; `Scaffold`/nav state changes are the only recomposition sources

**Images / memory**
- Coil `ImageLoader` with `allowHardware(true)` (HARDWARE bitmaps ⇒ pixels in GPU,
  invisible to ART GC), memory cache capped at **20% of heap**, 128 MB disk cache,
  `Precision.EXACT` + fixed 320×180 decode per row, crossfade off

**Build**
- R8 **full mode**, resource shrinking, `resourceConfigurations = ["en"]`
- Baseline profile (`app/src/main/baseline-prof.txt`) + `androidx.profileinstaller`
- No DI framework, no WebView SDK, single module

## 5. Project layout

```
.github/workflows/build.yml     CI: build + upload APK (+ release on v* tags)
app/build.gradle.kts            compile flags, signing, dependencies
app/proguard-rules.pro          R8 full-mode rules (extractor/rhino/okhttp)
app/compose-stability.conf      Compose compiler stability config
app/src/main/baseline-prof.txt  startup baseline profile
app/src/main/java/com/youtubelite/app/...
```

## 6. Legal

This project is not affiliated with, endorsed, or sponsored by Google/YouTube.
NewPipeExtractor is GPL-3.0 — distributing an APK that links it makes this
project's binary subject to GPL-3.0 as well. Use of InnerTube endpoints is at your
own risk and must respect YouTube's Terms of Service.
