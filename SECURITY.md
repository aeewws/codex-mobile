# Security Policy

## Supported Status

Codex Mobile is an active prototype. Security fixes are handled on a best-effort basis.

The most actively maintained branch is:

- `main`

## Reporting A Vulnerability

If GitHub private vulnerability reporting is available for this repository, please use that first.

If it is not available:

1. Do not publish secrets, tokens, auth files, or exploit details in a public issue.
2. Open a minimal issue only for low-risk findings that can be discussed safely in public.
3. For anything sensitive, contact the maintainer through the GitHub profile before public disclosure.

## Scope Notes

This repository intentionally excludes private device runtime data such as:

- Termux auth state
- local session archives
- proxy configuration
- private debugging artifacts

When reporting a security problem, please avoid uploading any of the above.
