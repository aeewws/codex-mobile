# Codex Mobile Project Brief

This page is a public-facing summary of what Codex Mobile is, why it exists, and how to describe it consistently.

## One-Line Summary

Codex Mobile is a mobile-first Android shell for a real local Codex runtime in Termux.

## What It Is

Codex Mobile turns a local Termux-based Codex backend into a touch-first Android product instead of a terminal-first workflow.

It is not:

- a fake chat wrapper around a remote model
- a terminal screenshot packaged as an app
- a generic Android AI client with no runtime assumptions

It is:

- a real mobile UI around a local Codex runtime
- a bridge between Android product interaction and Termux-hosted backend execution
- a practical experiment in making local AI coding usable on an actual phone

## Why It Matters

Most local coding-agent workflows are terminal-native and desktop-oriented. That makes them powerful, but hard to use from a real mobile device.

Codex Mobile explores a different layer of the stack:

- mobile thread recovery and reconnect behavior
- product-style session history and lifecycle management
- model, reasoning, and permission controls exposed in a touch-first UI
- Android-specific runtime constraints such as backgrounding, keepalive, and instability handling

## Current Public Signals

- public GitHub repository
- MIT license
- CI running on pull requests and `main`
- release history
- maintainer docs such as `SECURITY.md`, `CODEOWNERS`, and Dependabot config
- setup notes and roadmap docs

## What Exists Today

- Android app built with Jetpack Compose
- local backend bridge to a Termux-hosted Codex runtime
- history, archive, restore, rename, and delete flows
- model switching, reasoning switching, permission modes, and Fast mode
- document attachment extraction for supported formats
- long-thread recovery work for unstable mobile scenarios

## What Makes The Project Non-Trivial

- it depends on a real local runtime instead of a mocked interface
- Android backgrounding and reconnect behavior are part of the product problem
- the backend lifecycle is part of the UX, not just an install detail
- it has to reconcile product design with rooted-device and local-runtime constraints

## Copyable Blurbs

### Short

Codex Mobile is a mobile-first Android shell for a real local Codex runtime in Termux, focused on thread recovery, session lifecycle, and touch-first interaction.

### Medium

Codex Mobile is an Android product shell around a real local Codex runtime hosted in Termux. Instead of treating the terminal as the final interface, it adds a touch-first mobile layer for chat, history, reconnect handling, model controls, and runtime lifecycle management on a real phone.

### Longer

Codex Mobile is an Android app that turns a local Termux-based Codex backend into a usable mobile product. The project focuses on the hard parts that appear when a coding-agent workflow leaves the desktop: thread recovery, reconnect handling, local backend lifecycle, mobile-friendly session history, and exposing runtime controls without raw terminal UX. It is not a fake chat wrapper; it is a mobile-first shell around a real local coding runtime.

## Suggested OSS Positioning

If you need to describe the project to reviewers, users, or maintainers, the safest consistent framing is:

1. Codex Mobile is a mobile-first shell around a real local runtime.
2. The project focuses on productizing local AI coding workflows for Android.
3. The hard problems are reliability, lifecycle, recovery, and mobile interaction, not just front-end rendering.

## Recent Progress

Recent repository work improved public maintainability signals:

- clearer README and Chinese README framing
- CI workflow improvements
- release publishing
- maintainer docs and security policy
- source path cleanup to match the real package name

## Useful Links

- Repository: [aeewws/codex-mobile](https://github.com/aeewws/codex-mobile)
- Release: [v0.2.0](https://github.com/aeewws/codex-mobile/releases/tag/v0.2.0)
- Setup: [docs/setup.md](./setup.md)
- Roadmap: [docs/roadmap.md](./roadmap.md)
