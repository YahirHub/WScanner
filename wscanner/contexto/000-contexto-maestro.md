# Contexto Maestro — WScanner

# Fecha

2026-07-21

# Objetivo

Mantener una referencia única del estado técnico vigente de WScanner para poder continuar el desarrollo sin depender del historial de conversación. La aplicación debe funcionar como escáner e inventario de red local Android, con detección offline y arquitectura simple, sin cloud obligatorio para descubrir dispositivos.

# Decisiones tomadas

- Lenguaje de producción: Java 11.
- `minSdk 24`, `compileSdk 36`, `targetSdk 36`.
- UI basada en XML Views/RecyclerView dentro de una Single Activity; no introducir Fragments sin una necesidad real.
- El motor de detección no depende de Internet, cuentas, APIs cloud ni diccionarios remotos.
- La base `oui_database.json` permanece únicamente como enriquecimiento opcional cuando Android expone una MAC válida.
- La presencia y la identidad se construyen mediante múltiples evidencias locales verificables.
- `DeviceIdentity` es la única política de ranking/fusión; no duplicar rankings en el escáner o la UI.
- El inventario persiste entre reescaneos de la misma subred. Solo se limpia automáticamente cuando cambia el CIDR para evitar mezclar redes.
- Online/offline se decide al finalizar un ciclo completo. Nunca marcar toda la lista offline al empezar un nuevo ciclo.
- El acceso directo a LAN deberá adaptarse al permiso `ACCESS_LOCAL_NETWORK` antes de migrar a `targetSdk 37`.
- Speed Test es una herramienta externa al descubrimiento offline: usa Internet, ejecuta primero una medición bidireccional con fallback transparente y luego una descarga real independiente.
- La UI premium se mantiene sobre Views/XML y Material Components; las microinteracciones usan APIs nativas y no agregan dependencias de animación.
- Los estados de presión deben poder cancelarse al iniciar un desplazamiento y el shimmer solo puede permanecer activo mientras exista una operación real.

# Arquitectura actual

## Componentes principales

| Clase | Responsabilidad |
|---|---|
| `MainActivity.java` | UI, navegación, inventario por CIDR, ciclo manual y monitor continuo |
| `NetworkScanner.java` | Orquestación de descubrimiento local, rango, cancelación, puertos y fingerprints |
| `ScanCycleState.java` | IPs vistas por ciclo; aplica ausencia únicamente al terminar |
| `DeviceIdentity.java` | Ranking de fuentes, fusión de metadatos y clasificación de tipo |
| `MdnsDiscovery.java` | mDNS/DNS-SD estructurado, TXT autoanunciado y reverse lookup |
| `SsdpDiscovery.java` | SSDP/UPnP y lectura local segura de descripción de dispositivo |
| `WsDiscovery.java` | WS-Discovery y señales ONVIF/WSD |
| `SnmpDiscovery.java` | SNMP v2c best-effort: `sysName.0` y `sysDescr.0` |
| `NetBiosDiscovery.java` | NBSTAT/NetBIOS con nombre, grupo y Unit ID/MAC opcional |
| `VendorResolver.java` | OUI opcional si existe MAC |
| `Device.java` | Modelo de inventario |
| `DeviceAdapter.java` | Lista, filtros, iconografía y estado visual online/offline |
| `HapticUtil.java` | Feedback háptico |
| `PressStateUtil.java` | Estados de presión cancelables y retorno con resorte |
| `ShimmerTextView.java` | Shimmer de texto limitado a procesos activos |
| `SpeedometerGauge.java` | Gauge reutilizable para test normal y descarga real |
| `SpeedTestTool.java` | Speed Test bidireccional con fallback y segunda descarga real |
| `TracerouteTool.java` | Traceroute |
| `WakeOnLanTool.java` | Wake-on-LAN |
| `ScanHistory.java` | Historial existente |

## Pipeline vigente

```text
Detectar Network WiFi/Ethernet + IPv4/prefijo/gateway
  ↓
Enumerar rango real (máximo activo: 1024; redes mayores se acotan al /24 local)
  ↓
Publicar local + gateway
  ↓
Presencia ICMP/TCP + estimulación de neighbor cache
  ├──────── mDNS/DNS-SD estructurado + TXT
  ├──────── SSDP/UPnP estructurado
  ├──────── WS-Discovery/ONVIF
  └──────── SNMP v2c best-effort
  ↓
mDNS reverse
  ↓
ARP/MAC/OUI opcional
  ↓
Escaneo concurrente de 36 puertos
  ↓
HTTP title/realm + Server como detalle
TLS SAN/CN + SSH/FTP/RTSP banners
  ↓
NetBIOS + Unit ID/MAC opcional
  ↓
DeviceIdentity.mergeInto(IP)
  ↓
ScanCycleState.finishCycle(): vistos online / no vistos offline
```

Prioridad base:

`Local 100 > Gateway 98 > mDNS 90 > WS-Discovery 88 > SSDP 85 > SNMP 82 > NetBIOS 80 > DNS 70 > TLS 65 > HTTP 60 > OUI DB 50 > TCP 30 > Heurística 10`.

Los nombres genéricos se penalizan. Una fuente de mayor prioridad no sustituye automáticamente un nombre específico útil.


## Speed Test vigente

```text
Cloudflare normal → lista pública LibreSpeed → selección de hasta 3 servidores → compatibilidad descarga directa
  ↓
latencia + jitter + download multistream + upload multistream
  ↓
segunda pantalla
  ↓
descarga real: Tele2 → fallback ThinkBroadband
```

La UI no expone el cambio de proveedor. Si no existe un backend bidireccional disponible, upload queda como no disponible en vez de reportar un `0` ficticio.

# Librerías usadas

- AndroidX AppCompat.
- Material Components.
- RecyclerView.
- Mikepenz Iconics / Community Material Typeface.
- `java.net`, `javax.net.ssl` y APIs Android de conectividad para el motor.
- JUnit para tests unitarios.
- No hay librería externa de descubrimiento de red.

# Archivos importantes modificados

Cambios vigentes documentados principalmente en:

- `contexto/008-mejora-deteccion-offline-y-refactor-motor.md`
- `contexto/009-monitor-continuo-y-descubrimiento-avanzado-offline.md`
- `contexto/010-speed-test-doble-etapa-y-fallback.md`
- `contexto/011-redisenio-ui-premium-y-microinteracciones.md`

Código central:

- `app/src/main/java/com/thowilabs/wscanner/MainActivity.java`
- `app/src/main/java/com/thowilabs/wscanner/NetworkScanner.java`
- `app/src/main/java/com/thowilabs/wscanner/ScanCycleState.java`
- `app/src/main/java/com/thowilabs/wscanner/DeviceIdentity.java`
- `app/src/main/java/com/thowilabs/wscanner/MdnsDiscovery.java`
- `app/src/main/java/com/thowilabs/wscanner/SsdpDiscovery.java`
- `app/src/main/java/com/thowilabs/wscanner/WsDiscovery.java`
- `app/src/main/java/com/thowilabs/wscanner/SnmpDiscovery.java`
- `app/src/main/java/com/thowilabs/wscanner/NetBiosDiscovery.java`
- `app/src/main/java/com/thowilabs/wscanner/Device.java`
- `app/src/main/java/com/thowilabs/wscanner/DeviceAdapter.java`

# Problemas encontrados

Históricamente el proyecto tenía contexto desfasado respecto al código y estrategias ARP/OUI descritas como parte crítica aunque Android moderno puede impedir obtener MAC. También existían fallos concretos en mDNS, NBSTAT, rango de red y tratamiento de dispositivos que bloquean ICMP.

En la revisión más reciente se encontró además:

- estado offline aplicado prematuramente al iniciar cada ciclo de monitor;
- parpadeo visual por reiniciar alpha de las tarjetas en cada actualización;
- inventario eliminado al reescanear manualmente;
- gateway degradado a nombres de software HTTP como `lighttpd`;
- pérdida de tipos de servicio mDNS;
- pérdida de fabricante/modelo/tipo SSDP;
- ausencia de WS-Discovery/ONVIF y SNMP;
- falta de señales TLS y realms HTTP.
- registros TXT DNS-SD desaprovechados aunque muchos equipos publican ahí modelo/nombre/plataforma;
- Unit ID/MAC de NBSTAT descartado;
- puerto RTSP tratado con demasiada certeza como cámara;
- posible solapamiento de ciclos si el usuario iniciaba manualmente un barrido durante la pausa del monitor.
- callbacks tardíos de workers cancelados podían mezclarse con el estado visto/no visto del ciclo siguiente.
- el Speed Test anterior no medía subida realmente: devolvía `0` fijo y solo descargaba un archivo estático.

# Soluciones implementadas

- Estado por ciclo completo con `ScanCycleState`.
- Inventario persistente en el mismo CIDR y reinicio al cambiar de red.
- Fusión centralizada mediante `DeviceIdentity`.
- mDNS corregido y estructurado por servicios y metadatos TXT autoanunciados.
- NBSTAT corregido y ampliado para Unit ID/MAC y grupo de trabajo.
- SSDP estructurado y limitado al host respondedor para leer `LOCATION`.
- WS-Discovery/ONVIF agregado.
- SNMP v2c `public` agregado como best-effort opcional.
- Presencia mediante ICMP/TCP y candidatos independientes por multicast/protocolos.
- Prefijo/gateway reales mediante Android Connectivity APIs.
- Puertos ampliados y escaneados en paralelo.
- HTTP `Server` convertido en detalle, no identidad.
- Fingerprint TLS SAN/CN y banners SSH/FTP/RTSP.
- Gateway protegido con fuente de prioridad propia.
- Sockets TCP y HTTP ligados al `Network` local seleccionado cuando Android lo permite.
- Clasificación RTSP conservadora: solo ONVIF u otra evidencia explícita afirma que el equipo es una cámara.
- El monitor cancela ciclos programados pendientes al iniciar otro manualmente para impedir solapamientos.
- Los callbacks del motor se validan contra la generación de escaneo activa antes de publicar resultados o progreso.
- Tests de lógica crítica del descubrimiento ampliados a 28 casos Java validados localmente mediante runner ligero y stubs mínimos Android.
- Speed Test agrega 5 pruebas unitarias de cálculo/selección adaptativa; las comprobaciones matemáticas críticas se validaron además con un runner Java local.
- Speed Test bidireccional con Cloudflare principal, fallback dinámico mediante la lista pública oficial de LibreSpeed, selección acotada por latencia, compatibilidad histórica, tamaños adaptativos/rampa multistream y segunda pantalla de descarga real.
- Cancelación por generación e interrupción del worker activo del Speed Test al salir o reiniciar para ignorar callbacks tardíos y reducir solapamientos.
- Interfaz unificada con estilos Material reutilizables, tarjetas XML, estados de presión cancelables, hápticos discretos, transiciones breves y estados de carga/vacío orientativos.
- Animaciones de RecyclerView limitadas a dispositivos nuevos y reiniciadas al reciclar vistas para conservar estabilidad.

# Pendientes

- Ejecutar el build Android real y `lint` con Android SDK y Gradle disponibles.
- Validar Speed Test en hardware real contra conexiones lentas/rápidas y comprobar disponibilidad de los backends públicos desde distintas redes.
- Validar en hardware real el monitor continuo durante múltiples ciclos y conexiones/desconexiones.
- Probar cámaras ONVIF, impresoras WSD/IPP, Cast/AirPlay, NAS/SMB, routers y switches SNMP.
- Medir duración total del escaneo y falsos positivos/falsos negativos antes de ajustar más timeouts.
- La identificación exacta de marca/modelo/SO no puede garantizarse si el dispositivo no publica esa información y no hay MAC disponible. No inventar fingerprints.
- Diseñar permiso de red local antes de `targetSdk 37`.
- Validar la nueva UI premium en teléfonos y tablets reales, incluyendo TalkBack, fuentes grandes, reducción de movimiento y reciclaje intensivo del listado.

# Próximos pasos

1. Ejecutar `gradlew(.bat) :app:testDebugUnitTest`.
2. Ejecutar `gradlew(.bat) :app:assembleDebug`.
3. Ejecutar `gradlew(.bat) :app:lintDebug`.
4. Instalar APK y probar una red conocida durante al menos 5 ciclos de monitor continuo.
5. Comparar cobertura e identidad contra una herramienta de referencia y registrar diferencias por protocolo.
6. Con datos reales, priorizar la siguiente mejora: historial de eventos entrada/salida, alertas locales o fingerprints adicionales que no requieran cloud.
