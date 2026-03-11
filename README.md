# Codex Mobile

Codex Mobile is an Android app that turns a local Termux-based Codex backend into a touch-first mobile product, instead of a terminal-first workflow.

Current UI is Chinese-first because the project is being developed on a real daily-driver phone. That is acceptable for open source. Localization can be improved later without changing the core architecture.

中文说明见 [README.zh-CN.md](README.zh-CN.md)。

## Screenshots

| Chat | Commands |
| --- | --- |
| ![Chat screen](docs/images/chat.png) | ![Commands screen](docs/images/commands.png) |

## What It Is

- Android app built with Jetpack Compose
- Uses a local Codex backend running inside Termux
- Keeps Termux as the runtime layer, not the primary user interface
- Focuses on product-style chat, thread recovery, and mobile interaction

## Current Features

- Auto-start and reconnect to the local Codex backend
- Mobile chat UI for real Codex threads
- History, archive, restore, and delete flows
- Model switching, reasoning level switching, permission modes, and Fast mode
- Long-thread recovery improvements for unstable mobile/runtime conditions

## Why This Exists

The default Codex terminal workflow is powerful, but it is not a good mobile product.

This project exists to make Codex usable on a real Android phone:

- open the app directly instead of living in a terminal
- keep conversations readable on a touchscreen
- preserve thread history and recovery flows
- bridge a real local backend instead of building a fake client

## Architecture

- UI: Jetpack Compose
- Backend runtime: community Codex CLI inside Termux
- Bridge: local app-to-backend RPC
- Runtime helpers: root-assisted backend lifecycle and recovery logic

## Current UX Direction

Codex Mobile is intentionally moving toward a desktop-style AI coding shell for Android:

- chat-first interaction
- mobile-friendly thread management
- persistent local backend lifecycle
- state recovery after backgrounding or reconnecting
- support for model, reasoning, and runtime controls without exposing raw terminal UX

## Setup Expectations

This repository is the app project, not a full one-click exported phone image.

To run it yourself, you are expected to prepare your own environment:

1. Android Studio and the Android SDK
2. A working Termux installation on-device
3. A Codex backend package inside Termux
4. Local authentication for the backend
5. Device-specific proxy and root configuration if your setup needs it

This project targets a rooted Android workflow because backend lifecycle, recovery, and low-level integration are part of the product design.

## Project Status

This is an active prototype moving toward a desktop-grade Codex experience on Android.

Current priorities:

- bridge and state-machine stability
- long-thread recovery
- chat UX polish
- image input support

## Current Limitations

- The UI is currently Chinese-first
- Backend behavior still depends on the Termux Codex package version
- Android- and root-specific setup is still part of the runtime story
- Image input support has been verified at the backend layer but is not yet exposed in the UI
- Long-thread and reconnect behavior is actively being hardened

## Repository Scope

This repository intentionally excludes private and device-specific runtime data.

Not included here:

- Termux auth files
- local Codex session history
- runtime backup archives
- device-specific proxy or root configuration
- private debugging artifacts

## Open Source Notes

This repository is meant to publish the app project itself, not a full exported phone environment.

If you want to run or adapt this project, expect to set up your own:

- Android build environment
- Termux runtime
- Codex backend package
- local authentication and proxy settings

## License

This project is released under the [MIT License](LICENSE).
