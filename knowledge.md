# WScanner — Project knowledge

## What this is

Android local-network scanner and diagnostics app. Device discovery is local: ICMP/TCP presence checks, mDNS/DNS-SD, SSDP/UPnP, reverse DNS, NetBIOS, port signals and local HTTP metadata. Detection does not require Internet APIs or vendor dictionaries. The embedded OUI database is optional enrichment only when a MAC is actually available.

Independent tools include Speed Test, Traceroute, Wake-on-LAN and Scan History. Speed Test can require Internet access; the device discovery engine does not.

## Key directories

| Path | Purpose |
|------|---------|
| `app/src/main/java/com/thowilabs/wscanner/` | Production Java source (15 active classes) |
| `app/src/test/java/com/thowilabs/wscanner/` | Unit tests for discovery logic |
| `app/src/main/res/layout/` | Phone layouts |
| `app/src/main/res/layout-sw600dp/` | Tablet 7-inch alternatives |
| `app/src/main/res/layout-w840dp/` | Large tablet / landscape alternatives |
| `app/src/main/res/anim/` | Animation resources |
| `app/src/main/res/values/` | Colors, strings, themes and dimensions |
| `app/src/main/res/drawable/` | Vector icons and backgrounds |
| `app/src/main/assets/oui_database.json` | Optional legacy OUI enrichment; not required for discovery |
| `contexto/` | Persistent technical context and decisions |

## Source files (15 active)

| File | Role |
|------|------|
| `MainActivity.java` | UI, navigation, scan lifecycle, stop/cancel handling and tools |
| `NetworkScanner.java` | Discovery orchestrator, real subnet range, candidate collection, ports and cancellation |
| `DeviceIdentity.java` | Central identity ranking, merge rules and signal-based device classification |
| `MdnsDiscovery.java` | mDNS/DNS-SD service discovery and reverse lookup |
| `SsdpDiscovery.java` | SSDP/UPnP discovery and safe local description parsing |
| `NetBiosDiscovery.java` | Concurrent NBSTAT/NetBIOS discovery |
| `VendorResolver.java` | Optional OUI lookup when a valid MAC is available |
| `Device.java` | Device model |
| `DeviceAdapter.java` | RecyclerView adapter and filtering |
| `HapticUtil.java` | Cross-API haptic feedback |
| `SpeedometerGauge.java` | Speed test gauge custom view |
| `SpeedTestTool.java` | External connectivity speed test |
| `TracerouteTool.java` | UDP traceroute |
| `WakeOnLanTool.java` | Wake-on-LAN sender |
| `ScanHistory.java` | Persistent scan history |

## Build & run

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:installDebug
adb logcat WScanner.mDNS:* WScanner.SSDP:* WScanner.NetBIOS:* WScanner.Scanner:* WScanner.UI:* *:S
```

On Windows use `gradlew.bat` with the same tasks.

## Tech stack

| Layer | Choice |
|-------|--------|
| Language | Java 11 |
| UI | XML Views, Material Components, RecyclerView |
| Icons | Mikepenz Iconics 5.5 + Community Material Typeface |
| Network | `java.net` + Android Connectivity APIs |
| Protocols | ICMP, TCP, mDNS/DNS-SD, SSDP/UPnP, DNS, NetBIOS/NBNS, HTTP, UDP traceroute |
| Build | Gradle KTS, AGP 9.2.1 |
| Min SDK | 24 (Android 7.0) |
| Compile/Target SDK | 36 (Android 16) |

## Current discovery pipeline

1. Read active local IPv4, prefix and gateway from `ConnectivityManager`/`LinkProperties`; fallback to WiFi APIs.
2. Enumerate the actual subnet, bounded to a local `/24` when the network exceeds 1024 usable hosts.
3. Presence sweep: `InetAddress.isReachable` plus TCP fallback on representative ports.
4. mDNS/DNS-SD and SSDP in parallel; multicast responses can add candidates even without ping response.
5. mDNS reverse lookup for candidates.
6. ARP/neighbor cache as optional enrichment only.
7. Concurrent port scan and signal-based classification.
8. Local HTTP title extraction on known open HTTP ports.
9. Concurrent NetBIOS on candidates lacking a strong identity.
10. Merge all updates by IP through `DeviceIdentity`.

## Identity source priority

`Local 100 > mDNS 90 > SSDP 85 > NetBIOS 80 > DNS 70 > HTTP 60 > OUI 50 > TCP 30 > Heuristic 10`

Generic-name penalties prevent a higher-ranked but vague label from overwriting a specific useful identity.

## Key conventions & gotchas

- **Java production code only.**
- **Single Activity, no Fragments.**
- **No external network-discovery libraries.** Protocol handling is implemented with local sockets/APIs.
- **No Internet dependency for device detection.** SSDP XML metadata is fetched only from the same local responder host and redirects are disabled.
- **MAC/OUI is optional.** Discovery must work with an empty ARP cache.
- **MulticastLock is required** during mDNS/SSDP scanning.
- **Progressive discovery:** later sources update the same IP rather than creating duplicate identities.
- **Cancellation:** `NetworkScanner.cancel()` invalidates the active generation; UI stop calls it.
- **Large networks:** active scan is bounded to avoid accidental massive sweeps.
- **Permissions:** INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE.
- **Future Android:** re-check local-network permission requirements before targeting API 37+.

## Unit tests added

- `DeviceIdentityTest`: merge quality and signal classification.
- `MdnsDiscoveryTest`: A-record publication and SRV instance normalization.
- `NetBiosDiscoveryTest`: valid NBSTAT query wire layout.
- `NetworkRangeTest`: `/24` host enumeration and large-network bounding.

## Files intentionally absent/removed

- `ArpReader.java`: not part of the current tree; neighbor-cache enrichment lives in `NetworkScanner`.
- SwipeRefreshLayout flow: scanner is initiated/stopped from the FAB flow.
