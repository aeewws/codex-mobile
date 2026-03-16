# Codex Mobile

Codex Mobile turns a local Termux-based Codex backend into a touch-first Android product instead of a terminal-first workflow.

中文说明见 [README.zh-CN.md](README.zh-CN.md)。

## Why This Exists

The terminal workflow is powerful, but it is not a good mobile product. Codex Mobile is an attempt to make local AI coding usable on a real Android phone without pretending the terminal is the final UI.

The project is focused on:

- chat-first mobile interaction
- thread recovery after reconnects or backgrounding
- local backend lifecycle management
- exposing model, reasoning, and permission controls without raw terminal UX

## Current Status

Codex Mobile is an active prototype with real daily use behind it.

- Android app built with Jetpack Compose
- local backend runtime inside Termux
- root-assisted backend startup and keepalive hardening
- current UI is Chinese-first while the documentation is being expanded
- rooted Android is required today

This repository is the app project itself. It is not a one-click exported phone image.

## Screenshots

| Chat | Commands |
| --- | --- |
| ![Chat screen](docs/images/chat.png) | ![Commands screen](docs/images/commands.png) |

## Current Features

- auto-start and reconnect to the local Codex backend
- mobile chat UI for real Codex threads
- history, archive, restore, rename, and delete flows
- model switching, reasoning level switching, permission modes, and Fast mode
- document attachment extraction for supported formats
- long-thread recovery improvements for unstable mobile/runtime conditions

## Compatibility And Setup

Current expected environment:

- Android 9+ device
- Termux installed on-device
- community Codex CLI package inside Termux
- local authentication already completed in Termux
- root access for the current backend lifecycle design

Quick start and environment notes live in [docs/setup.md](docs/setup.md).

## Development

Local development commands:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The repository also includes a GitHub Actions workflow that builds the debug APK on pushes and pull requests.

## Project Scope

This repository intentionally excludes private and device-specific runtime data.

Not included here:

- Termux auth files
- local Codex session history
- runtime backup archives
- device-specific proxy or root configuration
- private debugging artifacts

## Limitations

- the UI is currently Chinese-first
- backend behavior depends on the installed Termux Codex package version
- Android- and root-specific setup is still part of the runtime story
- image input has been validated at the backend layer but is not yet exposed in the main UI
- reconnect and long-thread stability are still being hardened

## Open Source Direction

Immediate repository priorities are tracked in [docs/roadmap.md](docs/roadmap.md).

If you want to contribute, start with [CONTRIBUTING.md](CONTRIBUTING.md).

## Notes

This project is not affiliated with OpenAI or Termux. It currently targets a community-packaged Codex CLI runtime inside Termux.

## License

This project is released under the [MIT License](LICENSE).
