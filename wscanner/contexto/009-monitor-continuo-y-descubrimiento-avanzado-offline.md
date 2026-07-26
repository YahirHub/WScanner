# Fecha

2026-07-20

# Objetivo

Corregir el estado visual incorrecto del monitor continuo, donde al comenzar cada nuevo ciclo todos los dispositivos conocidos se marcaban temporalmente como offline antes de que el barrido llegara a sus IPs. Ampliar además el motor de descubrimiento e identificación local para obtener más señales verificables sin depender de Internet, APIs cloud ni diccionarios remotos.

# Decisiones tomadas

- El estado online/offline se decide por **ciclo completo**, no al iniciar el barrido.
- Un dispositivo conserva su último estado confirmado mientras el nuevo ciclo está en progreso.
- Solo al finalizar un ciclo se atenúan los equipos conocidos que no fueron observados por ninguna capa durante todo el ciclo.
- Un reescaneo manual conserva el inventario de la misma subred; si cambia el CIDR, el inventario se reinicia para no mezclar redes.
- El gateway usa una fuente propia `Gateway` de alta prioridad para impedir que software HTTP como `lighttpd` reemplace su identidad.
- Se amplía el descubrimiento con WS-Discovery/ONVIF y SNMP v2c de mejor esfuerzo.
- mDNS conserva los tipos de servicio DNS-SD asociados a cada host, no solo el nombre.
- mDNS procesa también TXT DNS-SD autoanunciado para extraer metadatos verificables como nombre amigable, modelo, fabricante, plataforma y MAC publicada por el servicio.
- SSDP conserva metadatos estructurados autoanunciados: `friendlyName`, fabricante, modelo y tipo UPnP.
- Los headers HTTP `Server` se consideran software/evidencia técnica, no nombre del dispositivo.
- Se aceptan `title` y realm HTTP como identidades locales de menor prioridad cuando son útiles.
- Se inspecciona el certificado TLS local (SAN/CN) como señal adicional, sin modificar el trust store global ni enviar credenciales.
- Se mantienen las soluciones simples y sin nuevas dependencias de producción.
- Un puerto RTSP por sí solo ya no se considera prueba suficiente de que el equipo sea una cámara; ONVIF/NetworkVideoTransmitter u otra señal explícita sí lo es.
- NBSTAT se amplía para recuperar Unit ID/MAC y grupo de trabajo además del nombre NetBIOS.
- El monitor cancela cualquier ciclo programado pendiente cuando se inicia uno manualmente durante la pausa, evitando escaneos superpuestos.

# Arquitectura actual

El estado de monitor se separa del descubrimiento:

```text
MainActivity
  ├─ conserva inventario por CIDR
  ├─ ScanCycleState.beginCycle()
  ├─ cada onDeviceFound(ip) -> ScanCycleState.markSeen(ip)
  └─ onFinished -> ScanCycleState.finishCycle(devices)
                     ├─ vistos: online
                     └─ no vistos: offline/gris
```

Pipeline vigente:

1. Detectar `Network` WiFi/Ethernet activo, IPv4, prefijo y gateway.
2. Enumerar rango real con límite deliberado para redes grandes.
3. Añadir IP local y gateway.
4. Presencia concurrente ICMP/TCP y estimulación de caché de vecino.
5. Ejecutar mDNS, SSDP, WS-Discovery y SNMP en paralelo.
6. Incorporar respuestas de protocolos aunque ICMP haya fallado.
7. Ejecutar mDNS inverso.
8. Enriquecer opcionalmente con ARP/MAC/OUI.
9. Escanear 36 puertos en paralelo.
10. Enriquecer con HTTP title/realm, certificado TLS y banners SSH/FTP/RTSP.
11. Ejecutar NetBIOS y recuperar Unit ID/MAC cuando el responder lo publique.
12. Fusionar por IP mediante `DeviceIdentity`.
13. Finalizar ciclo y actualizar online/offline.

Prioridad base:

`Local (100) > Gateway (98) > mDNS (90) > WS-Discovery (88) > SSDP (85) > SNMP (82) > NetBIOS (80) > DNS (70) > TLS (65) > HTTP (60) > OUI DB (50) > TCP (30) > Heurística (10)`.

# Librerías usadas

- No se agregaron dependencias de producción.
- APIs estándar Java/Android: `java.net`, `javax.net.ssl`, `java.security.cert`, Android Connectivity APIs y Wifi `MulticastLock`.
- JUnit existente para tests locales del módulo.

# Archivos importantes modificados

- `app/src/main/java/com/thowilabs/wscanner/MainActivity.java`
- `app/src/main/java/com/thowilabs/wscanner/ScanCycleState.java` (nuevo)
- `app/src/main/java/com/thowilabs/wscanner/NetworkScanner.java`
- `app/src/main/java/com/thowilabs/wscanner/DeviceIdentity.java`
- `app/src/main/java/com/thowilabs/wscanner/Device.java`
- `app/src/main/java/com/thowilabs/wscanner/DeviceAdapter.java`
- `app/src/main/java/com/thowilabs/wscanner/MdnsDiscovery.java`
- `app/src/main/java/com/thowilabs/wscanner/SsdpDiscovery.java`
- `app/src/main/java/com/thowilabs/wscanner/WsDiscovery.java` (nuevo)
- `app/src/main/java/com/thowilabs/wscanner/SnmpDiscovery.java` (nuevo)
- `app/src/test/java/com/thowilabs/wscanner/ScanCycleStateTest.java` (nuevo)
- `app/src/test/java/com/thowilabs/wscanner/WsDiscoveryTest.java` (nuevo)
- `app/src/test/java/com/thowilabs/wscanner/SnmpDiscoveryTest.java` (nuevo)
- `app/src/test/java/com/thowilabs/wscanner/SsdpDiscoveryTest.java` (nuevo)
- Tests existentes de identidad, mDNS y rango ampliados.
- `README.md`, `README.txt`, `knowledge.md` y contexto persistente.

# Problemas encontrados

- `MainActivity` ejecutaba una operación equivalente a `markAllPending()` al empezar cada ciclo continuo, poniendo `online=false` a todos los equipos antes de redescubrirlos.
- `DeviceAdapter` reiniciaba alpha a cero en cada `notifyItemChanged`, provocando parpadeos cuando una misma IP recibía múltiples señales durante el ciclo.
- Los reescaneos manuales vaciaban el inventario en lugar de conservar equipos conocidos y determinar su ausencia al final.
- El gateway podía terminar llamado `lighttpd` porque un header `Server` HTTP se trataba como identidad y la fuente inicial del gateway no tenía suficiente prioridad.
- mDNS descartaba la relación entre host e identificadores de servicio después de resolver el nombre.
- SSDP devolvía solo un nombre y perdía fabricante/modelo/tipo contenidos en la descripción UPnP.
- El motor no aprovechaba WS-Discovery/ONVIF ni SNMP para dispositivos que no anuncian mDNS/SSDP.
- Los sockets TCP/HTTP podían seguir la ruta predeterminada del sistema en escenarios con varias redes activas.
- La clasificación por puerto 554 sobreafirmaba que cualquier servidor RTSP era una cámara, aunque RTSP también aparece en NVR y servidores multimedia.
- NetBIOS descartaba la MAC contenida en el Unit ID de NBSTAT.
- mDNS ignoraba metadatos TXT DNS-SD que muchos dispositivos publican junto con SRV/PTR.
- Durante la pausa del monitor era posible iniciar manualmente un ciclo y dejar además el callback del siguiente ciclo ya programado.
- Un worker de presencia que terminara justo después de cancelar un escaneo podía todavía publicar un resultado tardío y contaminar el `seenIps` del ciclo siguiente.

# Soluciones implementadas

- `ScanCycleState`: set de IPs vistas por ciclo y aplicación de ausencia únicamente en `finishCycle()`.
- Inventario persistente en reescaneos sobre el mismo CIDR y limpieza automática al cambiar de red.
- Eliminación de la animación que reiniciaba alpha a 0 en cada actualización de una tarjeta existente.
- Fuente `Gateway` con prioridad 98.
- HTTP `Server` relegado a detalle; `title`/realm son señales de identidad de menor prioridad.
- mDNS estructurado con lista de servicios por host para clasificación basada en DNS-SD.
- Parser TXT DNS-SD con metadatos autoanunciados y límite de campos/tamaño para evitar crecimiento no acotado.
- SSDP estructurado con `friendlyName`, fabricante, modelo, tipo UPnP y servicios.
- Nuevo WS-Discovery con extracción de scopes ONVIF como nombre/hardware.
- Nuevo SNMP best-effort para `sysName.0` y `sysDescr.0`.
- Probes TCP/HTTP ligados al `Network` Android seleccionado cuando está disponible.
- Fingerprint adicional de certificado TLS SAN/CN y banners locales SSH/FTP/RTSP.
- Lista de servicios/puertos ampliada a 36 puertos para el fingerprint detallado.
- Tests nuevos para ciclo continuo, gateway vs HTTP, servicios mDNS, SSDP estructurado, WS-Discovery, SNMP y CIDR.
- NBSTAT estructurado con nombre, grupo y Unit ID/MAC; la OUI embebida se usa solo como enriquecimiento local si esa MAC existe.
- Clasificación RTSP neutral salvo evidencia explícita de cámara.
- Prevención de ciclos de monitor solapados durante el intervalo entre barridos.
- Publicación de resultados/progreso protegida por la generación activa; callbacks tardíos de un escaneo cancelado ya no se mezclan con un ciclo nuevo.

# Pendientes

- Ejecutar build, tests Gradle y lint con Android SDK real. El entorno de trabajo actual no dispone de acceso funcional a la distribución Gradle requerida para completar ese build.
- Probar el nuevo monitor continuo durante varios minutos con conexiones/desconexiones reales.
- Medir falsos positivos de SNMP, TLS y títulos/realm HTTP en redes heterogéneas.
- Medir cuántos modelos/fabricantes reales se recuperan vía TXT DNS-SD y NBSTAT frente a dispositivos de referencia.
- Evaluar una estrategia de historial de presencia/eventos (entrada/salida) persistente si se desea acercar el producto a funciones de monitorización continua tipo inventario.
- La identificación exacta de marca/modelo/SO seguirá limitada cuando el dispositivo no autoanuncie esos datos y Android no exponga MAC. No inventar esa información.
- Antes de subir a `targetSdk 37`, implementar el permiso de red local de Android 17.

# Próximos pasos

1. Ejecutar `:app:testDebugUnitTest`, `:app:assembleDebug` y `:app:lintDebug`.
2. Probar una red conocida y comparar número de equipos y calidad de identidad contra una herramienta de referencia.
3. Registrar por IP qué capa encontró cada equipo y cualquier falso negativo.
4. Ajustar timeouts/concurrencia solo con datos de pruebas reales.
5. Considerar eventos de entrada/salida y notificaciones locales como siguiente feature de monitorización, sin introducir cloud si no es necesario.
