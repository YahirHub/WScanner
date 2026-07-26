# Fase 1 — Mejoras de Escaneo y UX

**Fecha:** 2026-07-14
**Relacionado con:** `000-contexto-maestro.md`

---

## Objetivo

Mejorar la experiencia de usuario post-escaneo con ordenamiento, búsqueda/filtro, más puertos de escaneo, pantalla de detalle de dispositivo, ping individual y abrir en navegador.

---

## Funcionalidades implementadas

### 1. Ordenamiento por IP / Nombre / Vendor / Método

**Archivos:** `MainActivity.java`, `DeviceAdapter.java`, `activity_main.xml`

- Chips interactivos en fila horizontal: IP, Nombre, Vendor, Método
- Chip activo en cyan (`#00E5FF`) con fondo `badge_empty_hint`
- Chips inactivos en gris (`#8B949E`) sin fondo
- `DeviceAdapter.sortBy(criteria)` con `Comparator` específico para cada criterio
- Ordenamiento IP: numérico por octetos
- Ordenamiento Vendor: los "Desconocido" van al final (clave "zzz")
- Ordenamiento Method: por ranking de fuente (mDNS=7 > SSDP=6 > ... > Heurística=1)

### 2. Búsqueda / Filtro con SearchView

**Archivos:** `MainActivity.java`, `DeviceAdapter.java`, `res/menu/toolbar_menu.xml`

- `SearchView` integrado en la toolbar via `onCreateOptionsMenu`
- `DeviceAdapter` implementa `Filterable`
- Búsqueda por nombre, IP, vendor, MAC, método de descubrimiento
- `filteredItems = null` cuando no hay filtro activo (usa `items` directamente)
- **Bug corregido:** Los dispositivos no aparecían hasta abrir el SearchView. Causa: `filteredItems` era copia estática en constructor, nunca se actualizaba con datos nuevos.

### 3. 6 puertos adicionales + nombres de servicio

**Archivos:** `NetworkScanner.java`, `Device.java`

- `PROBE_PORTS` expandido de 9 a 15 puertos
- Añadidos: 53 (DNS), 3389 (RDP), 5900 (VNC), 5000 (UPnP), 5353 (mDNS), 9100 (IPP)
- `SERVICE_NAMES` estático con 15 entradas
- `Device.openPorts` (List<Integer>) y `Device.serviceNames` (List<String>)
- `NetworkScanner.serviceName(int port)` — público para UI

### 4. Pantalla de detalle de dispositivo

**Archivos:** `MainActivity.java`, `layout_device_detail.xml`, `layout_device_detail_inner.xml`

- Vista embebida (no Activity separada) con icono, nombre, IP, MAC, vendor, método, puertos, acciones
- Navegación: tap en tarjeta → `showDeviceDetail(device)` → oculta scanner content, muestra detail
- Botones de acción: Volver, Copiar IP, Copiar MAC, Abrir en navegador, Ping
- Puertos abiertos mostrados con nombre de servicio

### 5. Ping individual con RTT

**Archivos:** `MainActivity.java`

- Botón "Ping" en detail view
- Ejecuta `InetAddress.isReachable(2000)` en thread separado
- Muestra RTT en ms si responde, o "no responde"

### 6. Abrir en navegador

**Archivos:** `MainActivity.java`

- Botón "Abrir en navegador" en detail view
- `Intent.ACTION_VIEW` con `http://<ip>`

---

## Bug crítico corregido: DeviceAdapter no mostraba dispositivos

**Síntoma:** El escaneo descubría dispositivos pero no aparecían en la lista hasta tocar el icono de búsqueda.

**Causa:** `filteredItems` era una copia estática (`new ArrayList<>(items)`) hecha en el constructor. Cuando `items` se modificaba (clear, add), `filteredItems` nunca se actualizaba. `getItemCount()` devolvía `filteredItems.size()` (tamaño viejo).

**Solución:** `filteredItems = null` cuando no hay filtro activo. `getItemCount()` y `onBindViewHolder()` usan `items` directamente. Solo cuando el usuario escribe en SearchView se puebla `filteredItems`.

---

## Archivos creados

| Archivo | Descripción |
|---------|-------------|
| `layout_device_detail.xml` | Vista de detalle embebida |
| `layout_device_detail_inner.xml` | Contenido interno del detalle (reutilizado en tablet) |
| `toolbar_menu.xml` | Menu con SearchView |

## Archivos modificados

| Archivo | Cambios |
|---------|---------|
| `Device.java` | Campos `ttl`, `openPorts`, `serviceNames` |
| `NetworkScanner.java` | `SERVICE_NAMES`, `serviceName()`, `PROBE_PORTS` expandido, Fase 3 emite openPorts |
| `DeviceAdapter.java` | `Filterable`, `sortBy(criteria)`, `OnDeviceClickListener`, `discoveryMethodRank()` |
| `MainActivity.java` | SearchView, chips, showDeviceDetail, ping, abrir navegador, back navigation extendida, merge de ports |
| `activity_main.xml` | Fila de chips, include layout_device_detail |
