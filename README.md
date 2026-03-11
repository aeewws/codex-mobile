# Codex Mobile

Codex Mobile is an Android app that turns a local Termux-based Codex backend into a touch-friendly mobile experience.

## What it does

- Starts and connects to a local `codex app-server`
- Provides a mobile chat UI for Codex
- Supports thread history, archived sessions, model switching, permission modes, and Fast mode
- Keeps Termux as the backend runtime instead of the primary user interface

## Architecture

- Android UI: Jetpack Compose
- Local backend: community Codex CLI running in Termux
- Transport: local RPC over `ws://127.0.0.1:8765`
- Runtime helpers: root-assisted backend start/stop and recovery logic

## Status

This project is an active prototype focused on making Codex usable on Android with a product-style interface instead of a terminal-first flow.

Current work is focused on:

- bridge and state-machine stability
- long-thread recovery
- chat UX polish
- image input support

## Notes

- This repository intentionally does not include private runtime backups, auth files, or device-specific Termux data.
- Mobile runtime snapshots and local debugging artifacts are kept outside the repository.
