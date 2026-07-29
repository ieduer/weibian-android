# ADR: Weibian Android identity flow

Status: accepted for v1.1 direct release
Date: 2026-07-29

## Decision

Weibian remains a User Center client and does not create an App-local account
system. The v1.1 direct channel uses the native credential form already shipped
in v1.0, posting once over HTTPS directly to
`https://my.bdfz.net/api/login`. The password is not persisted, logged, copied
to command arguments, included in screenshots, or sent to any App-owned
Worker. Only the returned User Center session is retained, encrypted with an
Android Keystore key.

This is a deliberately narrow identity adapter, not a new identity authority.
Registration, password reset, profile management, account revocation and
upstream Seiue policy remain owned by User Center.

## Why this exception is accepted

The existing User Center one-time native handoff is hard-coded to the legacy
Companion client and does not expose a supported browser return flow for
standalone Apps. Reusing that client identity or extracting an HttpOnly browser
cookie would create a false client boundary. A WebView is also rejected for the
core product and is not introduced for login.

Until User Center provides a registered `weibian` Custom Tab/App Link handoff,
the native form is the smallest first-party path that preserves the existing
authority and avoids a second account database.

## Security constraints

- exact HTTPS User Center origin; no configurable runtime login host;
- user-initiated submission only, no automatic retry;
- no credential persistence, analytics, crash attachment or debug logging;
- session validation through `/api/me`;
- encrypted local session storage and explicit server logout plus local clear;
- authenticated production acceptance uses the canonical account once and
  records aggregate evidence only;
- a future browser handoff must register `weibian` as its own client and cannot
  reuse `bdfz-companion`.

## Revisit trigger

Replace this adapter with a system browser or Custom Tab handoff when User
Center publishes a standalone-App client contract with:

1. registered client key `weibian`;
2. claimed HTTPS App Link return URI;
3. 90-second one-time exchange code;
4. exact-client and replay rejection;
5. physical-device cold-start and revocation acceptance.
