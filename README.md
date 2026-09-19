# You-Tube

Ultra-lightweight, client-only YouTube client for Android — **100% Kotlin + Jetpack Compose (Material 3)** with Media3 (ExoPlayer 1.5), NewPipeExtractor, OkHttp3, Coil, encrypted credential storage and a Media3 `MediaSessionService` for low-CPU background audio.

No backend. Everything (browse, search, watch, comments, likes, subscriptions) talks straight to YouTube's InnerTube endpoints from the device.

> **Unofficial, independent app.** Not affiliated with, endorsed by, or connected to YouTube/Google. It uses undocumented, rate-limited interfaces; Google can (and does) change or block them, and embedded Google sign-in may be refused by Google's security policies. Guest browsing, search and playback keep working when that happens.

---

## Build the APK from GitHub (no Android Studio needed)

1. Push this repository (or keep it as-is on GitHub).
2. Go to the **Actions** tab → **Build You-Tube APK** → run it (it also runs automatically on push/PR to `main`).
3. When the *Unit tests + debug/release APK* job is green, open its **Artifacts → `You-Tube-APK`** and download:
   - `app-debug.apk` — signed with a throwaway CI key, **directly installable** (enable “install from unknown sources”).
   - `app-release-unsigned.apk` — R8 full-mode minified + resource shrunk; sign it with your own keystore if you want to distribute.

To make CI produce a signed release APK, add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Meaning |
|---|---|
| `SIGNING_STORE_FILE` | Base64 **or** upload path of the keystore. Fastest path: upload the `.jks` as an Actions *artifact* in a `keystore` workflow and set this to its path, or base64-decode it in the workflow. |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

(The release `build.gradle.kts` activates the `release` signing config automatically when `SIGNING_STORE_FILE` is present, so no workflow edit is needed.)

---

## Package & identity

- **App name:** `You-Tube`
- **Application ID:** `io.github.sudantha.youtubelite` (namespace + signing identity)
- **Launcher icon:** original red rounded-rectangle play-button vector (`app/src/main/res/drawable/ic_launcher.xml`) — a geometric play mark, not a copy of the YouTube logo.
- **Debug build:** `io.github.sudantha.youtubelite.debug` (keeps the production build's signing identity clean).

## Modules

```
:app               Application (Compose UI, InnerTube API, player, session layer)
:baselineprofile   Macrobenchmark app: baseline profile generator + startup/scroll metrics
```

| Area | Stack |
|---|---|
| UI | Compose Material 3, strong skipping, `@Immutable` models, keyed lazy lists |
| Player | Media3 ExoPlayer 1.5.1, `SurfaceView` (`PlayerView surface_type="surface_view"`), `DefaultRenderersFactory.forceEnableMediaCodecAsynchronousQueueing()`, custom `LowRamLoadControl` |
| Streams | NewPipeExtractor `v0.26.5` over the shared OkHttp client (HTTP/2, pooled connections, DNS TTL cache); DASH muxed + separate video/audio merged with `MergingMediaSource`; quality selector with auto-fallback |
| Images | Coil 2.7 — `Precision.EXACT` to view size, `Bitmap.Config.HARDWARE`, 12% heap memory cache, 32 MB disk cache |
| Session | On-demand `WebView` (Google account, `FLAG_SECURE`), cookie allow-list, `EncryptedSharedPreferences` (AES256-GCM) storage, computed `SAPISIDHASH` Authorization header per request |
| Settings | DataStore (non-sensitive only) |
| Build | R8 **full mode**, resource shrinking, desugaring (`desugar_jdk_libs_nio`), Baseline Profiles merged from the benchmark module |

## Performance design (what actually ships)

- **Buffer:** `min 10s / max 25s / playback 1.5s / rebuffer 3s`, `targetBufferBytes = 20 MiB` with **size priority** (`prioritizeTimeOverSizeThresholds=false`) and **zero back-buffer**. Note: this caps the *sample buffer*, not codecs/surfaces/app RAM — total RSS is measured, not assumed (see below).
- **Decoders:** async MediaCodec queueing enabled; on stop/background the renderers are released via `player.stop()`; background is a true **audio-only MediaSource** (video track disabled), not a hidden video view.
- **Compose:** `@Immutable` data models, keyed + `contentType` lazy lists, shimmer drawn in `drawWithContent` (no composition invalidation), list threshold state via `remember { derivedStateOf }`, no fast-changing state read in composition.
- **Network:** one shared `OkHttpClient` (HTTP/2 pool 5, GZIP via `gzip()` default, 2-min DNS cache), bounded 8 MB response reads, dynamic JSON (no giant DTO trees) parsed straight into immutable UI models.
- **Startup:** lean activity, no splash, baseline profile + `profileinstaller` for ahead-of-time JIT; measured by macrobenchmark.

### Honesty box

- “120 FPS / < 120 MB / minimal battery” are **targets, not guarantees** — they depend on the device, video, codec support and network. Verify on your hardware with `Macrobenchmark` (included) + `am start -W`, `dumpsys meminfo`, `gfxinfo` and `dumpsys batterystats` (procedures in `docs/BENCHMARK.md`).
- Hardware-bitmap thumbnails reduce ART heap but move pixels to GPU; Coil's cache is hard-capped either way.
- YouTube throttles datacenter/abnormal clients; if `SAPISIDHASH` validation or the challenge JS fails, the app surfaces a clear error instead of hanging.

## Step-by-step implementation guide (where each requirement lives)

1. **Module build config + R8 rules + dependencies** → `app/build.gradle.kts`, `app/proguard-rules.pro`, `gradle/libs` via the version catalog-free explicit coordinates (pinned). Key flags: `isMinifyEnabled + R8 full mode` (`android.enableR8.fullMode=true` in `gradle.properties`), `isShrinkResources`, desugaring, `composeCompiler` reports.
2. **Low-RAM player setup (SurfaceView)** → `app/src/main/java/.../player/LowRamLoadControl.kt`, `PlaybackEngine.kt` (renderer factory, track selector, error-driven fallback to muxed ≤ current resolution), `res/layout/player_surface.xml` (`app:surface_type="surface_view"`).
3. **WebView cookie capture + SAPISIDHASH** → `auth/LoginActivity.kt` (on-demand WebView, allow-listed hosts, destroyed on capture), `auth/SessionStore.kt` (EncryptedSharedPreferences), `auth/SapisidHash.kt` (`SHA1("<ts> <SAPISID> https://www.youtube.com")`), injected per-request in `data/InnerTube.kt`.
4. **High-performance Home feed** → `ui/HomeFeed.kt` (keyed `items`, `contentType`, shimmer via `drawWithContent`, `derivedStateOf` scroll flag), `ui/MainViewModel.kt` (StateFlow, pagination via continuation tokens, dedupe + list cap).

## Tests

- **Unit (CI):** SAPISIDHASH digest + CRLF cookie rejection, InnerTube feed/comment parsing (classic + lockup renderers), bounded response stream, `LowRamLoadControl` constants.
- **Instrumented (CI emulator):** compose smoke test + macrobenchmark suite (cold start with/without baseline profile, feed scroll) and the baseline profile generator.

## Legal

Independent, unofficial, non-commercial client. Use at your own risk; do not hold YouTube/Google responsible for account actions performed through it. The icon is an original play-mark vector.
