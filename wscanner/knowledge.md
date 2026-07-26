# WScanner — Project knowledge

## Current purpose

Android local-network discovery and diagnostics app. Device discovery is local/offline: no cloud API is required for presence or identity. The engine combines protocol evidence and self-advertised metadata rather than assuming that MAC/OUI will be available on modern Android.

Independent diagnostics such as Speed Test use Internet access. Speed Test now runs a normal bidirectional HTTP measurement first, then switches to a second UI state for a conventional real-file download comparison.

## Current production sources (20 Java classes)

Core discovery/inventory:

- `MainActivity.java`: UI, persistent inventory by subnet, manual rescan and continuous monitor lifecycle.
- `NetworkScanner.java`: selected-LAN detection, discovery orchestration, cancellation, ports and local fingerprints.
- `ScanCycleState.java`: records IPs seen during one complete cycle; absence is applied only at cycle end.
- `DeviceIdentity.java`: single merge/ranking/type-classification policy.
- `MdnsDiscovery.java`: raw mDNS/DNS-SD discovery, service types, TXT self-advertised metadata and reverse lookup.
- `SsdpDiscovery.java`: SSDP/UPnP discovery plus structured local XML metadata.
- `WsDiscovery.java`: WS-Discovery and ONVIF-oriented probes.
- `SnmpDiscovery.java`: optional SNMP v2c `public` read of `sysName.0`/`sysDescr.0`.
- `NetBiosDiscovery.java`: NBSTAT/NetBIOS names plus workgroup and Unit ID/MAC when published.
- `Device.java`, `DeviceAdapter.java`: device model and XML-backed premium cards with stable IDs, filtering and online/offline presentation.
- `PressStateUtil.java`: cancellable press-scale feedback that preserves click, long-click and scrolling.
- `ShimmerTextView.java`: lightweight text shimmer enabled only while a process is active.
- `VendorResolver.java`: optional embedded OUI enrichment only when a MAC exists.

Other app tools:

- `HapticUtil.java`, `SpeedometerGauge.java`, `SpeedTestTool.java`, `TracerouteTool.java`, `WakeOnLanTool.java`, `ScanHistory.java`.
- `SpeedTestTool.java`: Cloudflare primary HTTP speed backend, dynamic LibreSpeed public-server fallback, legacy direct-download compatibility, adaptive/ramped multistream download/upload sizing, HTTP latency/jitter, and a second real-file download phase. Provider fallback is internal and does not alter the UI flow.


## Premium UI invariants

- Press feedback must be subtle and must return to the neutral state when the finger drags outside or a parent begins scrolling.
- RecyclerView rows animate only on first discovery; repeated protocol updates never replay entrance animations or leave animators attached to recycled holders.
- Haptics use platform feedback and respect modern Android behavior; legacy vibration exists only for API 24-25 compatibility.
- Shimmer is reserved for active scan/speed-test phases and stops when the operation or screen ends.
- Empty states explain the next action and why the feature is useful instead of presenting an unexplained blank list.
- Scanner, device detail, Speed Test and About share the same dark-navy palette, rounded surfaces, subtle strokes and cyan accent hierarchy.
- Tablet layouts keep split-pane behavior and use a compact variant of the network summary.
- UI assets are local; the sidebar banner is WebP-compressed to reduce APK size and startup decode cost.

## Discovery pipeline

1. Select active WiFi/Ethernet `Network`; read IPv4/prefix/gateway from `LinkProperties`.
2. Enumerate the actual subnet, bounded to the phone's local `/24` when usable hosts exceed 1024.
3. Keep local IP and gateway as known candidates.
4. Presence sweep: best-effort ICMP plus TCP fallback; sockets are bound to the selected Android `Network` when available.
5. Run mDNS/DNS-SD, SSDP/UPnP, WS-Discovery and SNMP concurrently.
6. Multicast/protocol responses independently add candidates even when ICMP fails.
7. mDNS reverse lookup.
8. ARP/neighbor cache as optional MAC/OUI enrichment.
9. Concurrent 36-port scan on discovered candidates.
10. Local metadata: DNS-SD TXT, HTTP title/auth realm, HTTP Server as detail only, TLS certificate SAN/CN, SSH/FTP/RTSP banners.
11. NetBIOS fallback with optional NBSTAT Unit ID/MAC enrichment.
12. Merge by IP with `DeviceIdentity`.
13. At cycle completion only, `ScanCycleState` marks known-but-not-seen devices offline.

## Identity priority

`Local 100 > Gateway 98 > mDNS 90 > WS-Discovery 88 > SSDP 85 > SNMP 82 > NetBIOS 80 > DNS 70 > TLS 65 > HTTP 60 > OUI 50 > TCP 30 > Heuristic 10`

Generic names are penalized. A `Gateway` identity cannot be replaced by an HTTP software banner such as `lighttpd`.

## Continuous monitoring invariants

- Starting a new scan does **not** preemptively mark all devices offline.
- A device keeps its previous confirmed visual state while the cycle is running.
- `seenIps` is reset at cycle start.
- Every discovery callback marks the IP as seen.
- Only `finishCycle()` changes unseen known devices to offline.
- A later observation immediately restores online state.
- Repeated manual scans retain inventory on the same CIDR.
- A CIDR change clears the inventory to avoid mixing different networks.
- Stopping monitoring preserves the last completed/verified state.
- Starting a cycle manually during the monitor delay cancels the pending scheduled callback, preventing overlapping scans.

## Speed Test flow

1. HTTP latency/jitter against the selected normal backend.
2. Small warm-up transfer to estimate connection capacity.
3. Adaptive 4-stream download test.
4. Adaptive 3-stream upload test.
5. Cloudflare is tried first. On failure, fetch LibreSpeed's public server list, probe candidates with a bounded parallel latency budget, and try up to the three fastest responding servers transparently. If all bidirectional backends fail, use the historical direct-download engine as reduced compatibility.
6. Switch the UI to the real-download screen.
7. Download a static test file directly; if the first legacy host fails, try the second without exposing an intermediate error.
8. Present normal download/upload beside the real-download result.

Leaving the Speed Test view invalidates and interrupts the active worker so late callbacks cannot update a hidden screen. Blocking HTTP calls remain bounded by connection/read timeouts.

## Build

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:installDebug
```

Windows uses `gradlew.bat` with the same tasks.

## Diagnostic logs

```bash
adb logcat WScanner.mDNS:* WScanner.SSDP:* WScanner.WSD:* WScanner.SNMP:* WScanner.NetBIOS:* WScanner.Scanner:* WScanner.UI:* *:S
```

## Constraints and decisions

- Java production code, minSdk 24, target/compile SDK 36.
- No external discovery library and no cloud dependency for device detection.
- `MulticastLock` is acquired for raw multicast compatibility and released after a scan.
- SSDP `LOCATION` is fetched only when its URL host is exactly the responder IP and redirects are disabled.
- HTTP `Server` is software metadata, never a device identity by itself.
- TLS inspection uses a per-probe capturing `X509TrustManager` that records the presented server chain and delegates normal platform validation; it does not modify global trust configuration or send credentials.
- SNMP is optional best-effort and uses only the conventional read-only `public` community.
- `oui_database.json` is legacy optional enrichment; discovery must remain functional without it.
- RTSP alone is classified conservatively as RTSP/video; explicit ONVIF/camera signals are required to claim camera identity.
- Before targeting API 37+, implement Android local-network runtime permission behavior.

## Validation status

Local Java validation with minimal Android stubs compiles the core discovery classes. A lightweight local runner executes 28 Java logic tests successfully. Full Android Gradle build/lint must still be run in an environment with Android SDK and an available Gradle distribution.
