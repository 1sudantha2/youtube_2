# Verifying the performance claims

The performance rules in this repo are *measurable*. Use these on your real device (USB debugging on, same video, same network) before/after any change.

## 1. Startup time (must be fast-open)

```bash
# Macrobenchmark (precise, needs :baselineprofile on a connected device)
./gradlew :baselineprofile:connectedDebugAndroidTest

# Quick manual
adb shell am start -W -n io.github.sudantha.youtubelite.debug/io.github.sudantha.youtubelite.MainActivity
```
Target: `TotalTime` < 900 ms cold on a mid-range device; compare `startupWithoutProfile` vs `startupWithProfile` to confirm the baseline profile is active.

## 2. Frame rate during playback (120 Hz target)

```bash
adb shell dumpsys gfxinfo io.github.sudantha.youtubelite.debug framestats > frames.txt
# play video 30 s, then:
adb shell dumpsys gfxinfo io.github.sudantha.youtubelite.debug | head -40
adb shell dumpsys gfxinfo io.github.sudantha.youtubelite.debug reset
```
`50% / 90% / 95% / 99% percentile frame time` should sit under the frame budget (16.7 ms @ 60 Hz, 8.3 ms @ 120 Hz). For list scrolling specifically, the scroll macrobenchmark in `StartupBenchmarks.kt` reports p50/p90/p95 total time.

## 3. RAM footprint (< 120 MB active playback)

```bash
# Start playback, wait for steady buffering, then:
adb shell dumpsys meminfo io.github.sudantha.youtubelite.debug
```
Read **TOTAL PSS**. The player contributes sample buffer (≤ 20 MiB by design) + decoder + surface; the rest is Compose + Coil (12% of heap) + runtime. If you exceed the budget, profile with `simpleperf` / Android Studio Profiler and check the biggest contributors — the buffer constants in `LowRamLoadControl.kt` are the first knob.

## 4. CPU + battery

```bash
adb shell dumpsys cpuinfo
adb shell dumpsys batterystats --charged | grep -A8 youtubelite
# or instrumented:
adb shell cmd battery unplug; ... usage ...; adb shell dumpsys batterystats --history
```
Keep an eye on `ExoPlayer`/`MediaCodec`/`Mali|Adreno` threads. Background audio-only mode (toggled in the account sheet) should drop the video decoder thread entirely — verify with `adb shell dumpsys media.player`.

## 5. Regenerating the baseline profile

```bash
./gradlew :baselineprofile:connectedDebugAndroidTest :baselineprofile:generateBaselineProfiles
# merged into app/build/outputs/baselineProfiles/<variant>/... for the release build
```
CI does this automatically on every push (benchmark job, non-blocking) and uploads the results to the `You-Tube-Benchmark` artifact.

## 6. A/B checklist for “smoother than before”

1. Same device, same video, 1080p → 720p → 360p.
2. Record: startup `TotalTime`, frame p99 (playback + scroll), PSS @ 60 s, battery % / 10 min idle-playback.
3. Re-run after the change; keep the profile + logs side by side.
