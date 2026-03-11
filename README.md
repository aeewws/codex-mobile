# Codex Mobile

Codex Mobile is an Android app that turns a local Termux-based Codex backend into a touch-first mobile product, instead of a terminal-first workflow.

Current UI is Chinese-first because the project is being developed on a real daily-driver phone. That is acceptable for open source. Localization can be improved later without changing the core architecture.

中文说明见 [README.zh-CN.md](README.zh-CN.md)。

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

## Architecture

- UI: Jetpack Compose
- Backend runtime: community Codex CLI inside Termux
- Bridge: local app-to-backend RPC
- Runtime helpers: root-assisted backend lifecycle and recovery logic

## Project Status

This is an active prototype moving toward a desktop-grade Codex experience on Android.

Current priorities:

- bridge and state-machine stability
- long-thread recovery
- chat UX polish
- image input support

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
