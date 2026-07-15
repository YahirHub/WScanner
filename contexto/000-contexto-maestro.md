# Contexto Maestro — WScanner

**Fecha:** 2026-07-15
**Versión:** 1.0
**Última actualización:** 2026-07-15 (tras rediseño responsive + animaciones)

---

## Resumen ejecutivo

WScanner es un escáner de red local gratuito para Android, diseñado como alternativa libre a Fing. Descubre dispositivos conectados a la red WiFi mediante un motor de escaneo multicapa: ping (ICMP), mDNS (Bonjour/AirPlay/Google Cast), SSDP (UPnP/DLNA/Chromecast), NetBIOS (Windows), HTTP fingerprinting y port scanning. 100% offline, sin anuncios, sin rastreo, sin permisos innecesarios.

---

## Estado actual del proyecto

| Aspecto | Valor |
|---------|-------|
| Lenguaje | Java 11 |
| UI toolkit | XML Views (ConstraintLayout, CoordinatorLayout, RecyclerView) |
| Arquitectura | Single Activity (`MainActivity`) con views embebidas (sin Fragments) |
| Tema | `Theme.MaterialComponents.DayNight.NoActionBar` (siempre oscuro) |
| Navegación | Visibility toggling entre Scanner / Device Detail / About |
| Back handling | Manual (`onBackPressed`) con flags `showingAbout` / `showingDeviceDetail` |
| compileSdk | 36 |
| minSdk | 24 (Android 7.0) |
| targetSdk | 36 (Android 15) |
| Gradle | KTS, AGP 9.2.1 |
| Testing | Solo stubs de ejemplo (no hay tests reales) |

---

## Estructura del proyecto

```
WScanner/
├── app/
│   ├── build.gradle.kts               # AGP config, dependencias
│   ├── src/main/
│   │   ├── AndroidManifest.xml        # Permisos (INTERNET, WIFI_STATE, MULTICAST)
│   │   ├── assets/oui_database.json   # 53,371 OUIs (1.6 MB)
│   │   ├── java/com/thowilabs/wscanner/
│   │   │   ├── MainActivity.java      # UI principal, navegación, escaneo, transiciones
│   │   │   ├── NetworkScanner.java    # Orquestador de fases de descubrimiento
│   │   │   ├── MdnsDiscovery.java     # mDNS/DNS-SD (RFC 6762) manual
│   │   │   ├── SsdpDiscovery.java     # SSDP M-SEARCH + scoring
│   │   │   ├── NetBiosDiscovery.java  # NetBIOS NBSTAT query
│   │   │   ├── VendorResolver.java    # Resolución OUI → vendor
│   │   │   ├── Device.java            # Modelo de dispositivo
│   │   │   ├── DeviceAdapter.java     # RecyclerView adapter + Filterable
│   │   │   ├── HapticUtil.java        # Feedback háptico cross-API
│   │   │   └── ArpReader.java         # ELIMINADO (código duplicado, nunca usado)
│   │   └── res/
│   │       ├── anim/                   # 8 animaciones XML (slide, fade, scale)
│   │       ├── color/                  # Tinting de drawer
│   │       ├── drawable/              # 17 drawables (gradients, cards, icons, badges)
│   │       ├── layout/                # 7 layouts + 3 alternativos por qualifier
│   │       ├── menu/                  # drawer_menu.xml
│   │       ├── mipmap-anydpi-v26/     # Launcher icons
│   │       └── values/                # colors, strings, themes, dimens
│   └── src/test/ & androidTest/       # Solo stubs Kotlin (no tests reales)
├── gradle/
│   ├── libs.versions.toml             # Version catalog
│   └── wrapper/                       # Gradle wrapper
├── settings.gradle.kts
├── build.gradle.kts                   # Root (aplica plugin android-application)
├── contexto/                          # Documentación de arquitectura (ES)
├── README.md                          # Documentación en español
├── knowledge.md                       # Conocimiento rápido del proyecto
└── sesion.jsonl                       # Log de sesión Codewolf
```

---

## Fuentes Java (10 clases activas)

| Clase | Líneas | Rol | Estado |
|-------|--------|-----|--------|
| `MainActivity.java` | ~500 | UI, navegación, escaneo, transiciones, búsqueda, chips, detail, ping, haptics, shimmer, pulse | **Activa** |
| `NetworkScanner.java` | ~380 | Orquestador: ping sweep → mDNS → SSDP → ARP → port scan → HTTP → NetBIOS | **Activa** |
| `MdnsDiscovery.java` | ~520 | mDNS/DNS-SD manual (RFC 6762): service discovery + reverse lookup | **Activa** |
| `SsdpDiscovery.java` | ~310 | SSDP M-SEARCH + scoring de nombres por IP + XML friendlyName | **Activa** |
| `NetBiosDiscovery.java` | ~250 | NBSTAT query UDP puerto 137, codificación half-ASCII | **Activa** |
| `DeviceAdapter.java` | ~350 | RecyclerView adapter + Filterable + Iconics + ripple + StateListAnimator + staggered reveal | **Activa** |
| `Device.java` | ~50 | Modelo: name, ip, mac, vendor, discoveryMethod, discoveryDetail, ttl, openPorts, serviceNames | **Activa** |
| `VendorResolver.java` | ~90 | Carga OUI JSON (53k entradas) y resuelve MAC → fabricante | **Activa** |
| `HapticUtil.java` | ~70 | Feedback háptico cross-API (24-25 legacy, 26+ nativo) | **Activa** |
| `ArpReader.java` | ~60 | **ELIMINADO** — código duplicado en NetworkScanner | Inactivo |

---

## Pipeline de escaneo (NetworkScanner)

```
Fase 0:      Detectar subred + gateway (WifiManager + DhcpInfo)
Fase 0.5:    Cargar VendorResolver + adquirir MulticastLock

Fase 1:      Ping sweep (254 IPs, 10 hilos, 300ms timeout) → emitir dispositivos básicos
Fase 1.5:    [PARALELO] mDNS service discovery (PTR _services._dns-sd._udp.local)
Fase 1.55:   mDNS reverse lookup (PTR X.X.X.X.in-addr.arpa) para cada IP viva
Fase 1.6:    [PARALELO] SSDP discovery (M-SEARCH a 239.255.255.250:1900)

Fase 2:      ARP (14 métodos) — siempre vacío en Android 10+ (SELinux bloquea /proc/net/arp)
Fase 3:      Port scanning (15 puertos: 80,443,22,445,8080,23,21,554,1883,53,3389,5900,5000,5353,9100)
             + HTTP banner grab + extract <title>
Fase 3.5:    NetBIOS probe (solo IPs sin identificación previa)

Fase 4:      Liberar MulticastLock → onFinished()
```

### Prioridad de descubrimiento (source ranking)

| Prioridad | Método | Puntaje |
|-----------|--------|---------|
| 1 | mDNS | 7 |
| 2 | SSDP | 6 |
| 3 | NetBIOS | 5 |
| 4 | OUI DB (MAC) | 4 |
| 5 | DNS inverso | 3 |
| 6 | HTTP banner | 2 |
| 7 | Heurística | 1 |

---

## Dependencias

### build.gradle.kts (app)

```kotlin
dependencies {
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

1. **Android 10+ bloquea `/proc/net/arp`** — SELinux `proc_net`, `untrusted_app`. Los 14 métodos ARP siempre devuelven 0 resultados. Las MAC de dispositivos no son accesibles sin root.
2. **AP isolation** — Muchos routers bloquean tráfico multicast entre clientes WiFi. mDNS/SSDP no ven nada en estos casos.
3. **Timeout total ~22s** — El ping sweep + mDNS + SSDP + port scan + NetBIOS toma ~20-22 segundos en total.
4. **Sin tests** — Solo existen stubs de ejemplo (`ExampleUnitTest.kt`, `ExampleInstrumentedTest.kt`).
5. **Sin ProGuard/R8** — `isMinifyEnabled = false` en release.
6. **`layout_device_detail.xml` tiene un `android:visibility="gone"` en el ScrollView raíz** — El `<include>` en activity_main.xml sobreescribe este ID, por lo que `findViewById(R.id.layoutDeviceDetail)` funciona correctamente.
7. **Anim XML files no usados** — Los 8 archivos en `res/anim/` existen pero las transiciones usan `TransitionManager` programático.
8. **w840dp no usa @dimen references** — El layout w840dp hardcodea tamaños en vez de usar `@dimen/chip_text_size` del archivo `values-w840dp/dimens.xml`.

---

## Pendientes

- Agregar tests unitarios para el motor de escaneo (`NetworkScanner`, `MdnsDiscovery`, `SsdpDiscovery`)
- Agregar tests instrumentados para la UI
- Probar en dispositivo real con varios tipos de dispositivos (Apple, Windows, Smart TV, impresora)
- Agregar SNMP discovery (puerto 161)
- Agregar DHCP monitoring para detección pasiva
- Habilitar R8/ProGuard para release
- Persistencia de resultados de escaneo (historial)
- Estadísticas de escaneo (tiempo total, dispositivos por método)

---

## Documentos relacionados

| Archivo | Contenido |
|---------|-----------|
| `contexto/01-contexto-inicial.md` | Arquitectura inicial V1 |
| `contexto/02-motor-escaneo.md` | Fusión de bases OUI (53,371 entradas) |
| `contexto/03-descubrimiento-multicapa.md` | mDNS + SSDP + NetBIOS discovery |
| `contexto/04-rediseno-ui-profesional.md` | Rediseño UI oscuro cyber/network |
| `contexto/05-fase1-mejoras-ux-escaneo.md` | Ordenamiento, búsqueda, detalle, puertos, ping |
| `contexto/06-rediseno-responsive-animaciones.md` | ConstraintLayout, tablet layouts, transiciones, animaciones |

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

<!-- codewolf:auto-context:start -->
# Estado automático más reciente

- Última actualización: 2026-07-15T16:47:41.965Z
- Último registro: contexto/008-hay-un-detalle-y-es-que-no-hay-manera-de-detener-el-escaneo-en-p.md
- Resumen: The user's request is complete. Let me provide a summary of what was done.
## Resumen

**3 archivos modificados/creados:**

| Archivo | Cambio |
|---------|--------|
| `ic_stop.xml` (nuevo) | Vector drawable de un cuadrado blanco para el botón de parar |
| `MainActivity.java` | Lógica del FAB rediseñada |

**Nuevo comportamiento del FAB:**

- **Normal**: icono radar cyan. Click → inicia escaneo. Long-press → inicia modo monitor.
- **Escaneando**: icono cuadrado rojo ⏹. Click → detiene el escaneo inmediatamente. Long-press → ignorado (evita escaneos duplicados).
- **Al terminar** (natural o por stop): vuelve al icono radar con el color que corresponda (cyan normal, rojo si quedó en modo monitor activo).

**Correcciones de edge cases:**
- `onFinished` ya no sobrescribe el estado del UI si el usuario detuvo el scan manualmente
- Long-press bloqueado durante escaneo para evitar un segundo hilo solapado
- Archivos del cambio: app/src/main/java/com/thowilabs/wscanner/MainActivity.java, app/src/main/res/drawable/ic_stop.xml
<!-- codewolf:auto-context:end -->
