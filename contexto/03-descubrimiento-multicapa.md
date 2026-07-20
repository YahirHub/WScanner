# Fecha
2026-07-13
> Estado del documento: histórico. Para el pipeline vigente desde 2026-07-20 consultar `008-mejora-deteccion-offline-y-refactor-motor.md`.

# Objetivo
Implementar motor de descubrimiento multicapa (mDNS + SSDP + NetBIOS + HTTP fingerprinting)
para obtener nombres reales de dispositivos sin depender de MAC addresses (bloqueadas por Android 10+).

# Contexto
- Android 10+ bloquea acceso a `/proc/net/arp` (SELinux `proc_net`, `untrusted_app`)
- Los 13 métodos ARP de NetworkScanner fallan — confirmado con logs: `Permission denied`, `Cannot bind netlink socket`
- La OUI database (53,371 entradas) no se puede usar porque las MAC no son accesibles sin root
- Fing usa mDNS/Bonjour, UPnP/SSDP, NetBIOS, SNMP, DHCP monitoring — según su SDK público

# Arquitectura

## Pipeline de escaneo reorganizado

```
Fase 0:      Detectar subred + gateway (WifiManager + DhcpInfo)
Fase 0.5:    Cargar VendorResolver + adquirir MulticastLock

Fase 1:      Ping sweep (254 IPs, 10 hilos, 300ms timeout)
             → foundIps, hostnames DNS

Fase 1.5:    [EN PARALELO] mDNS discovery (MdnsDiscovery)
             → mdnsNames: Map<IP, hostname .local>
             Estrategia: service discovery (PTR _services._dns-sd._udp.local) +
             reverse lookup (PTR X.X.X.X.in-addr.arpa) a cada IP encontrada

Fase 1.6:    [EN PARALELO] SSDP discovery (SsdpDiscovery)
             → ssdpNames: Map<IP, friendlyName/SERVER>
             M-SEARCH a 239.255.255.250:1900, ST: ssdp:all, GET al XML de LOCATION

Fase 2:      ARP table (14 métodos) — seguirá fallando, se mantiene por consistencia

Fase 3:      Port scanning + HTTP banner grab
             → openPorts, httpBanners (Server header + HTML <title>)

Fase 3.5:    NetBIOS probe (NetBiosDiscovery)
             → netbiosNames (solo IPs con SMB visible o dispositivos no identificados)
             NBSTAT query UDP puerto 137

Fase 4:      Construir dispositivos con buildDeviceName() multicapa
             → liberar MulticastLock
```

## Prioridad de nombrado (buildDeviceName)

1. Vendor OUI DB (requiere MAC real — casi nunca disponible)
2. **mDNS hostname** — iPhone-de-Juan.local, HP-LaserJet.local
3. **SSDP friendlyName** — Samsung QLED TV, TP-Link Archer C7
4. **NetBIOS name** — DESKTOP-OFICINA
5. DNS hostname — poco común en redes domésticas
6. HTTP banner — Server: Apache/2.4 | Title: ASUS Router
7. Heurística por puertos — Router, Servidor SSH, Cámara IP
8. Fallback — Equipo .XX

## Decisiones técnicas

- **MulticastSocket crudo** (no NsdManager): la implementación actual mantiene control directo del protocolo y compatibilidad con API 24→36. El permiso `ACCESS_LOCAL_NETWORK` debe revisarse al migrar el target a API 37 o superior; no se documenta como requisito actual del target 36.
- **Un solo MulticastLock compartido**: adquirido al inicio del scan, liberado al final. Evita locks redundantes.
- **Sin dependencias externas**: todo usa `java.net.MulticastSocket`, `DatagramSocket`, `DatagramPacket`, `URL`, `HttpURLConnection`.
- **Permiso `CHANGE_WIFI_MULTICAST_STATE`**: normal (no requiere diálogo de usuario).

## Archivos creados/modificados

| Archivo | Cambio |
|---------|--------|
| `MdnsDiscovery.java` | **NUEVO** — Construcción DNS binaria manual (RFC 1035/6762), service discovery + reverse lookup |
| `SsdpDiscovery.java` | **NUEVO** — M-SEARCH + parseo de XML <friendlyName> vía GET |
| `NetBiosDiscovery.java` | **NUEVO** — NBSTAT query UDP, codificación half-ASCII NetBIOS |
| `NetworkScanner.java` | MODIFICADO — Integración de 3 fases paralelas, HTTP banner grab, buildDeviceName() multicapa, MulticastLock |
| `Device.java` | MODIFICADO — Campos `discoveryMethod` y `discoveryDetail` |
| `DeviceAdapter.java` | MODIFICADO — Muestra método de descubrimiento: "vía Bonjour (mDNS) · iPhone-de-Juan.local" |
| `AndroidManifest.xml` | MODIFICADO — Permiso `CHANGE_WIFI_MULTICAST_STATE` |

## Logs para diagnóstico

```bash
adb logcat -c && adb logcat WScanner.mDNS:* WScanner.SSDP:* WScanner.NetBIOS:* WScanner.Scanner:* WScanner.Vendor:* *:S
```

TAGs:
- `WScanner.mDNS` — MdnsDiscovery (queries DNS, respuestas, nombres .local)
- `WScanner.SSDP` — SsdpDiscovery (M-SEARCH, respuestas, friendlyName)
- `WScanner.NetBIOS` — NetBiosDiscovery (NBSTAT query, nombres extraídos)
- `WScanner.Scanner` — NetworkScanner (pipeline completo, cada fase, buildDeviceName)
- `WScanner.Vendor` — VendorResolver (carga OUI, cada resolve())

## Resultado esperado

- Sin mDNS/SSDP (sin dispositivos compatibles en la red): ~mismo comportamiento que antes
- Con dispositivos Apple/impresoras/SmartTV: nombres reales (iPhone, Samsung TV, HP LaserJet)
- En UI: "vía Bonjour (mDNS)" o "vía UPnP (SSDP)" en vez de solo "Equipo .XX"

## Riesgos

- **AP isolation** en routers: bloquea tráfico multicast entre clientes WiFi → mDNS/SSDP no ven nada. Se loggea warning.
- **Timeout total**: puede aumentar de 17s a ~22s. Aceptable para V1.
- **DNS binario manual**: propenso a errores de offset en paquetes no estándar. Se captura con try/catch por record.

## Pendientes

- Probar en dispositivo real con varios tipos de dispositivos conectados (Apple, Windows, Smart TV, impresora)
- Agregar NetBIOS fallback para IPs sin puertos abiertos (el filtro actual solo consulta si hay 445/139)
- Considerar SNMP y DHCP monitoring para versión futura
