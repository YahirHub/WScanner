# WScanner — Project knowledge

## What this is
Free, offline, local-network scanner for Android (alternative to Fing).  
Discovers devices via multi-layer scanning: ping (ICMP), mDNS (Bonjour), SSDP (UPnP/DLNA), NetBIOS, ARP, HTTP fingerprinting.  
No ads, no analytics, no location/contacts/storage permissions.

## Key directories
| Path | Purpose |
|------|---------|
| `app/src/main/java/com/thowilabs/wscanner/` | All Java source code |
| `app/src/main/res/layout/` | XML layouts (DrawerLayout, RecyclerView, FAB) |
| `app/src/main/res/values/` | Colors, strings, themes (dark cyber palette) |
| `app/src/main/res/drawable/` | Vector icons, gradients, card backgrounds |
| `app/src/main/res/menu/` | Navigation drawer menu |
| `app/src/main/assets/` | OUI vendor database (`oui_database.json`, 53k entries) |
| `contexto/` | Architecture decision records (in Spanish) |

## Source files
| File | Role |
|------|------|
| `MainActivity.java` | UI: DrawerLayout, RecyclerView, SwipeRefreshLayout, FAB, scan orchestration |
| `NetworkScanner.java` | Orchestrator: ping sweep → mDNS → SSDP → ARP → port scan → HTTP → NetBIOS |
| `MdnsDiscovery.java` | mDNS/DNS-SD (RFC 6762) — service discovery + reverse lookup |
| `SsdpDiscovery.java` | SSDP M-SEARCH over multicast |
| `NetBiosDiscovery.java` | NetBIOS NBNS (UDP port 137) |
| `ArpReader.java` | ARP table reader (14 fallback methods) |
| `VendorResolver.java` | OUI → vendor name lookup (53,371 entries) |
| `Device.java` | Device model (name, ip, mac, vendor, discoveryMethod, discoveryDetail) |
| `DeviceAdapter.java` | RecyclerView adapter with Iconics vector icons |

## Build & run
```bash
# Compile debug APK
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug

# Open project in Android Studio (Hedgehog+ recommended)
```

## Tech stack
| Layer | Choice |
|-------|--------|
| Language | Java 11 (no Kotlin in production code) |
| UI | Material Design 3, CoordinatorLayout, RecyclerView |
| Icons | Mikepenz Iconics 5.5 + Community Material Typeface 7.0.96.1 |
| Network | Pure `java.net` — MulticastSocket, DatagramSocket, HttpURLConnection |
| Protocols | ICMP, mDNS/DNS-SD, SSDP, NetBIOS (NBNS), HTTP |
| Build | Gradle KTS, AGP 9.2.1 |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 36 (Android 15) |

## Key conventions & gotchas
- **Java only** — all production code is Java 11, not Kotlin (only 2 test stubs are .kt)
- **No external network libraries** — mDNS, SSDP, NetBIOS all implemented from scratch with `java.net`
- **ARP is unreliable on Android 10+** — `ArpReader` tries 14 methods but usually returns empty
- **MulticastLock required** — `NetworkScanner` acquires/releases a `WifiManager.MulticastLock` for mDNS/SSDP
- **Progressive discovery** — devices appear in real-time; later phases (mDNS/SSDP) may update existing entries via `ipIndex` map
- **Dark theme only** — `values-night/themes.xml` is identical to `values/themes.xml` (always dark)
- **Color palette**: dark cyber — bg `#0D1117`, accent cyan `#00E5FF`, text `#E6EDF3`, green `#3FB950`
- **Device source priority**: mDNS (7) > SSDP (6) > NetBIOS (5) > OUI DB (4) > DNS (3) > HTTP (2) > Heurística (1)
- **Iconics AAR dependency** uses `@aar` classifier on community-material-typeface
- **Permissions**: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE — no location
