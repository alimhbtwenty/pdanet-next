# Task: Xray server configs managed from the PC (role `xray-config`)

Branch `antigravity/xray-config-2026-10-16` from `origin/integrate/v3.1`. Draft PR into
`ccr-65b98b7a-8b3t20`, title `[antigravity] xray-config`. Follow `AGENTS.md`. Design: ADR-0001
(slice 4). Engine contract: `core/.../xray/XrayEngine.kt`. The engine itself is built in parallel by
the `xray-bridge` task: **code only against the `XrayEngine` interface**; on Android use a
placeholder `NoXrayEngine` (start throws "Xray engine is not bundled in this build"); Claude swaps in
the real engine when both branches merge.

## Goal
The owner manages servers from the PC only: list, import links, choose one (or "Off = direct"),
delete, latency test, and edit the bypass domain list. The phone stores them.

## Do
1. **Core (JVM, testable):** interface `XrayProfileStore` (list, add, delete, select/selected id,
   bypass domains) and `XrayControl` (uses the store + engine): on select -> `engine.start(config)`,
   on "off" -> `engine.stop()`. Profiles get a stable id. `BypassPolicy` needs owner domains that
   can change at runtime: add that without breaking its tests.
2. **Control endpoints** in `RelayServer` (loopback only, like `/log`; JSON; never return config
   JSON or links):
   `GET /xray/profiles` -> `[{"id","name","selected"}]`; `POST /xray/import` (body = links text) ->
   added ids; `POST /xray/select` `{"id": "..."}` or `{"id": null}`; `POST /xray/delete` `{"id"}`;
   `POST /xray/delay` `{"id"}` -> `{"ms": n|null}`; `GET`/`POST /xray/bypass` -> domain list.
   `serveHttp` reads only GET today: add POST with `Content-Length` (cap 1 MB, 400 on bad input).
   Status JSON: add `"xrayProfile"` (selected name or null). Only add fields.
3. **Android:** `XrayProfileStore` implementation encrypted with an Android Keystore AES-GCM key in
   app-private storage; selection survives restarts (on service start, start the selected profile).
4. **PC (C#):** a "Servers" window (separate `Form`, opened from a button on `MainForm`; the main
   window is already 920 px tall, do not grow it): list with the selected one marked, Import from
   clipboard, Use, Off (direct), Delete (confirm), Test delay (one / all), Bypass domains editor
   (one per line, Save). Talk to the endpoints through the adb-forwarded port with a raw socket like
   `PhoneStatus.Fetch` (no system proxy). Show the selected server name in the main window.
5. **Tests:** Kotlin: store/control with a fake engine, every endpoint (including 403 for non-loopback
   where testable and 400 for bad JSON), no secret in any response or log. Windows: request
   building / response parsing as pure functions.

## Do not
- Never log or return config JSON, links, UUIDs, passwords or keys. Not in exceptions either.
- No proxy, no listener other than the existing relay port, do not touch the router packet path.
- Do not bump VERSION.

## Done
CI green, PR description lists endpoints and screens. This laptop has no gradle/.NET SDK: CI is
your compiler.
