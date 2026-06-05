# Code signing

Release builds of both clients are code-signed in CI (`.github/workflows/release.yml`).

## Where the keys live

**Not in this repo.** Keys are kept offline by the maintainer and injected into
CI as encrypted **GitHub Actions secrets**. `.gitignore` blocks
`*.keystore`/`*.pfx`/`keystore.properties`/`local.properties` as a safety net.

| Secret | Used for |
|--------|----------|
| `ANDROID_KEYSTORE_BASE64` | base64 of the Android upload keystore |
| `ANDROID_KEYSTORE_PASSWORD` | keystore + key password |
| `ANDROID_KEY_ALIAS` | key alias inside the keystore |
| `WIN_CODESIGN_PFX_BASE64` | base64 of the Windows code-signing `.pfx` |
| `WIN_CODESIGN_PFX_PASSWORD` | password for that `.pfx` |

On a fork these are absent: the APK builds **unsigned** and the Windows binaries
are **left unsigned**, rather than failing.

## Android

`android/app/build.gradle.kts` resolves signing from two sources, in order:

1. **Local dev** — `android/local.properties` key `tsgbheater.signing.config`
   pointing at a keystore.properties file (the existing mechanism).
2. **CI** — the env vars `ANDROID_KEYSTORE_PATH` / `ANDROID_KEYSTORE_PASSWORD` /
   `ANDROID_KEY_ALIAS` (set from the secrets above).

## Windows (Authenticode)

CI signs `TsgbHeater.exe`/`.dll` in the published win-x64 output, timestamped so
signatures outlive the certificate. The `HcaloryTest` console harness is not part
of the product and is not built/signed.

> ⚠️ The current Windows certificate is **self-signed** — it proves integrity but
> SmartScreen will still warn end users with "Unknown Publisher". To ship
> publicly-trusted builds, obtain a CA-issued code-signing cert and repoint the
> `WIN_CODESIGN_PFX_*` secrets at it; no workflow changes needed.
