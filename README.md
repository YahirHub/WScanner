# WScanner

**Escáner de red local gratuito para Android — sin anuncios, sin rastreo.**

WScanner descubre todos los dispositivos conectados a tu red WiFi con un motor de escaneo multicapa: ping, mDNS (Bonjour), SSDP (UPnP), NetBIOS, HTTP fingerprinting y más. Diseñado como alternativa libre a Fing, 100% offline y sin permisos innecesarios.

<p align="center">
  <img src="docs/screenshot_light.png" width="280" alt="WScanner escaneo">
</p>

---

## ✨ Características

- 🔍 **Descubrimiento multicapa** — ping ARP + mDNS (Bonjour/AirPlay/Google Cast) + SSDP (UPnP/DLNA/Chromecast) + NetBIOS (Windows) + HTTP fingerprinting
- ⚡ **Reporte progresivo** — los dispositivos aparecen en tiempo real conforme se descubren, sin esperar al final
- 🏷️ **Nombres inteligentes** — prioriza mDNS (nombres .local) > SSDP (marcas/modelos) > NetBIOS > DNS > heurística de puertos
- 🎨 **Interfaz oscura profesional** — Material Design 3 con paleta cyber, Iconics (Material Design Community), tarjetas premium
- 📋 **Tap para copiar IP** — toca cualquier dispositivo y copia su IP al portapapeles
- 📡 **Sin dependencias externas** — mDNS, SSDP y NetBIOS implementados con `java.net` estándar, cero librerías de red
- 🔒 **Privacidad primero** — sin anuncios, sin analíticas, sin permisos de ubicación/contactos/almacenamiento

---

## 🛠️ Tecnología

| Capa | Tecnología |
|---|---|
| Lenguaje | Java 11 |
| UI | Material Design 3, CoordinatorLayout, RecyclerView, Iconics 5.5 |
| Red | `java.net` (MulticastSocket, DatagramPacket, InetAddress) |
| Protocolos | ICMP (ping), mDNS/DNS-SD (RFC 6762), SSDP (UPnP), NetBIOS (NBNS), HTTP |
| Build | Gradle KTS, Android Gradle Plugin |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 36 (Android 15) |

---

## 📦 Instalación

Descarga el APK desde [GitHub Releases](https://github.com/thowilabs/wscanner/releases) e instálalo en tu dispositivo Android.

> **Nota:** La app solo requiere el permiso `INTERNET` y `ACCESS_WIFI_STATE`. No necesita ubicación, contactos ni almacenamiento.

---

## 🏗️ Estructura del proyecto

```
WScanner/
├── app/
│   ├── src/main/java/com/thowilabs/wscanner/
│   │   ├── MainActivity.java          # UI principal, DrawerLayout, RecyclerView
│   │   ├── NetworkScanner.java        # Orquestador de fases de descubrimiento
│   │   ├── MdnsDiscovery.java         # mDNS/DNS-SD manual (RFC 6762)
│   │   ├── SsdpDiscovery.java         # SSDP M-SEARCH + scoring por IP
│   │   ├── NetBiosDiscovery.java      # NetBIOS NBNS (puerto 137)
│   │   ├── ArpReader.java             # Lectura ARP (14 métodos)
│   │   ├── VendorResolver.java        # Resolución OUI (53,371 entradas)
│   │   ├── Device.java                # Modelo de dispositivo
│   │   └── DeviceAdapter.java         # Adaptador RecyclerView + Iconics
│   └── src/main/res/                  # Layouts, drawables, themes, colores
├── oui_data/                          # Fuentes OUI (Wireshark, Nmap, IEEE)
└── contexto/                          # Documentación de arquitectura
```

---

## 🔬 Cómo funciona

WScanner ejecuta un pipeline de descubrimiento en fases:

1. **Ping sweep** — ICMP echo a toda la subred en paralelo
2. **mDNS Service Discovery** — PTR `_services._dns-sd._udp.local` → tipos → instancias → SRV → A
3. **mDNS Reverse Lookup** — PTR `X.X.X.X.in-addr.arpa` para cada IP viva
4. **SSDP M-SEARCH** — multicast 239.255.255.250:1900, scoring de nombres por IP
5. **ARP** — lectura de tablas ARP del sistema (limitado en Android 10+)
6. **Port scan + HTTP** — puertos comunes (80, 443, 8080, 554, etc.), fingerprinting HTTP
7. **NetBIOS** — NBSTAT query UDP puerto 137

Cada fase emite resultados en tiempo real. Si una fase posterior descubre mejor información (ej. mDNS da nombre .local), se actualiza la tarjeta del dispositivo existente.

---

## 🧑‍💻 Desarrollo

```bash
# Clonar
git clone https://github.com/thowilabs/wscanner.git
cd wscanner

# Compilar (requiere Android SDK)
./gradlew assembleDebug

# Instalar en dispositivo conectado
./gradlew installDebug
```

Abre el proyecto en Android Studio Hedgehog o superior.

---

## 📄 Licencia

MIT © 2025 [Thowilabs](https://thowilabs.com)

---

<p align="center">
  <sub>Desarrollado por Thowilabs · Alternativa gratuita, sin anuncios ni rastreo</sub>
</p>
