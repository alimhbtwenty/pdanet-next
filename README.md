# PdaNet-Next 🚀

> **Modern, carrier-bypass tethering engine and companion dashboard.**
> Solves legacy PdaNet+ pain points: automated TTL matching, DPI traffic masking, transparent proxying, zero proprietary desktop drivers needed, and modern Material 3 UI.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Windows%20%7C%20macOS%20%7C%20Linux-green.svg)](#cross-platform)
[![No Root Required](https://img.shields.io/badge/Root-Not%20Required-brightgreen.svg)](#architecture)

---

## 📌 Why PdaNet-Next? (The Problem with Legacy PdaNet+)

PdaNet+ was great in 2011, but users today face major frustrations:
1. **Broken Carrier Bypass:** Carriers now inspect packet **TTL (Time to Live)**, DNS leaks, and SNI/DPI signatures. PdaNet's "Hide Tether Usage" fails frequently.
2. **Obsolete Proprietary Drivers:** PdaNet requires installing outdated, unsigned Windows `.exe` desktop drivers from the 2010s that trigger antivirus warnings.
3. **Android 12/13/14+ WiFi Direct Breakage:** OS permission changes frequently disconnect WiFi Direct mode.
4. **License Lockout:** Users report losing their paid license after phone resets or Play Store removals (FoxFi).
5. **Dated UI:** A Gingerbread/Holo era interface with zero diagnostics.

**PdaNet-Next fixes all of these.**

---

## ✨ Features

- 🛡️ **Automated TTL Normalization (`TTL = 64/65`):** Matches phone outgoing packet hops so carrier DPI engines cannot identify tethered laptop/console traffic.
- 🔌 **Driverless USB & WiFi Tethering:** Uses standard local HTTP/SOCKS5 proxy and `adb reverse` / WebSockets. No custom kernel driver required on Windows, Mac, or Linux.
- 🔒 **Encrypted DNS (DoH / DoT):** Routes all client DNS queries over Cloudflare/Google DNS-over-HTTPS to prevent carrier DNS snooping.
- ⚡ **Zero-Root VPN Service Mode:** Uses Android `VpnService` locally to intercept and forward non-local traffic through the phone's mobile data interface without requiring root.
- 📱 **Modern Material 3 Dashboard:** Real-time bandwidth monitor, active client list, TTL diagnostics, and quick toggle.
- 🔓 **100% Free & Open Source:** No activation keys, no device locks.

---

## 🏗️ Architecture

```
[ Client Laptop / PC / Mac ]
            │
            ▼ (Standard SOCKS5 / HTTP Proxy :8080 or ADB Tunnel)
[ PdaNet-Next Engine (Android) ]
            │
            ├─► 1. Local VpnService / Proxy Server
            ├─► 2. TTL Mangling & SNI Sanitization
            ├─► 3. DoH (DNS over HTTPS) Resolver
            │
            ▼
[ Mobile Carrier (T-Mobile / Verizon / AT&T / MCI / Irancell) ]
(Sees only normal on-device cellular HTTPS traffic)
```

---

## 🚀 Quick Start

### 1. Android App
Install the APK or run via web companion.
Tap **"Start Proxy Engine"** to launch the local proxy on port `8080` (HTTP) and `1080` (SOCKS5).

### 2. Client Setup (Windows / macOS / Linux)

#### Method A: USB Tether (Fastest, Lowest Latency)
Enable USB Debugging and run the cross-platform client:
```bash
# Connect phone via USB
adb reverse tcp:8080 tcp:8080
# Now point your browser/system proxy to 127.0.0.1:8080
```

#### Method B: WiFi Hotspot / Direct
Connect your PC to the phone's WiFi hotspot, and set Proxy:
- **Server:** `<Phone IP shown in app, e.g. 192.168.43.1>`
- **Port:** `8080`

#### Windows PowerShell TTL Auto-Fix (One-Click):
```powershell
# Set outgoing Windows TTL to 65 (matches mobile hop)
netsh int ipv4 set glob defaultcurhoplimit=65
netsh int ipv6 set glob defaultcurhoplimit=65
```

---

## 🛠️ Tech Stack
- **Android Engine:** Kotlin, Coroutines, Android VpnService, Netty/Ktor SOCKS5 core
- **Web & Desktop Companion:** HTML5, Modern Canvas, ServiceWorker, WebSockets
- **Protocols:** SOCKS5 (RFC 1928), HTTP CONNECT tunneling, DNS-over-HTTPS (RFC 8484)

---

## 📄 License
MIT License. Free for everyone forever.
