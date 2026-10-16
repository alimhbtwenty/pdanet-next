# Task: the Xray engine in the phone app (role `xray-bridge`)

Branch `antigravity/xray-bridge-2026-10-16` from `origin/integrate/v3.1`. Draft PR into
`ccr-65b98b7a-8b3t20`, title `[antigravity] xray-bridge`. Follow `AGENTS.md`. Design:
`docs/decisions/0001-xray-engine-and-iran-bypass.md` (slice 3). The contract you implement:
`core/src/main/kotlin/io/github/alimhbtwenty/pdanext/core/xray/XrayEngine.kt` (do not change its
signatures; if you must, explain why in the PR).

## Goal
The phone app embeds Xray-core and the router sends Route.TUNNEL flows through it, **in-process**:
Xray `core.Dial` / `core.DialUDP` with a config that has **outbounds only** (no inbounds, no
listener, no SOCKS/HTTP proxy, no TUN, no `VpnService`).

## Do
1. **Go module `xraybridge/`** (repo root), package `xraybridge`, gomobile-friendly API (only
   gomobile-supported types: string, bool, int/int64, []byte, error, exported structs/interfaces):
   - `Start(configJSON string) error`, `Stop()`, `IsRunning() bool`
   - `DialTCP(host string, port int, timeoutMs int) (*Conn, error)`; `Conn` has
     `Read(max int) ([]byte, error)` (return a new slice: gomobile copies `[]byte` arguments, so a
     Java buffer passed in would not be filled), `Write(b []byte) (int, error)`, `CloseWrite() error`,
     `Close() error`. EOF must be distinguishable (e.g. return `io.EOF` -> Java gets an exception
     with a known message, or return an empty slice + nil; document which).
   - `DialUDP(host string, port int) (*PacketConn, error)` with `Send([]byte) error`,
     `Receive(timeoutMs int) ([]byte, error)` (timeout distinguishable), `Close() error`.
   - `ImportLinks(text string) (string, error)`: JSON array of `{name, config}` built from share links
     / base64 subscriptions. Use **XTLS/libXray** (MIT) for link conversion if its API fits; else
     write the conversion. **No v2rayNG code** (GPL).
   - `MeasureDelay(configJSON, url string, timeoutMs int) (int64, error)`: separate temporary
     instance, does not touch the running one.
   - Android DNS gotcha: Go's resolver has no `/etc/resolv.conf` on Android, so a server given by
     host name may not resolve. Make server host names resolve (e.g. set Xray `dns` servers or a
     Go `net.DefaultResolver` with a UDP dialer to public resolvers, through the phone's network).
   - Pin versions in `go.mod`, commit `go.sum`. `go test ./...` with unit tests: link import
     (vless/vmess/trojan/ss with fake values, never real servers), and a Dial through a `freedom`
     outbound to a local test server.
2. **CI** (`.github/workflows/release.yml`): a job that runs `go test` and builds
   `xraybridge.aar` with gomobile (`-target=android/arm64,android/arm -androidapi 24`, Go and
   gomobile versions pinned), uploads it as an artifact; the Android APK job downloads it into the
   app (e.g. `android/app/libs/`) before building. Never commit the AAR.
3. **Android:** `XrayEngineImpl : XrayEngine` on top of the AAR. Its `tunnel` is one stable
   `Upstream`: `connectTcp` -> `DialTCP` wrapped as `StreamConnection` (Input/OutputStream adapters,
   `shutdownOutput` -> `CloseWrite`); `openUdp` -> `DialUDP` as `DatagramFlow` (throw
   `SocketTimeoutException` on receive timeout, as `UdpFlow` expects); `resolveDns` sends the raw
   query through the tunnel to `1.1.1.1:53` via `DialUDP`. While not running, connects throw
   `IOException` (no fallback to direct). Add `val isSelected: Boolean` (true after `start`, false
   after `stop`, stays true if the instance died) and wire `RelayService` with
   `tunnelProvider = { engine.tunnel.takeIf { engine.isSelected } }` (the `RelayServer` parameter
   exists). Do not add a config UI or storage: the `xray-config` task does that. Add the status
   field `"xrayRunning"` (only add fields).
4. Log tunnel connect failures with `LogLevel.WARN` and source `ROUTER` (never the config or address
   of the VPN server beyond host:port of the destination).

## Do not
- No inbound in any config, no local port, no proxy setting, no VpnService. Do not touch the PC app.
- No real server configs, UUIDs or keys anywhere (tests use obviously fake values).
- Do not bump VERSION, do not commit binaries.

## Done
CI green (including the new Go job and the APK with the AAR), PR description says how the APK size
changed and what was tested. This laptop has no Go, gradle or .NET SDK: CI is your compiler.
