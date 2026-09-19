# Release signing

The release keystore used by GitHub Actions (and reproducible local builds) lives in this
directory and is committed intentionally, so that **CI can build a signed, installable APK
straight from GitHub** — one of the project's core deliverables.

| | |
|---|---|
| File | `you-tube-release.p12` (PKCS#12) |
| Key alias | `you-tube` |
| Store & key password | `you-tube-release` |
| Key | RSA 2048, CN=You-Tube |
| Validity | 30 years (2025 → 2055) |

## Overriding

If you fork the project and want your own key, export any of these before building:

```bash
export YT_KEYSTORE=/path/to/your.p12
export YT_KEYSTORE_PASSWORD=...
export YT_KEY_ALIAS=...
export YT_KEY_PASSWORD=...
```

`app/build.gradle.kts` falls back to the committed keystore when the environment
variables are absent, so `./gradlew assembleRelease` always works out of the box.

> ⚠️ Because this key is public, anyone can sign updates that your device will accept as
> upgrades of this app. For personal use that's fine (verify by checking the download
> comes from this repository); for a private build, use your own keystore via the
> environment variables above.
