# WScanner

Escáner de red local para Android, sin anuncios ni analíticas. El motor de detección funciona dentro de la red local y no consulta APIs, servicios remotos ni bases de datos en Internet para descubrir dispositivos.

## Características

- **Descubrimiento multicapa local:** alcance ICMP, comprobación TCP, mDNS/DNS-SD, SSDP/UPnP, DNS inverso, NetBIOS, puertos y metadatos HTTP locales.
- **Detección de equipos que bloquean ping:** un host puede incorporarse por respuesta TCP, mDNS o SSDP aunque no conteste ICMP.
- **Subred real:** usa la dirección IPv4 y prefijo reportados por Android en lugar de asumir siempre `/24`.
- **Identidad por señales observadas:** combina nombres y tipos detectados por protocolos y servicios locales, sin depender de diccionarios de fabricantes.
- **Resultados progresivos:** los dispositivos aparecen y se enriquecen durante el escaneo.
- **Fusión estable de identidad:** una señal genérica posterior no reemplaza un nombre específico de mejor calidad.
- **Cancelación real del escaneo:** detener el escaneo invalida el trabajo activo y evita seguir publicando resultados obsoletos.
- **Privacidad:** sin anuncios, sin analíticas y sin permisos de ubicación, contactos o almacenamiento.

> La detección de dispositivos es local/offline. Herramientas independientes como la prueba de velocidad sí pueden necesitar acceso a Internet para medir conectividad externa.

## Tecnología

| Capa | Tecnología |
|---|---|
| Lenguaje | Java 11 |
| UI | Material Components, CoordinatorLayout, RecyclerView, Iconics |
| Red | `java.net` y APIs de conectividad de Android |
| Protocolos | ICMP, TCP, mDNS/DNS-SD, SSDP/UPnP, DNS, NetBIOS/NBNS, HTTP |
| Build | Gradle KTS, Android Gradle Plugin 9.2.1 |
| Min SDK | 24 (Android 7.0) |
| Compile/Target SDK | 36 (Android 16) |

## Permisos

El manifiesto declara:

- `INTERNET`: necesario para sockets TCP/UDP y HTTP, incluidos destinos de la red local.
- `ACCESS_NETWORK_STATE`: consulta del estado y propiedades de red.
- `ACCESS_WIFI_STATE`: compatibilidad con información WiFi y fallback de IP/gateway.
- `CHANGE_WIFI_MULTICAST_STATE`: `MulticastLock` para recibir mDNS/SSDP de forma fiable en WiFi.

No solicita ubicación, contactos ni almacenamiento.

## Estructura principal

```text
WScanner/
├── app/
│   ├── src/main/java/com/thowilabs/wscanner/
│   │   ├── MainActivity.java          # UI, navegación y ciclo de escaneo
│   │   ├── NetworkScanner.java        # Orquestador del descubrimiento multicapa
│   │   ├── DeviceIdentity.java        # Fusión y clasificación por señales observadas
│   │   ├── MdnsDiscovery.java         # mDNS/DNS-SD manual
│   │   ├── SsdpDiscovery.java         # SSDP/UPnP y descripción XML local segura
│   │   ├── NetBiosDiscovery.java      # NBSTAT/NetBIOS concurrente
│   │   ├── VendorResolver.java        # Enriquecimiento OUI opcional si existe MAC
│   │   ├── Device.java                # Modelo de dispositivo
│   │   └── DeviceAdapter.java         # Presentación y filtrado de dispositivos
│   ├── src/main/assets/
│   │   └── oui_database.json          # Enriquecimiento legado opcional; no requerido
│   └── src/test/java/com/thowilabs/wscanner/
│       ├── DeviceIdentityTest.java
│       ├── MdnsDiscoveryTest.java
│       ├── NetBiosDiscoveryTest.java
│       └── NetworkRangeTest.java
├── contexto/                          # Contexto persistente y decisiones técnicas
├── knowledge.md                       # Resumen operativo del proyecto
└── README.md
```

## Cómo funciona la detección

1. **Detecta la red IPv4 activa** mediante `ConnectivityManager`, `LinkProperties` y `LinkAddress`; conserva un fallback compatible con APIs/ROMs antiguas.
2. **Construye el rango de hosts** según el prefijo real. Para redes de más de 1024 hosts limita el escaneo activo al `/24` alrededor del teléfono para evitar barridos masivos accidentales.
3. **Comprueba presencia** con `InetAddress.isReachable()` y, si falla, intenta una lista pequeña de puertos TCP frecuentes.
4. **Ejecuta mDNS y SSDP en paralelo.** Sus respuestas pueden incorporar dispositivos aunque no hayan respondido al barrido inicial.
5. **Consulta mDNS inverso** sobre los candidatos locales encontrados.
6. **Lee la caché ARP/vecinos como enriquecimiento opcional.** La detección no depende de obtener MAC.
7. **Escanea puertos en paralelo** y clasifica el tipo de equipo mediante protocolos/servicios observados.
8. **Obtiene títulos HTTP locales** solo sobre puertos HTTP abiertos conocidos.
9. **Consulta NetBIOS concurrentemente** en candidatos que todavía no tienen una identidad fuerte.
10. **Fusiona resultados por IP** usando una prioridad de fuentes y evitando degradar nombres específicos.

### Prioridad de identidad

`Local > mDNS > SSDP > NetBIOS > DNS > HTTP > OUI opcional > TCP > heurística`

La prioridad no es absoluta: un nombre genérico de una fuente superior no reemplaza automáticamente una identidad específica ya conocida.

## Desarrollo

```bash
# Tests unitarios
./gradlew :app:testDebugUnitTest

# Compilar APK debug
./gradlew :app:assembleDebug

# Lint
./gradlew :app:lintDebug

# Instalar en un dispositivo conectado
./gradlew :app:installDebug
```

En Windows también pueden usarse los mismos objetivos con `gradlew.bat`.

### Logs de diagnóstico

```bash
adb logcat WScanner.mDNS:* WScanner.SSDP:* WScanner.NetBIOS:* WScanner.Scanner:* WScanner.UI:* *:S
```

## Pruebas recomendadas en dispositivo real

Probar al menos una red con una combinación de router, Android/iOS, Windows/macOS, Smart TV/Chromecast, NAS y/o impresora. Verificar especialmente que:

- aparezcan equipos que bloquean ICMP pero exponen un servicio TCP;
- mDNS/SSDP incorporen dispositivos no detectados por ping;
- los nombres no empeoren cuando llegan varias fuentes para la misma IP;
- detener el escaneo no siga agregando resultados del trabajo cancelado;
- la aplicación funcione aunque la caché ARP esté vacía y no haya MAC disponible.

## Licencia

MIT © 2025 Thowilabs
