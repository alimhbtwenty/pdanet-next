# PdaNet-Next

Share your Android phone's internet with a Windows PC over a USB cable, for **every app on the
PC**, TCP and UDP. If a VPN is on in the phone, the PC goes through that VPN too.
No root, no drivers to hunt down, no license.

## How it works

```
 Windows PC                                         Android phone
┌──────────────────────────────┐   USB cable    ┌──────────────────────────────┐
│ every app (browser, games,   │   (adb forward │ PdaNet-Next app              │
│ calls, CLI tools, Store...)  │    tcp:28080)  │                              │
│            │                 │                │  relay on 127.0.0.1:28080    │
│            ▼                 │                │   SOCKS5 + UDP-over-TCP      │
│  "PdaNet-Next" virtual       │                │   + HTTP proxy + /status     │
│  adapter (Wintun, sing-box)  │ ─────────────► │            │                 │
│  DNS answered with fake IPs, │                │            ▼                 │
│  names resolved on the phone │                │  phone's network: mobile     │
└──────────────────────────────┘                │  data / Wi-Fi / phone VPN    │
                                                └──────────────────────────────┘
```

- The phone app opens every outgoing connection itself. Android routes an app's sockets through an
  active VPN, so the PC's traffic uses the phone's VPN, and the carrier sees ordinary phone traffic
  with the phone's own TTL. (Android's built-in USB tethering does the opposite: it bypasses the VPN.)
- ADB only carries TCP, so UDP (DNS, QUIC, games, calls) travels inside TCP using sing-box's
  "UDP over TCP v2" format, which the relay implements.
- The Windows app adds a virtual network adapter with sing-box (Wintun inside, signed driver), routes
  everything into it and sends it to the relay. The adapter is removed when the app closes, and the PC's
  normal networking comes back, even after a crash.

## Use it

1. **Phone**: install `PdaNet-Next.apk` (the Windows app can also install it for you over USB).
   Turn on **USB debugging** (Settings > About phone > tap *Build number* 7 times > Developer options).
2. **PC**: extract `PdaNet-Next-Windows.zip` from the latest release and run `PdaNet-Next.exe`
   (it asks for administrator rights: the virtual adapter needs them).
3. Plug in the cable, allow the USB debugging prompt on the phone, press **Connect**.

The Windows app starts the phone app by itself. Close the window to keep it running in the tray.

| Mode | What it covers | Admin |
| --- | --- | --- |
| **Whole PC** (default) | all apps, TCP + UDP, DNS resolved on the phone | yes |
| **Browsers only** | apps that follow the Windows proxy setting | no adapter, settings restored on disconnect |

Not carried: ICMP (`ping`), because the phone app cannot send raw packets without root.

## Build and test

| What | Command |
| --- | --- |
| Relay unit tests (any OS, JDK 17+) | `gradle -p core test` |
| Windows logic tests (any OS, .NET 8 SDK) | `dotnet run --project windows/tests` |
| End-to-end: TUN adapter -> relay, TCP/UDP/DNS, 50 MB transfer (Linux, root) | `gradle -p core installDist && sudo SING_BOX=/path/to/sing-box tests/e2e/run-linux-e2e.sh` |
| Android APK | `gradle -p android :app:assembleRelease` |
| Windows exe | `dotnet build windows/PdaNetNext.csproj -c Release` |

CI (`.github/workflows/release.yml`) runs all of these, bundles `adb` and `sing-box` next to the exe,
and publishes a release from `main`.

## Layout

```
core/       relay engine, plain Kotlin/JVM (used by the Android app, unit-tested off-device)
android/    Android app: foreground service + UI
windows/    Windows app (C#, .NET Framework 4.8 built into Windows 10/11)
tests/e2e/  Linux network-namespace test of the whole bridge
replica/    clean-room recon of PdaNet+ (what it does, what we rebuilt)
docs/       health check of the previous version
```

## License

MIT.
