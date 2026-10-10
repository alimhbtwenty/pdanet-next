# Architecture & Reverse-Engineering Map: PdaNet-Next
> Produced via Replica Skills (`replica-recon`, `replica-architect`, `replica-diff`)

## 1. Deep Analysis of Legacy PdaNet+ (Recon)

| Legacy PdaNet+ Component | How It Worked in 2011 | Why It Breaks in 2026 | PdaNet-Next Modern Replacement |
|---|---|---|---|
| **USB Mode** | Required custom Windows `.sys` NDIS driver | Windows 10/11 requires WHQL signed drivers; old drivers fail or crash | **Standard ADB Port Forwarding + Local Proxy (`adb reverse`)**. Zero drivers needed. |
| **"Hide Tether Usage"** | Stripped certain HTTP user-agents | Carriers now use eBPF/Netfilter packet inspection for **TTL decrement** and TLS fingerprinting | **Automated TTL Mangling (`defaultcurhoplimit=65`) + Encrypted DoH** |
| **WiFi Direct** | Custom legacy WiFi P2P API | Android 12+ strict background location & Wi-Fi permissions break it | **Modern Android Local-Only Hotspot + QR Code pairing** |
| **License / Activation** | Server verification + FoxFi companion check | Apps delisted from Play Store; users locked out after device resets | **100% Free & Open Source (MIT)** |
| **UI & UX** | Android 2.3 Gingerbread design | Tiny touch targets, no diagnostics, unclear connection status | **Material 3 Design + Real-time Throughput Gauge + Diagnostics** |

---

## 2. Carrier Detection Mechanisms & Countermeasures

### A. TTL (Time to Live) Inspection
* **Carrier Logic:** When a laptop (default TTL 64 or 128) sends a packet through a tethered phone (acting as router), the phone decrements the TTL by 1 before sending it to the cellular tower. The tower sees `TTL = 63` or `127` instead of phone's native `64`. The carrier instantly flags this as tethered traffic.
* **PdaNet-Next Fix:**
  1. For proxy mode: Traffic originates from phone app's socket stack directly -> **Carrier sees native phone TTL (64)**.
  2. For raw forwarding mode: One-click script normalizes client TTL to 65, so after 1 hop through the phone, tower receives exactly 64.

### B. DNS Profiling
* **Carrier Logic:** Windows queries Microsoft NCSI (`dns.msftncsi.com`), Windows Update, and telemetry domains. Phones rarely query these unless tethered.
* **PdaNet-Next Fix:**
  - Built-in **DNS-over-HTTPS (DoH)** resolver directly in the proxy engine (using Cloudflare/Google HTTPS endpoints). All DNS queries are encrypted TLS traffic.

### C. TLS Client Hello (JA3 / JA4 Fingerprinting)
* **Carrier Logic:** Browser on Windows has different TLS cipher suites than Android's Conscrypt/BoringSSL.
* **PdaNet-Next Fix:**
  - Transparent HTTP/2 & HTTP/3 multiplexing proxy.
