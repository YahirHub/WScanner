# Contexto Maestro — WScanner

**Fecha:** 2026-07-20  
**Versión:** 1.1  
**Última actualización:** 2026-07-20 (mejora de detección offline y refactor del motor)

---

## Resumen ejecutivo

WScanner es una aplicación Android para descubrir dispositivos de una red local. El motor de detección trabaja con señales locales observables —ICMP, TCP, mDNS/DNS-SD, SSDP/UPnP, DNS, NetBIOS y HTTP local— y no necesita consultar APIs ni servicios de Internet para detectar equipos. La base OUI embebida se conserva como enriquecimiento opcional cuando existe una MAC accesible, pero no es requisito del descubrimiento ni de la clasificación.

La aplicación también incluye herramientas independientes de diagnóstico. Algunas, como Speed Test, pueden usar Internet; esto no cambia el carácter local/offline del motor de detección de dispositivos.

---

## Estado actual del proyecto

| Aspecto | Valor |
|---------|-------|
| Lenguaje | Java 11 |
| UI toolkit | XML Views (ConstraintLayout, CoordinatorLayout, RecyclerView) |
| Arquitectura | Single Activity con vistas embebidas + clases de dominio/herramientas separadas |
| Motor de identidad | `DeviceIdentity` centraliza ranking, fusión y clasificación por señales |
| Tema | `Theme.MaterialComponents.DayNight.NoActionBar` con diseño oscuro |
| Navegación | Visibility toggling entre Scanner / Device Detail / About / herramientas |
| compileSdk | 36 |
| minSdk | 24 (Android 7.0) |
| targetSdk | 36 (Android 16) |
| Gradle | KTS, AGP 9.2.1 |
| Testing | 4 clases de test unitario nuevas para identidad, mDNS, NetBIOS y rango de red |

---

## Estructura del proyecto

```text
WScanner/
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── assets/oui_database.json   # enriquecimiento opcional, no requisito
│   │   ├── java/com/thowilabs/wscanner/
│   │   │   ├── MainActivity.java
│   │   │   ├── NetworkScanner.java
│   │   │   ├── DeviceIdentity.java
│   │   │   ├── MdnsDiscovery.java
│   │   │   ├── SsdpDiscovery.java
│   │   │   ├── NetBiosDiscovery.java
│   │   │   ├── VendorResolver.java
│   │   │   ├── Device.java
│   │   │   ├── DeviceAdapter.java
│   │   │   ├── HapticUtil.java
│   │   │   ├── SpeedometerGauge.java
│   │   │   ├── SpeedTestTool.java
│   │   │   ├── TracerouteTool.java
│   │   │   ├── WakeOnLanTool.java
│   │   │   └── ScanHistory.java
│   │   └── res/
│   └── src/test/java/com/thowilabs/wscanner/
│       ├── DeviceIdentityTest.java
│       ├── MdnsDiscoveryTest.java
│       ├── NetBiosDiscoveryTest.java
│       └── NetworkRangeTest.java
├── gradle/
├── contexto/
├── README.md
└── knowledge.md
```

---

## Fuentes Java (15 clases activas)

| Clase | Rol |
|-------|-----|
| `MainActivity.java` | UI, navegación, ciclo de escaneo, detalle, monitor y herramientas |
| `NetworkScanner.java` | Orquestador del descubrimiento multicapa y cancelación |
| `DeviceIdentity.java` | Ranking de fuentes, fusión de resultados y clasificación por señales |
| `MdnsDiscovery.java` | mDNS/DNS-SD manual: service discovery y reverse lookup |
| `SsdpDiscovery.java` | SSDP M-SEARCH y descripción UPnP local segura |
| `NetBiosDiscovery.java` | NBSTAT/NetBIOS concurrente |
| `DeviceAdapter.java` | RecyclerView, filtro y presentación |
| `Device.java` | Modelo de dispositivo |
| `VendorResolver.java` | OUI opcional cuando se obtiene una MAC válida |
| `HapticUtil.java` | Feedback háptico cross-API |
| `SpeedometerGauge.java` | Vista personalizada para Speed Test |
| `SpeedTestTool.java` | Prueba de velocidad de conectividad externa |
| `TracerouteTool.java` | Traceroute UDP |
| `WakeOnLanTool.java` | Envío de magic packets WoL |
| `ScanHistory.java` | Historial persistente de escaneos |

`ArpReader.java` no existe en el código actual. La lectura de caché ARP/vecinos está encapsulada como enriquecimiento de mejor esfuerzo dentro de `NetworkScanner`.

---

## Pipeline de escaneo (NetworkScanner)

```text
Fase 0:      Detectar IPv4 local + prefijo real + gateway con ConnectivityManager/LinkProperties
             Fallback compatible mediante WifiManager/DhcpInfo

Fase 1:      Barrido concurrente del rango
             ├─ InetAddress.isReachable()
             └─ fallback TCP en puertos representativos cuando ICMP falla

Fase 1.5:    [PARALELO] mDNS/DNS-SD service discovery
Fase 1.55:   mDNS reverse lookup sobre candidatos locales
Fase 1.6:    [PARALELO] SSDP/UPnP M-SEARCH
             mDNS/SSDP pueden agregar candidatos aunque no hayan respondido al barrido

Fase 2:      Caché ARP/vecinos de mejor esfuerzo
             └─ MAC/OUI solo enriquecen; no son requisito de detección

Fase 3:      Port scanning concurrente sobre candidatos
             + clasificación por puertos/servicios observados
             + HTTP title local únicamente en puertos HTTP abiertos conocidos

Fase 3.5:    NBSTAT/NetBIOS concurrente en candidatos sin identidad fuerte

Final:       Fusionar por IP mediante DeviceIdentity → liberar MulticastLock → onFinished()
```

Las redes con más de 1024 hosts utilizables se acotan deliberadamente al `/24` de la IP local. Esto evita barridos activos masivos; revisar si en el futuro se agrega una opción explícita para redes empresariales grandes.

### Prioridad de descubrimiento (source ranking)

| Prioridad | Método | Puntaje base |
|-----------|--------|--------------|
| 1 | Local | 100 |
| 2 | mDNS | 90 |
| 3 | SSDP | 85 |
| 4 | NetBIOS | 80 |
| 5 | DNS | 70 |
| 6 | HTTP | 60 |
| 7 | OUI DB opcional | 50 |
| 8 | TCP | 30 |
| 9 | Heurística | 10 |

`DeviceIdentity` penaliza nombres genéricos; una fuente con mayor puntaje no reemplaza automáticamente un nombre específico y útil.


---

## Dependencias

### build.gradle.kts (app)

```kotlin
dependencies {
    testImplementation(libs.junit)
    implementation(libs.androidx.appcompat)     // 1.6.1
    implementation(libs.material)               // 1.10.0
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.mikepenz:iconics-core:5.5.0")
    implementation("com.mikepenz:community-material-typeface:7.0.96.1-kotlin@aar")
}
```

**Nota:** `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` fue eliminada (commit: refactor swipe-to-refresh).

### libs.versions.toml

```toml
[versions]
agp = "9.2.1"
coreKtx = "1.10.1"        # Solo usado por stubs Kotlin de test
appcompat = "1.6.1"
material = "1.10.0"

[plugins]
android-application = "com.android.application" → 9.2.1
```

---

## Permisos (AndroidManifest.xml)

| Permiso | Tipo | Motivo |
|---------|------|--------|
| `INTERNET` | Normal | HTTP banner grab, socket connections |
| `ACCESS_NETWORK_STATE` | Normal | Detectar estado WiFi |
| `ACCESS_WIFI_STATE` | Normal | Leer IP local, DHCP info |
| `CHANGE_WIFI_MULTICAST_STATE` | Normal | MulticastLock para mDNS/SSDP |

Sin permisos de ubicación, contactos ni almacenamiento.

---

## Layouts (7 principales + 3 qualifier)

| Layout | Propósito | Qualifier |
|--------|-----------|-----------|
| `activity_main.xml` | Layout principal phone (ConstraintLayout) | default |
| `activity_main.xml` | Tablet 7" split-pane (lista 40% + detalle 60%) | sw600dp |
| `activity_main.xml` | Tablet 10" split-pane (lista 35% + detalle 65%) | w840dp |
| `layout_device_detail.xml` | Vista de detalle (ScrollView + include inner) | default |
| `layout_device_detail_inner.xml` | Contenido interno del detalle (compartido phone/tablet) | default |
| `empty_state.xml` | Estado vacío con ícono radar + texto | default |
| `placeholder_cards.xml` | 4 skeletons durante escaneo | default |
| `nav_header.xml` | Header del NavigationView | default |
| `about_content.xml` | Contenido de pantalla "Acerca de" (compartido) | default |

---

## Archivos de animación (res/anim/)

| Archivo | Efecto |
|---------|--------|
| `slide_in_right.xml` | Translate 15%→0 + alpha 0→1 (300ms) |
| `slide_out_left.xml` | Translate 0→-15% + alpha 1→0 (300ms) |
| `slide_in_left.xml` | Translate -15%→0 + alpha 0→1 (300ms) |
| `slide_out_right.xml` | Translate 0→15% + alpha 1→0 (300ms) |
| `fade_in.xml` | Alpha 0→1 (250ms) |
| `fade_out.xml` | Alpha 1→0 (200ms) |
| `scale_up.xml` | Scale 0.92→1 + alpha 0→1 (300ms) |
| `scale_down.xml` | Scale 1→0.95 + alpha 1→0 (250ms) |

**Nota:** Los archivos existen como recursos pero las transiciones entre pantallas usan `androidx.transition.TransitionManager` programáticamente.

---

## Animaciones activas en runtime

### MainActivity.java
- **Transiciones entre pantallas**: `TransitionManager.beginDelayedTransition()` con `AutoTransition`, `Fade`, `Slide`
- **FAB rotation**: `ObjectAnimator` 360° infinito durante escaneo (3000ms)
- **Empty state pulse**: `ObjectAnimator` alpha 0.3↔0.7 en `imgEmptyRadarBg` (1500ms, REVERSE)
- **Placeholder shimmer**: Pulsos alpha 0.4→0.7 escalonados por tarjeta
- **Fade-in header red**: Alpha 0→1 + translateY -20→0
- **Fade-in tarjetas**: Alpha 0→1 + translateY 20→0 con staggered delay (max 300ms)
- **Fade-in empty state**: Alpha 0→1
- **Press scale FAB**: 0.88x on press

### DeviceAdapter.java
- **Staggered reveal**: `setStartDelay(Math.min(i * 40L, 300L))` en fade-in
- **Ripple foreground**: `selectableItemBackground` como foreground de tarjeta
- **StateListAnimator**: Elevación 2dp→8dp en press, 8dp→2dp en release
- **Press scale cards**: 0.96x con `OvershootInterpolator(1.2f)`

---

## Paleta de colores

| Rol | Color | Hex |
|-----|-------|-----|
| Fondo principal | Dark near-black | `#0D1117` |
| Fondo secundario | Dark slate | `#161B22` |
| Tarjetas | Dark navy | `#1C2333` |
| Superficies | Slightly lighter | `#21262D` |
| Acento primario | Cyan neón | `#00E5FF` |
| Acento oscuro | Cyan profundo | `#00B8D4` |
| Texto primario | Blanco grisáceo | `#E6EDF3` |
| Texto secundario | Gris medio | `#8B949E` |
| Texto terciario | Gris oscuro | `#6E7681` |
| Online | Verde terminal | `#3FB950` |
| Warning | Ámbar | `#D29922` |
| Error | Rojo terminal | `#F85149` |
| Divider | Gris borde | `#30363D` |

---

## Problemas conocidos

1. **MAC/OUI no fiables en Android moderno:** la caché ARP/vecinos puede estar vacía o restringida. El motor ya no depende de ella; se conserva solo para enriquecer resultados cuando haya MAC.
2. **AP/client isolation:** routers y puntos de acceso pueden bloquear tráfico entre clientes, mDNS o SSDP. Ningún escáner desde un cliente puede descubrir por sockets directos equipos que la propia red aísla completamente.
3. **Multicast dependiente de la red:** mDNS/SSDP requieren que el router y la interfaz permitan multicast; WScanner adquiere `MulticastLock`, pero no puede corregir políticas del AP.
4. **Redes grandes:** por seguridad y consumo, subredes con más de 1024 hosts se limitan al `/24` alrededor del teléfono.
5. **Validación Android pendiente:** los tests de lógica pasan con compilación Java local, pero el build Gradle completo no pudo ejecutarse en el entorno de revisión porque el wrapper no pudo resolver `services.gradle.org`.
6. **Sin ProGuard/R8:** `isMinifyEnabled = false` en release.
7. **`layout_device_detail.xml` mantiene `android:visibility="gone"` en la raíz:** el include y la navegación actual gestionan su visibilidad en runtime.
8. **Animaciones XML históricas:** existen recursos en `res/anim/`; parte de las transiciones usa `TransitionManager` programático.

---

## Pendientes

- Ejecutar tests unitarios, lint y build con Android SDK/Gradle disponibles.
- Probar en dispositivo real con Android, iOS/macOS, Windows, Smart TV/Chromecast, NAS e impresoras.
- Validar equipos que bloquean ICMP pero exponen TCP, y dispositivos descubiertos solo por mDNS/SSDP.
- Probar máscaras `/23`, `/24`, `/25` y comportamiento deliberado en redes grandes.
- Agregar tests instrumentados para UI y cancelación del escaneo.
- Revisar permisos de red local al migrar en el futuro a target SDK 37 o superior.
- Evaluar si se elimina definitivamente `oui_database.json` y `VendorResolver` para reducir tamaño del APK; hoy son opcionales y no condicionan la detección.
- Habilitar R8/ProGuard para release cuando se prepare distribución.

---

## Documentos relacionados

| Archivo | Contenido |
|---------|-----------|
| `contexto/01-contexto-inicial.md` | Arquitectura inicial V1 |
| `contexto/02-motor-escaneo.md` | Historia de la base OUI, hoy enriquecimiento opcional |
| `contexto/03-descubrimiento-multicapa.md` | Introducción histórica de mDNS + SSDP + NetBIOS |
| `contexto/04-rediseno-ui-profesional.md` | Rediseño UI oscuro |
| `contexto/05-fase1-mejoras-ux-escaneo.md` | Mejoras de UX y herramientas de escaneo |
| `contexto/06-rediseno-responsive-animaciones.md` | Layouts responsive y transiciones |
| `contexto/007-inicializacion-y-actualizacion-del-contexto-del-proyecto.md` | Convención de contexto persistente |
| `contexto/008-mejora-deteccion-offline-y-refactor-motor.md` | Estado vigente del motor de detección offline y refactor 2026-07-20 |

---

## Historial de commits recientes

1. `21a4292` — refactor: eliminar swipe-to-refresh, solo FAB inicia escaneo
2. (commits anteriores de Fase 1 y mejoras UI — ver `git log`)

---

## Comandos útiles

```bash
# Compilar debug APK
./gradlew :app:assembleDebug

# Instalar en dispositivo
./gradlew :app:installDebug

# Lint
./gradlew :app:lintDebug

# Tests unitarios
./gradlew :app:testDebugUnitTest

# Logs para diagnóstico
adb logcat WScanner.mDNS:* WScanner.SSDP:* WScanner.NetBIOS:* WScanner.Scanner:* WScanner.Vendor:* WScanner.UI:* *:S
```
