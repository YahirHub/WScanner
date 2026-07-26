# 014 · Fingerprint de SO y OUI mejorado (offline)

## Contexto

Se pidió añadir:
1. Detección de fabricante (OUI) a partir de la MAC.
2. Fingerprinting de SO por TTL y puertos abiertos (Windows / Linux / Router / IoT).
3. mDNS/Bonjour + SSDP/UPnP para Chromecasts, impresoras, NAS, cámaras.
4. Mejorar la detección en general, todo offline, respetando las limitaciones de Android.

## Estado previo

- **OUI**: ya existía `VendorResolver` con `assets/oui_database.json`.
- **mDNS / SSDP / WS-Discovery / SNMP / NetBIOS / HTTP / TLS / RTSP / FTP / SSH**: ya integrado en `NetworkScanner`.
- **TTL**: el campo `Device.ttl` existía pero jamás se rellenaba (comentario "requiere raw sockets").
- **Fingerprint de SO**: no existía como módulo; solo un `DeviceIdentity.inferOsHint` que leía cadenas ya declaradas por el propio dispositivo.

## Cambios aplicados

### `TtlProbe.java` (nuevo)
Obtiene el TTL invocando `/system/bin/ping -c 1 -W 1 -n <ip>` (subproceso), parsea `ttl=NN`. Sin permisos extra, sin red externa. Timeout de 1.2 s con hilo watchdog. Ante SELinux / ROMs que bloquean `fork`, degrada silenciosamente a `-1` y el resto del pipeline sigue.

### `OsFingerprint.java` (nuevo)
Clasifica el SO combinando tres señales:
- Texto autoanunciado (SSH banner, HTTP Server, sysDescr SNMP, mDNS TXT).
- Combinaciones de puertos (445+3389 → Windows, 62078 → iOS, 9100+631 → impresora, 7000+8009 → Chromecast/tvOS…).
- TTL normalizado (64 / 128 / 255 tras sumar saltos habituales).

Devuelve `{label, confidence 0-100}` o `null` cuando no hay evidencia. La UI solo muestra fingerprints con confianza ≥ 50.

### `VendorResolver`
Detecta MAC **locally-administered** (bit 1 del primer octeto). Android 10+, iOS 14+ y Windows 10+ aleatorizan la MAC por SSID: mostrar un fabricante inventado sería un falso positivo, ahora devuelve *"MAC aleatoria (privacidad)"* y hay `isRandomized()` público para que la UI lo señalice.

### `Device`
Nuevos campos: `osFingerprint`, `osFingerprintConfidence`, `macRandomized`. El `ttl` deja de ser un placeholder muerto.

### `DeviceIdentity.mergeInto`
Al fusionar observaciones del mismo host se conserva el fingerprint de mayor confianza y el TTL más informativo, y se propaga `macRandomized`.

### `NetworkScanner.scanPortsForHost`
Tras el escaneo de puertos y banners, invoca `TtlProbe` y `OsFingerprint`, y adjunta el resultado al `Device` que se emite.

### `DeviceAdapter`
- Línea de identidad muestra el fingerprint cuando `confidence ≥ 50`.
- Filtro de búsqueda incluye `osFingerprint`.

## Limitaciones del sistema tenidas en cuenta

| Limitación                                             | Mitigación                                                                 |
|--------------------------------------------------------|----------------------------------------------------------------------------|
| Android no permite ICMP raw sin root                   | Se usa `/system/bin/ping` como subproceso, sin permisos extra              |
| ROMs con SELinux estricto bloquean `exec`              | `TtlProbe` degrada a `-1` y `OsFingerprint` sigue con puertos + banners    |
| Firewalls locales descartan ICMP (Windows, iOS)        | Rama de heurística "sin TTL": SMB → Windows, SSH → *nix                    |
| Android 10+ aleatoriza la MAC                          | `VendorResolver` detecta LAA y devuelve etiqueta explícita                 |
| Multicast requiere `WifiManager.MulticastLock`         | Ya estaba adquirido/liberado en `NetworkScanner`                           |
| TTL puede llegar decrementado por repetidores Wi-Fi    | `normalizeTtl` tolera ±30 saltos antes de decidir el valor base            |

## Todo offline

Ningún cambio introduce llamadas HTTP a Internet ni servicios cloud. La base OUI sigue siendo el JSON en `assets/`; el fingerprint es puramente algorítmico.

## Métrica

- 2 archivos Java nuevos (`TtlProbe`, `OsFingerprint`), ~230 LOC.
- 4 archivos existentes modificados (`Device`, `VendorResolver`, `DeviceIdentity`, `NetworkScanner`, `DeviceAdapter`), <60 LOC añadidas.
- 0 nuevos permisos en `AndroidManifest`.
- 0 nuevas dependencias.
