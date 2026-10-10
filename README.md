# PdaNet-Next

> USB tethering that carriers can't detect. Android → Windows 11. No root, no drivers, no license keys.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3ddc84.svg)](#)
[![Windows](https://img.shields.io/badge/Windows-11-0078d4.svg)](#)

---

## What this does

Your phone shares its mobile data with your Windows PC over a USB cable. The carrier sees normal phone traffic — not hotspot traffic — so your unlimited data plan stays unlimited.

**How it's different from PdaNet+:**

| Problem with PdaNet+ | PdaNet-Next |
|---|---|
| Carriers detect tethering via TTL inspection | Proxy-based: traffic originates from phone stack, native TTL |
| Requires unsigned Windows driver (.exe from 2012) | Zero drivers — uses standard ADB port forwarding |
| License lost after phone reset | Free & open source forever |
| WiFi Direct breaks on Android 12+ | USB only — rock solid, fastest, lowest latency |
| 2011-era UI with no diagnostics | Modern dark dashboard with live speed & status |

---

## How it works

```
┌─────────────────────┐     USB Cable      ┌──────────────────────┐
│   Windows 11 PC     │◄──────────────────►│   Android Phone       │
│                     │                     │                       │
│  Browser / Apps     │                     │  PdaNet-Next Engine   │
│       │             │                     │       │               │
│       ▼             │  ADB reverse tunnel │       ▼               │
│  localhost:8080  ───┼────────────────────►│  HTTP Proxy :8080     │
│  (system proxy)     │                     │       │               │
│                     │                     │       ▼               │
│                     │                     │  Mobile Data (LTE/5G) │
│                     │                     │  TTL = 64 (native)    │
└─────────────────────┘                     └──────────────────────┘
                                                     │
                                                     ▼
                                              Carrier Tower
                                         (sees normal phone traffic)
```

**Why carriers can't detect it:** In normal tethering, your laptop sends packets with TTL=128 through the phone, which decrements it to 127. The carrier sees 127 and flags it. With PdaNet-Next, your laptop's traffic goes through a local proxy on the phone — the phone's own network stack generates fresh packets with its native TTL of 64. The carrier sees nothing unusual.

---

## Quick Start (2 minutes)

### Prerequisites
- Android phone with USB Debugging enabled
- Windows 11 PC with [ADB](https://developer.android.com/tools/releases/platform-tools) installed
- USB cable

### Step 1: Phone
Install the APK on your Android phone. Open it and tap **Start Engine**.

### Step 2: PC
Plug in USB cable. Open PowerShell **as Administrator** and run:

```powershell
# One-time setup — run the connect script:
.\windows\connect.ps1
```

Or manually:
```powershell
adb reverse tcp:8080 tcp:8080
netsh winhttp set proxy 127.0.0.1:8080
```

### Step 3: Done
Open your browser. You're online through your phone's unlimited data.

### Disconnect
```powershell
.\windows\disconnect.ps1
```

---

## Project Structure

```
pdanet-next/
├── android/                    # Android app (Kotlin)
│   └── app/src/main/
│       ├── java/.../           # ProxyEngine, VpnService, UI
│       ├── res/                # Material 3 layouts
│       └── AndroidManifest.xml
├── windows/                    # Windows companion scripts
│   ├── connect.ps1             # One-click connect
│   ├── disconnect.ps1          # One-click disconnect
│   └── setup-adb.ps1           # Install ADB if missing
├── dashboard/                  # Web-based status dashboard
│   └── index.html              # Real-time speed & diagnostics
├── ARCHITECTURE.md             # Technical deep-dive
└── README.md
```

---

## Building from Source

### Android APK
```bash
cd android
./gradlew assembleRelease
# APK at: app/build/outputs/apk/release/app-release.apk
```

### Windows
No build needed — PowerShell scripts run directly.

---

## License
MIT — free forever, no activation, no tracking.
