# Contributing to Codex Mobile

Thanks for taking a look at Codex Mobile.

## Before Opening A Pull Request

- open an issue first for large changes, architecture changes, or platform-specific behavior changes
- keep pull requests narrow and explain the user-facing impact
- avoid including private device data, auth files, or session history
- update docs when setup expectations or runtime behavior change

## Local Development

Recommended baseline:

1. Install Android Studio and the Android SDK.
2. Open this repository as a Gradle project.
3. Use a device or emulator that matches the current support direction.
4. If you are testing the Termux bridge, prepare your own Termux and Codex runtime.

Useful commands:

```bash
./gradlew testLegacyDebugUnitTest testOssDebugUnitTest
./gradlew assembleLegacyDebug
./gradlew assembleOssDebug
```

## Issues And Proposals

Good reports include:

- Android version and device model
- root stack if relevant, such as Magisk or KernelSU
- Termux package version
- Codex package version inside Termux
- exact steps to reproduce
- logs or screenshots when possible

## Scope

This repository is intentionally public-code-only. Do not commit:

- auth or token files
- local conversation archives
- private runtime backups
- device-specific proxy configuration
- personal debugging artifacts

## Review Expectations

Pull requests are easier to review when they include:

- a short summary of the change
- screenshots for UI changes
- testing notes
- any known limitations or follow-up work
