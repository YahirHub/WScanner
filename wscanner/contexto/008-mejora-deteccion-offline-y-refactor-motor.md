# 008 — Mejora de detección offline y refactor del motor

# Fecha

2026-07-20

# Objetivo

Mejorar la cobertura y precisión del descubrimiento de dispositivos manteniendo el motor de detección completamente local, sin depender de APIs, consultas a Internet ni diccionarios de fabricantes para decidir si un equipo existe o qué tipo de dispositivo es. Corregir fallos del descubrimiento multicapa, reducir código redundante y agregar pruebas mínimas de lógica crítica.

# Decisiones tomadas

- La presencia de un dispositivo se determina por señales observables de red: ICMP, puertos TCP, mDNS, SSDP y cachés locales; la MAC y la base OUI no son requisito.
- Se mantiene `oui_database.json` únicamente como enriquecimiento opcional y legado cuando Android realmente expone una MAC válida.
- Se centraliza la fusión de identidades en `DeviceIdentity` para evitar rankings duplicados y para impedir que nombres genéricos reemplacen identidades específicas.
- El rango de escaneo se obtiene del prefijo IPv4 real reportado por Android. Las redes con más de 1024 hosts se acotan al `/24` que contiene la IP local para evitar barridos accidentales demasiado grandes.
- mDNS y SSDP pueden agregar candidatos directamente aunque esos dispositivos no respondan ICMP.
- El fallback TCP de presencia usa pocos puertos representativos; el escaneo completo de puertos ocurre después y solo sobre candidatos detectados.
- La lectura ARP se reduce de múltiples comandos equivalentes a `/proc/net/arp` y `ip neigh show` como mecanismo de mejor esfuerzo.
- Las descripciones XML SSDP solo se consultan cuando `LOCATION` apunta al mismo host que respondió, sin redirects, para mantener el acceso dentro de la red local y reducir riesgo de solicitudes inducidas a destinos externos.
- Se agregan tests unitarios para parsers, identidad y cálculo de rango.

# Arquitectura actual

El pipeline de `NetworkScanner` queda así:

1. Detectar red IPv4 activa, prefijo y gateway mediante `ConnectivityManager`/`LinkProperties`.
2. Generar hosts del rango con límite seguro para redes grandes.
3. Incorporar IP local y gateway como candidatos conocidos.
4. Ejecutar barrido concurrente con `InetAddress.isReachable` y fallback TCP.
5. Ejecutar mDNS/DNS-SD y SSDP en paralelo.
6. Incorporar resultados multicast válidos del rango aunque no hayan respondido al barrido.
7. Ejecutar mDNS reverse lookup sobre candidatos.
8. Leer caché ARP/vecinos como enriquecimiento opcional de MAC/OUI.
9. Escanear puertos concurrentemente y clasificar mediante señales observadas.
10. Obtener títulos HTTP locales en puertos HTTP abiertos conocidos.
11. Ejecutar NBSTAT/NetBIOS concurrente sobre candidatos sin identidad fuerte.
12. Fusionar todas las actualizaciones por IP mediante `DeviceIdentity`.

Prioridad base actual de fuentes:

`Local (100) > mDNS (90) > SSDP (85) > NetBIOS (80) > DNS (70) > HTTP (60) > OUI DB (50) > TCP (30) > Heurística (10)`.

El ranking se combina con una penalización a nombres genéricos; por tanto, la prioridad de fuente no degrada automáticamente una identidad específica.

# Librerías usadas

- No se agregaron dependencias de producción.
- Se usa `java.net` para sockets y protocolos locales.
- Se usa la API de conectividad de Android para conocer red, prefijo y gateway.
- Se habilitó `testImplementation(libs.junit)` usando la dependencia JUnit ya declarada en el catálogo de versiones.

# Archivos importantes modificados

- `app/src/main/java/com/thowilabs/wscanner/NetworkScanner.java`
- `app/src/main/java/com/thowilabs/wscanner/DeviceIdentity.java` (nuevo)
- `app/src/main/java/com/thowilabs/wscanner/MdnsDiscovery.java`
- `app/src/main/java/com/thowilabs/wscanner/SsdpDiscovery.java`
- `app/src/main/java/com/thowilabs/wscanner/NetBiosDiscovery.java`
- `app/src/main/java/com/thowilabs/wscanner/MainActivity.java`
- `app/src/main/java/com/thowilabs/wscanner/DeviceAdapter.java`
- `app/src/test/java/com/thowilabs/wscanner/DeviceIdentityTest.java` (nuevo)
- `app/src/test/java/com/thowilabs/wscanner/MdnsDiscoveryTest.java` (nuevo)
- `app/src/test/java/com/thowilabs/wscanner/NetBiosDiscoveryTest.java` (nuevo)
- `app/src/test/java/com/thowilabs/wscanner/NetworkRangeTest.java` (nuevo)
- `app/build.gradle.kts`
- `README.md`
- `knowledge.md`
- `contexto/000-contexto-maestro.md`
- `contexto/03-descubrimiento-multicapa.md`
- `contexto/007-inicializacion-y-actualizacion-del-contexto-del-proyecto.md`

# Problemas encontrados

- El parser mDNS recogía registros A internamente pero no publicaba el mapeo `IP -> hostname`, por lo que la resolución de servicios podía terminar sin resultados útiles.
- mDNS esperaba secuencialmente por cada tipo de servicio e instancia; con suficientes servicios podía superar el tiempo que `NetworkScanner` esperaba al hilo y dejar descubrimientos fuera del resultado.
- Las consultas NBSTAT de NetBIOS estaban construidas con offsets incorrectos: faltaba la longitud de la etiqueta codificada y el terminador del nombre DNS/NBNS.
- Los dispositivos encontrados por mDNS/SSDP se descartaban si no habían respondido previamente al ping.
- El barrido asumía una red `/24`, lo que producía rangos incorrectos en otras máscaras.
- `InetAddress.isReachable` era tratado como prueba suficiente de presencia, aunque muchos dispositivos bloquean ICMP.
- El ranking de fuentes estaba duplicado entre UI y modelo de escaneo.
- La estrategia ARP ejecutaba múltiples variantes redundantes de los mismos comandos.
- La extracción SSDP contenía heurísticas de marcas y podía intentar abrir cualquier URL anunciada en `LOCATION`.
- El contexto maestro y README todavía documentaban `ArpReader.java`, 14 métodos ARP, un número de puertos desactualizado y Android 15 para API 36.
- `contexto/007...` contenía texto operativo/meta que no pertenecía a documentación técnica persistente.

# Soluciones implementadas

- Publicación correcta de registros A mDNS y normalización de nombres SRV/instancias.
- Consultas mDNS por lotes con ventanas compartidas de recepción, reutilizando registros PTR/SRV/A adicionales para reducir timeouts acumulativos sin reducir cobertura.
- Consulta NBSTAT corregida y parser de nombres NBNS más robusto.
- Descubrimiento multicast convertido en fuente independiente de candidatos.
- Fallback TCP para equipos que no contestan ICMP.
- Detección de prefijo/gateway mediante propiedades reales de red.
- Escaneo de puertos concurrente y lista de servicios ampliada.
- Clasificación genérica por señales: impresión, SMB/NAS, RTSP, MQTT, RDP, VNC, SSH, DNS, web y servicios mDNS.
- `DeviceIdentity.mergeInto` como única lógica de fusión de identidad y datos complementarios.
- Cancelación del `NetworkScanner` conectada al botón de detener.
- ARP reducido a caché local de mejor esfuerzo.
- SSDP ligado a la IPv4 local detectada para evitar depender de la ruta por defecto en equipos con varias redes.
- XML SSDP limitado al host respondedor y sin redirects.
- Diez pruebas de lógica crítica validadas mediante compilación Java local con stubs mínimos de Android.
- Documentación principal actualizada para reflejar el código real.

# Pendientes

- Ejecutar `:app:testDebugUnitTest`, `:app:assembleDebug` y `:app:lintDebug` en un entorno con Android SDK y acceso al Gradle ya cacheado o disponible; el entorno de revisión no pudo descargar la distribución Gradle por falta de resolución DNS.
- Probar multicast y NetBIOS en dispositivos Android reales y en redes con AP/client isolation.
- Probar subredes `/23`, `/24`, `/25` y una red grande para confirmar el límite deliberado al `/24` local.
- Revisar el futuro permiso de red local al subir `targetSdk` a API 37 o superior.
- Evaluar en una limpieza posterior si se elimina definitivamente el asset OUI y `VendorResolver` para reducir el APK; actualmente permanecen como enriquecimiento opcional y no forman parte del descubrimiento requerido.

# Próximos pasos

1. Ejecutar pruebas unitarias, lint y build Android reales.
2. Hacer una prueba comparativa antes/después en una red conocida y registrar falsos negativos/falsos positivos por método.
3. Validar cancelación durante cada fase del escaneo.
4. Solo después de las pruebas reales, ajustar timeouts o concurrencia con datos medidos.
