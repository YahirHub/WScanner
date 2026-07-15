# WScanner — Project knowledge

## What this is
Free, offline, local-network scanner for Android (alternative to Fing).  
Discovers devices via multi-layer scanning: ping (ICMP), mDNS (Bonjour), SSDP (UPnP/DLNA), NetBIOS, ARP, HTTP fingerprinting.  
Includes tools: Speed Test, Traceroute, Wake-on-LAN, Scan History.  
No ads, no analytics, no location/contacts/storage permissions.

## Key directories
| Path | Purpose |
|------|---------|
| `app/src/main/java/com/thowilabs/wscanner/` | All Java source code (14 active classes) |
| `app/src/main/res/layout/` | XML layouts (phone + tablet qualifiers) — 11 files |
| `app/src/main/res/layout-sw600dp/` | Tablet 7" alternative layouts |
| `app/src/main/res/layout-w840dp/` | Tablet 10" / landscape alternative layouts |
| `app/src/main/res/anim/` | Animation XML resources (8 files) |
| `app/src/main/res/values/` | Colors, strings, themes, dimens |
| `app/src/main/res/values-sw600dp/` | Dimension overrides for 7" tablets |
| `app/src/main/res/values-w840dp/` | Dimension overrides for 10" tablets |
| `app/src/main/res/drawable/` | Vector icons, gradients, card backgrounds (20 files) |
| `app/src/main/res/menu/` | Navigation drawer + toolbar search menu (2 files) |
| `app/src/main/assets/` | OUI vendor database (`oui_database.json`, 53k entries) |
| `contexto/` | Architecture decision records (Spanish) |

## Source files (14 active)
| File | Role |
|------|------|
| `MainActivity.java` | UI, navigation, scan orchestration, transitions, search, device detail, ping, haptics, FAB long-press monitor toggle, pulse/shimmer animations |
| `NetworkScanner.java` | Orchestrator: ping sweep → mDNS → SSDP → ARP → port scan (15 ports) → HTTP → NetBIOS |
| `MdnsDiscovery.java` | mDNS/DNS-SD (RFC 6762) — service discovery + reverse lookup, manual DNS binary construction |
| `SsdpDiscovery.java` | SSDP M-SEARCH over multicast with scoring system for best device names |
| `NetBiosDiscovery.java` | NetBIOS NBNS (UDP port 137) with half-ASCII encoding |
| `VendorResolver.java` | OUI → vendor name lookup (53,371 entries from JSON) |
| `Device.java` | Device model (name, ip, mac, vendor, discoveryMethod, discoveryDetail, ttl, openPorts, serviceNames) |
| `DeviceAdapter.java` | RecyclerView adapter + Filterable + Iconics + ripple + StateListAnimator + staggered reveal |
| `HapticUtil.java` | Haptic feedback cross-API (legacy Vibrator 24-25, performHapticFeedback 26+) |
| `SpeedometerGauge.java` | Custom View: modern minimalist arc gauge (no needle, gradient cyan glow, central text) |
| `SpeedTestTool.java` | HTTP download/upload speed test using public CDN URLs (Tele2, ThinkBroadband) |
| `TracerouteTool.java` | UDP-based traceroute with hop-by-hop latency measurement |
| `WakeOnLanTool.java` | Wake-on-LAN magic packet sender |
| `ScanHistory.java` | Persistent scan history storage |

## Build & run
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"  # Windows Git Bash
./gradlew :app:assembleDebug       # Compile debug APK
./gradlew :app:installDebug        # Install on device
./gradlew :app:lintDebug           # Run lint checks
adb logcat WScanner.*:* *:S        # Diagnostic logs
```

## Tech stack
| Layer | Choice |
|-------|--------|
| Language | Java 11 (no Kotlin in production code) |
| UI | XML Views: ConstraintLayout, CoordinatorLayout, RecyclerView, Material Components |
| Icons | Mikepenz Iconics 5.5 + Community Material Typeface 7.0.96.1 |
| Network | Pure `java.net` — MulticastSocket, DatagramSocket, HttpURLConnection |
| Protocols | ICMP, mDNS/DNS-SD, SSDP, NetBIOS (NBNS), HTTP, UDP traceroute |
| Build | Gradle KTS, AGP 9.2.1 |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 36 (Android 15) |

## Key conventions & gotchas
- **Java only** — all production code is Java 11, not Kotlin
- **No Fragments** — single Activity with embedded views + visibility toggling
- **No external network libraries** — mDNS, SSDP, NetBIOS, traceroute implemented from scratch
- **ARP unreliable on Android 10+** — 14 methods tried, always returns empty due to SELinux
- **MulticastLock required** — acquired at scan start, released at end
- **Progressive discovery** — devices appear in real-time; later phases update existing entries via `ipIndex` map
- **Dark theme only** — `values-night/themes.xml` identical to day (always dark)
- **Device source priority**: mDNS (7) > SSDP (6) > NetBIOS (5) > OUI DB (4) > DNS (3) > HTTP (2) > Heurística (1)
- **15 probed ports**: 80, 443, 22, 445, 8080, 23, 21, 554, 1883, 53, 3389, 5900, 5000, 5353, 9100
- **Responsive layouts**: `layout-sw600dp` (7" tablet, 40/60 split), `layout-w840dp` (10", 35/65 split)
- **Screen transitions**: `TransitionManager.beginDelayedTransition()` with Fade + Slide
- **Permissions**: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE
- **Monitor mode**: activated via long-press on FAB (vibration + toast feedback)
- **Search icon**: hidden via `onPrepareOptionsMenu` on Speed Test, About, and Device Detail screens
- **Sort chips**: removed from UI — sort criteria now inline in each device card

## Files intentionally removed
- `ArpReader.java` — duplicate code, functionality in NetworkScanner
- `SwipeRefreshLayout` dependency and layout — replaced by FAB-only scan trigger
- Sort chips row (`chipSortIp/Name/Vendor/Method`) — replaced by per-card info display
- Monitor chip — replaced by FAB long-press toggle
