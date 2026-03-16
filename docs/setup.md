# Codex Mobile Setup Notes

Codex Mobile is currently designed for a rooted Android workflow with a local Termux-based Codex runtime.

## What You Need

- Android Studio and the Android SDK
- an Android 9+ device
- Termux installed on-device
- a working Codex CLI package inside Termux
- local authentication already completed in Termux
- root access if you want the current backend lifecycle flow to work as designed

## Project Reality

This repository contains the Android app project. It does not contain:

- your Termux auth state
- your local Codex session history
- your proxy setup
- a full exported phone environment

## High-Level Bring-Up

1. Clone the repository and open it in Android Studio.
2. Sync the Gradle project and install the required Android SDK components.
3. Prepare your device-side Termux environment.
4. Make sure Codex can run inside Termux before testing the Android app.
5. Build and install the app with Android Studio, `./gradlew assembleLegacyDebug`, or `./gradlew assembleOssDebug`.
6. Launch the app, then verify backend detection, reconnect behavior, and thread loading.

## Termux Runtime Notes

The repository includes [`tools/termux-codex-update.sh`](../tools/termux-codex-update.sh) as a helper for updating the community Termux Codex package and restarting the local app-server when needed.

The app expects a local websocket endpoint on `ws://127.0.0.1:8765`.

## Current Constraints

- rooted Android is part of the current product design
- backend behavior depends on the installed Codex package version inside Termux
- setup still includes device-specific decisions around power management, root tooling, and proxy configuration
- UI localization is still Chinese-first

## Troubleshooting Checklist

- confirm Termux is installed and launchable
- confirm root is available to the app when testing keepalive features
- confirm Codex auth exists inside Termux
- confirm the local app-server can listen on port `8765`
- capture screenshots or logs before opening an issue
