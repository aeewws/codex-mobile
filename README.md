# Codex Mobile

[![Android CI](https://github.com/aeewws/codex-mobile/actions/workflows/android-ci.yml/badge.svg)](https://github.com/aeewws/codex-mobile/actions/workflows/android-ci.yml)
[![MIT License](https://img.shields.io/github/license/aeewws/codex-mobile)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%209%2B-3DDC84)
![Status](https://img.shields.io/badge/status-active%20prototype-0A7EA4)

Codex Mobile turns a local Termux-based Codex backend into a touch-first Android product instead of a terminal-first workflow.

It is a mobile-first shell for a real local Codex runtime, not a fake chat wrapper and not a terminal screenshot in an app.

中文说明见 [README.zh-CN.md](README.zh-CN.md)。

Quick links: [Project brief](docs/project-brief.md) · [Setup](docs/setup.md) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md)

## Why This Exists

The terminal workflow is powerful, but it is not a good mobile product. Codex Mobile is an attempt to make local AI coding usable on a real Android phone without pretending the terminal is the final UI.

The project is focused on:

- chat-first mobile interaction
- thread recovery after reconnects or backgrounding
- local backend lifecycle management
- exposing model, reasoning, and permission controls without raw terminal UX

## What Makes It Different

- it connects to a real local Codex backend inside Termux instead of proxying to a fake mobile chat shell
- it is designed around thread recovery, reconnect handling, and long-session resilience on an actual phone
- it treats backend lifecycle and keepalive hardening as part of the product, not just an install note
- it is being shaped by daily use on a real device, so the tradeoffs are practical rather than hypothetical

## Current Status

Codex Mobile is an active prototype with real daily use behind it.

- Android app built with Jetpack Compose
- local backend runtime inside Termux
- root-assisted backend startup and keepalive hardening
- current UI is Chinese-first while the documentation is being expanded
- rooted Android is required today

This repository is the app project itself. It is not a one-click exported phone image.

## Screenshots

| Attachment Sheet | History | Settings |
| --- | --- | --- |
| ![Attachment sheet](docs/images/attachments.jpg) | ![History screen](docs/images/history.jpg) | ![Settings screen](docs/images/settings.jpg) |

## Current Features

- auto-start and reconnect to the local Codex backend
- mobile chat UI for real Codex threads
- history, archive, restore, rename, and delete flows
- model switching, reasoning level switching, permission modes, and Fast mode
- document attachment extraction for supported formats
- long-thread recovery improvements for unstable mobile/runtime conditions

## Repository Health

- Android CI runs on pull requests and on pushes to `main`
- issue templates and a pull request template are included for repeatable maintenance
- dependency updates are configured through Dependabot
- a basic security policy and maintainer ownership file are included

## Compatibility And Setup

Current expected environment:

- Android 9+ device
- Termux installed on-device
- community Codex CLI package inside Termux
- local authentication already completed in Termux
- root access for the current backend lifecycle design

Quick start and environment notes live in [docs/setup.md](docs/setup.md).

This project is best evaluated as "an Android product shell around a local coding runtime", not as a general-purpose Android app that can run without environment assumptions.

## Development

Build channels:

- `legacyDebug` keeps package compatibility with the working phone install line
- `ossDebug` uses the public `io.github.aeewws.codexmobile` application id for open-source distribution

Local development commands:

```bash
./gradlew testLegacyDebugUnitTest testOssDebugUnitTest
./gradlew assembleLegacyDebug
./gradlew assembleOssDebug
```

The repository also includes a GitHub Actions workflow that builds both debug channels on pushes and pull requests.

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
