# You-Tube 🔴▶

**An unofficial, ultra-lightweight YouTube client for Android.** 100% Kotlin, Jetpack
Compose + Material 3, client-side only — no backend, no telemetry. Video extraction via
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor); feeds & engagement
via YouTube's internal InnerTube API.

> Not affiliated with YouTube or Google. Use your own Google account at your own risk.

---

## ✨ What's inside

| Area | Implementation |
|---|---|
| **UI** | Jetpack Compose + Material 3, 100% Kotlin, zero-XML screens |
| **Player** | AndroidX Media3 ExoPlayer · custom `LowRamLoadControl` (10–25 s buffer, ≤20 MB) · `SurfaceView` rendering (no TextureView) · async MediaCodec queueing · DASH video+audio merging (`MergingMediaSource`) · background audio via `MediaSessionService` (video track released when app is backgrounded) |
| **Extraction** | NewPipeExtractor for stream URLs + fallback muxed; InnerTube (`youtubei/v1`) for home / subscriptions / library / liked / watch-later / comments / search / suggestions / like / subscribe |
| **Auth** | In-app WebView sign-in → cookie capture (SID, HSID, SSID, APISID, SAPISID, LOGIN_INFO) → `SAPISIDHASH` Authorization header · `EncryptedSharedPreferences` |
| **Images** | Coil with `Precision.EXACT`, `Bitmap.Config.HARDWARE`, ≤20 % heap memory cache |
| **Performance** | R8 full mode + resource shrinking, Baseline Profiles, `@Immutable` models, keyed `LazyColumn`s, draw-phase shimmer, `derivedStateOf` paging triggers |

## 📱 Requirements

- Android 7.0+ (minSdk 24), target Android 15 (API 35)
- A Google account for subscriptions / likes / comments (optional — browsing works anonymously)

## 🚀 Build

### From GitHub (recommended)

The app builds itself — CI produces a **signed, ready-to-install APK** for every push and
every release tag:

1. Go to the **Actions** tab → **Android CI** → pick a run → download the
   `You-Tube-v<version>` artifact (pushes), or grab the APK from
   [**Releases**](../../releases) (tag `v1.0.0`).
2. Install the APK on your phone (`adb install You-Tube-v1.0.0.apk` or just open it).

### Locally

```bash
git clone https://github.com/1sudantha2/youtube_2
cd youtube_2
./gradlew :app:assembleRelease   # debug keystore signing for local testing is fine too
```

Requirements: JDK 17, Android SDK 35. The release keystore is committed at
`signing/you-tube-release.p12` (see `signing/README.md`) so GitHub Actions and local
builds produce identical signatures — override with `YT_KEYSTORE`/`YT_KEYSTORE_PASSWORD`/
`YT_KEY_ALIAS`/`YT_KEY_PASSWORD` environment variables if you want your own.

## 🧭 App map

- **Home** — YouTube-style feed with shimmer skeletons and infinite scroll
- **Subscriptions** — 2-column grid (needs sign-in)
- **You** — History · Liked videos · Watch later
- **Search** — debounced suggestions, videos + channels
- **Watch** — quality selector (144p–1080p DASH, auto-fallback to muxed on bad network),
  comments with replies & sorting, like/dislike/subscribe/share, related videos, autoplay
- **Settings** — default quality, autoplay, sign in/out

## 🔋 Performance engineering

- **Playback RAM < 120 MB**: 10–25 s / ≤20 MB buffer budget, video decoder released on
  `ON_STOP` (audio-only background), SurfaceFlinger-direct `SurfaceView` compositing.
- **Smooth 60/120 fps**: no GPU TextureView readback, hardware bitmaps for images,
  composition-scoped state (position poll touches only the progress bar),
  `key` + `contentType` recycling in every list.
- **Fast cold open**: Baseline Profiles + R8 full mode + no heavy init on the main thread.

## 📄 License

Unofficial fan project. All YouTube/Google trademarks belong to Google LLC.

---

## සිංහල (Sinhala)

### You-Tube — සැහැල්ම Android YouTube යෙදුම

මෙය YouTube සඳහා වූ **නිල නොවන, ඉතා සැහැල්ම** Android යෙදුමකි — 100% Kotlin,
Jetpack Compose, සර්වර් එකක් නොමැතිව (client-side only).

**විශේෂතා:**
- 🔋 **අඩු RAM / අඩු බැටරි / වේගවත් විවෘත වීම** — වීඩියෝ ධාරාව සඳහා 20 MB පමණක් වෙන් කරන
  `LowRamLoadControl`, `SurfaceView` හරහා සෘජුවම රූප රාමු ලබා ගැනීම, යෙදුම පසුබිමට
  ගිය විට වීඩියෝ decoder නිදහස් කර හඬ පමණක් ධාරාවට හරවයි.
- 🎬 **144p–1080p** තත්ත්ව තේරීම — DASH වීඩියෝ+හඬ ධාරා ඒකාබද්ධ කරයි; ජාලය දුර්වල
  විට ස්වයංක්‍රීයව සාමාන්‍ය ධාරාවට පත් වේ.
- 💬 අදහස් (comments) කියවීම/ලිවීම, 👍 කිරීම, 🔔 යොදා ගැනීම — Google ගිණුමකින්
  යෙදුම තුළම ආරක්ෂිතව පිවිසෙන්න (cookies උපාංගය තුළම සංකේතනය වේ).
- 🏠 Home පිටුව, 🔍 සෙවීම, 📺 Subscriptions, 📚 Library (History / Liked / Watch later).

**APK ලබා ගැනීම (GitHub සිටම):**
1. මෙම repository එකේ **Actions** ටැබයට යන්න → නවතම සාර්ථක run එක විවෘත කරන්න →
   **You-Tube-v1.0.0** artifact එක බාගන්න.
2. නැතහොත් **Releases** පිටුවෙන් `You-Tube-v1.0.0.apk` බාගෙන දුරකථනයට install කරන්න.

අවශ්‍ය වන්නේ Android 7.0 හෝ ඊට ඉහළ අනුවාදයක් පමණි.
