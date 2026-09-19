# You-Tube

An ultra-lightweight YouTube client for Android, written in Kotlin + Jetpack Compose.

**No backend.** The app talks directly to YouTube's private `youtubei/v1` ("InnerTube") API
from the device, authenticates with cookies captured from an in-app WebView, and plays
streams through Media3 (ExoPlayer). There is no server to run, no API key to obtain, and no
third party in the data path.

---

## Table of contents

- [Memory & CPU budget](#memory--cpu-budget)
- [Architecture](#architecture)
- [Authentication](#authentication)
- [Playback pipeline](#playback-pipeline)
- [Building](#building)
- [CI/CD](#cicd)
- [Project layout](#project-layout)
- [Tests](#tests)
- [Optional: NewPipeExtractor](#optional-newpipeextractor)
- [Known limitations](#known-limitations)
- [Legal](#legal)

---

## Memory & CPU budget

Every number below is a deliberate ceiling enforced in code, not a target.

| Concern | Budget | Where it is enforced |
| --- | --- | --- |
| Media buffer window | **10 s – 25 s** | `player/LowRamLoadControl.kt` (`MIN_BUFFER_MS` / `MAX_BUFFER_MS`) |
| Media buffer memory | **20 MB hard cap** | `LowRamLoadControl.MAX_BUFFER_BYTES` → `setTargetBufferBytes` |
| Back buffer | **0 s** | `setBackBuffer(0, false)` — seeking back re-fetches instead of holding RAM |
| Codec extensions | **none loaded** | `EXTENSION_RENDERER_MODE_OFF` (no FFmpeg `.so` mapped) |
| Image cache | 10% of heap class, ≤ 20 MB, `HARDWARE` bitmaps | `ui/CoilConfig.kt` |
| Disk image cache | 60 MB | `CoilConfig` |
| HTTP connection pool | 5 idle sockets / 30 s | `InnerTubeClient.defaultHttp()` |
| Launcher icons | 0 PNG bytes | vector-only `mipmap/` + `mipmap-anydpi-v26/` |
| Native libs | one APK per ABI | `splits { abi { … } }` in `app/build.gradle.kts` |

`tools/verify_project.py` asserts these numbers, so a future edit that quietly raises the
buffer fails a check instead of shipping.

### Why `SurfaceView`, not `TextureView`

A `TextureView` renders video into the app's view-layer bitmap: every frame is copied
through app memory before the compositor sees it, which costs a full extra frame buffer per
video. `SurfaceView` gets its own hardware layer composited by SurfaceFlinger, so decoder
output can reach the display controller without that copy. The trade-off is that a
`SurfaceView` does not transform with the view hierarchy, so `player/VideoSurface.kt`
measures itself to the video's aspect ratio in `onMeasure`.

### Frame-time decisions in the feed

- Explicit `key` on every `LazyColumn` item (`state.videos[index].id`) — without keys,
  inserting a page shifts every following item and re-creates its state.
- `contentType` per item kind, so a video row is never recycled into a shelf and re-measured.
- `@Immutable` on every UI model and on `FeedUiState`, so item composables are *skipped*,
  not re-executed.
- The paging trigger reads scroll offsets inside `derivedStateOf`, so an ordinary scroll
  frame does not recompose the screen.
- Coil decodes to `HARDWARE` bitmaps (API 26+) with `allowRgb565`, putting thumbnails in GPU
  memory instead of the Java heap.
- No shimmer: the loading placeholder is static, because a shimmer animates a gradient
  across the whole placeholder every frame.
- The scrubber samples position at 4 Hz instead of reading it per frame.

---

## Architecture

```
MainActivity ─── AppNavHost (single NavHost, bottom bar)
   │
   ├─ HomeFeedScreen      FEwhat_to_watch
   ├─ FeedScreen          FEsubscriptions / FElibrary / Watch Later / search results
   ├─ SearchScreen        youtubei/v1/search
   ├─ PlayerScreen        VideoSurface (SurfaceView) + overlay + quality sheet + comments
   └─ LoginScreen         WebView → cookies → CookieStore
        │
        ▼
   FeedViewModel / WatchViewModel      (StateFlow<@Immutable UiState>)
        │
        ▼
   YoutubeRepository                   (feed ids, watch fallback, actions)
        │
        ├── InnerTubeClient           (HTTP + client context + SAPISIDHASH)
        ├── InnerTubeParser           (defensive JSON → domain models)
        └── SigCipher                 (base.js signature descrambling, WEB profile only)
        │
        ▼
   LowRamPlayer → MergingMediaSource(DashMediaSource video, DashMediaSource audio)
   PlaybackService (MediaSessionService) for background audio
```

Packages:

| Package | Responsibility |
| --- | --- |
| `data.innertube` | Transport, JSON accessors, parsing, SAPISIDHASH, signature descrambling |
| `data.auth` | WebView cookie extraction, session storage |
| `data.extractor` | Optional NewPipeExtractor bridge (reflection) |
| `domain` | `@Immutable` models only — no Android, no JSON |
| `player` | LoadControl, MPD builder, DASH merging, SurfaceView, player state |
| `service` | `MediaSessionService` for background playback |
| `ui` | Compose screens, ViewModels, navigation, Coil/theme config |
| `di` | Hand-rolled `ServiceLocator` (no DI framework) |

---

## Authentication

1. `ui/screens/LoginScreen.kt` opens `accounts.google.com/ServiceLogin` in a WebView. The
   user signs in on Google's own page.
2. `data/auth/WebViewCookieExtractor.kt` watches the cookie jar. As soon as `SID` **and**
   `SAPISID` are present it parses the header into a `SessionCookies` value.
3. `data/auth/CookieStore.kt` holds it in memory (with a private SharedPreferences
   fallback) and publishes it as a `StateFlow`.
4. On every authenticated call, `data/innertube/SapishHash.kt` derives:

   ```
   Authorization: SAPISIDHASH <epochSeconds>_<sha1(epochSeconds + " " + SAPISID + " " + origin)>
   ```

   plus the `Cookie` header. Because the hash is derived from a cookie already on the
   device, the raw `SAPISID` never appears in a header.

This is what makes subscriptions, library, like/dislike, subscribe and comment posting work
without OAuth tokens and without a server. Cookies are attached **only** to requests the app
sends to `youtube.com`, and `AndroidManifest.xml` sets `allowBackup="false"` with
`data_extraction_rules.xml` excluding prefs and databases from cloud backup.

---

## Playback pipeline

1. `InnerTubeClient.next(videoId)` returns the watch payload. The `ANDROID` client profile is
   tried first because its adaptive formats arrive **pre-signed** (no `signatureCipher`, so
   no `base.js` descrambling). If that payload has no usable formats, the `WEB` profile is
   retried.
2. `InnerTubeParser` extracts `adaptiveFormats` — each with `initRange` and `indexRange` —
   plus captions.
3. `DashManifestBuilder` synthesises **two** MPDs from those ranges: a video manifest with one
   `Representation` per resolution, and an audio manifest with the best audio stream. Both are
   inlined as RFC 2397 `data:` URIs, so nothing is fetched to "read a manifest".
4. `MergingDashMediaSource` wraps `MergingMediaSource(DashMediaSource(video), DashMediaSource(audio))`
   — the classic DASH video/audio merge. The two manifests are carried on the `MediaItem`'s
   `RequestMetadata` extras so both children stay reachable.
5. `LowRamPlayer` builds ExoPlayer with `LowRamLoadControl`, extension renderers off, and a
   `DefaultDataSource` that routes `data:` to Media3's base64 decoder and everything else to
   HTTP.
6. The quality sheet lists the heights the video actually offers (144p → 1080p, plus Auto) and
   applies a `TrackSelectionOverride` on the video track group — a gapless switch with no
   re-prepare and no new connection.
7. Background audio: `PlaybackService` is a `MediaSessionService` with
   `foregroundServiceType="mediaPlayback"`, the same 20 MB load control, and
   `setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)` so it never allocates a video decoder.

If no DASH-describable pair exists, the player falls back to the best progressive format via
`ProgressiveMediaSource`.

---

## Building

Requirements: JDK 17, Android SDK 35.

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # R8-minified, resources shrunk
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # lint
```

The Gradle wrapper jar is committed (`gradle/wrapper/gradle-wrapper.jar`), so a fresh clone
builds without installing Gradle first.

Release signing is automatic when these environment variables/secrets are present, and
silently skipped otherwise: `RELEASE_STORE_FILE`, `RELEASE_KEY_ALIAS`,
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD` (and `RELEASE_KEYSTORE_B64` in CI).

---

## CI/CD

`.github/workflows/build-apk.yml` runs on every push and pull request:

| Job | Does |
| --- | --- |
| `test` | `:app:testDebugUnitTest`, uploads the HTML report |
| `build` | assembles the APK, uploads one artifact per ABI + a universal APK; on a `v*` tag it creates a GitHub Release with the APKs attached |
| `lint` | `:app:lintDebug`, non-blocking |

Artifacts are named `You-Tube-<version>-<abi>-<buildType>.apk`. Gradle and the JDK are cached
between runs. Use **Actions → Build & Release APK → Run workflow** to build on demand and
choose `debug` or `release`.

---

## Project layout

```
app/
├── build.gradle.kts              deps, splits, R8, signing
├── proguard-rules.pro            keeps for Media3 / serialization / OkHttp / NewPipe
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── kotlin/com/ultra/youtube/app/
    │   │   ├── App.kt                       Application + Coil ImageLoaderFactory
    │   │   ├── MainActivity.kt              single activity
    │   │   ├── data/
    │   │   │   ├── YoutubeRepository.kt     feed ids, watch fallback, actions
    │   │   │   ├── auth/CookieStore.kt
    │   │   │   ├── auth/WebViewCookieExtractor.kt
    │   │   │   ├── extractor/NewPipeEngine.kt
    │   │   │   └── innertube/{InnerTubeClient,InnerTubeParser,JsonExt,SapishHash,SigCipher}.kt
    │   │   ├── di/ServiceLocator.kt
    │   │   ├── domain/Models.kt             @Immutable models
    │   │   ├── player/{LowRamLoadControl,LowRamPlayer,DashManifestBuilder,
    │   │   │           MergingDashMediaSource,PlayerState,VideoSurface}.kt
    │   │   ├── service/PlaybackService.kt
    │   │   └── ui/…                         screens, ViewModels, nav, theme, Coil
    │   └── res/
    │       ├── drawable/ic_youtube_logo.xml play-button vector (single path, punched notch)
    │       ├── mipmap*/                     vector-only launcher icons
    │       └── values/, xml/
    └── test/kotlin/…                        JVM unit tests
.github/workflows/build-apk.yml
tools/verify_project.py            static project verifier (see Tests)
gradle/libs.versions.toml          version catalog
```

`ic_youtube_logo.xml` is a single non-zero-fill path: the rounded-rect body is drawn
clockwise and the play triangle counter-clockwise, so the notch is transparent without a
second colour or a layer-list. Tint it with `android:tint`.

---

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

| Test | Covers |
| --- | --- |
| `SapishHashTest` | the auth header, pinned to independently computed SHA-1 vectors |
| `WebViewCookieExtractorTest` | cookie-header parsing edge cases |
| `InnerTubeParserTest` | feed/watch/comment parsing against recorded-shaped fixtures, incl. renderer nesting and `signatureCipher` |
| `DashManifestBuilderTest` | the synthesised MPD is parsed as real XML; ranges, ordering, escaping |
| `SigCipherTest` | `base.js` descrambler: parse + apply, verified by hand |
| `InnerTubeClientTest` | `createCommentParams` protobuf bytes, client profiles |
| `LowRamLoadControlTest` | the RAM contract (10–25 s, 20 MB) |

### Static verification

```bash
python3 tools/verify_project.py
```

This parses every artifact and cross-checks the references between them: Kotlin
lexical balance and package/directory agreement, XML well-formedness, every `@string` /
`@color` / `@drawable` / `@mipmap` / `@style` / `@xml` reference, manifest component → class
resolution, version-catalog accessors, the Gradle wrapper jar, and the workflow YAML. It
ships a self-test for its own lexer, because three of its checks were wrong before the
self-test caught them.

---

## Optional: NewPipeExtractor

`data/extractor/NewPipeEngine.kt` bridges to
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) **by reflection**, so it
is a second engine rather than a hard dependency:

- dependency absent (default) → `NewPipeEngine.isAvailable == false`, the app runs on the
  built-in InnerTube client;
- dependency present → NewPipe can be used as a fallback extractor via `StreamResolver`.

This is deliberate: NewPipeExtractor pulls in Rhino and jsoup, several megabytes of DEX and a
second HTTP stack, which contradicts the memory budget. To link it directly, uncomment the
dependency in `app/build.gradle.kts` (the NewPipe repo is already declared in
`settings.gradle.kts`) — the ProGuard keeps are already in place.

---

## Known limitations

- **InnerTube is private and changes without notice.** The client versions in
  `InnerTubeClient` (`ANDROID 19.29.37`, `WEB 2.20240701.00.00`) and the public InnerTube key
  will eventually need bumping. Parsing is defensive throughout — a renamed renderer yields an
  empty list, not a crash — but a reshuffled response can still empty a feed.
- **The `n` (throttling) parameter is not descrambled.** Some formats are speed-limited
  without it. The `ANDROID` profile is less affected than `WEB`.
- **`signatureCipher` descrambling** (`SigCipher`) handles the three primitives YouTube uses
  (reverse / splice / swap). If YouTube adds a fourth, `SigCipher.parse` returns `null` and
  the app falls back to pre-signed `ANDROID` formats.
- **Like/dislike/subscribe/comment params** are opaque blobs harvested from the watch
  response; if YouTube moves them, the action fails with a server message rather than
  silently doing nothing.
- **No downloads, no PiP, no cast.** Out of scope for this build.

---

## Legal

This project is an independent client. It is not affiliated with, endorsed by, or sponsored
by Google LLC or YouTube. "YouTube" and the play-button mark are trademarks of Google LLC;
the bundled vector is a functional re-drawing used for identification.

You are responsible for complying with YouTube's Terms of Service and applicable law in your
jurisdiction. Accessing YouTube through an unofficial client may violate those terms. Sign-in
uses your own Google account and your own cookies; nothing is transmitted to any third party,
and no credentials leave the device.

Use at your own risk.
