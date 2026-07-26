# Migración Material 3 — herramientas secundarias y limpieza de color

# Fecha

2026-07-26

# Objetivo

Completar la migración a Material 3 iniciada en las Fases 1 y 2, eliminando los últimos colores hardcoded en layouts y código Java, y unificando toda la superficie visual bajo los tokens `?attr/colorPrimary`, `?attr/colorSurface`, `?attr/colorOnSurface`, `?attr/colorOnSurfaceVariant`, `?attr/colorOutlineVariant` y `?attr/colorError`, de forma que el tema (claro/oscuro y futuras variantes) controle la apariencia de forma centralizada.

# Decisiones tomadas

- Sustituir los hex GitHub-dark (`#0D1117`, `#00E5FF`, `#8B949E`, `#6E7681`, `#E6EDF3`, `#30363D`, `#21262D`) por atributos de tema M3, sin introducir dependencias nuevas.
- Mantener `@color/status_online`, `@color/status_warning` y `@color/status_error` como tokens semánticos independientes del rol M3, para estados de red que deben conservar significado visual entre temas.
- Resolver colores en Java a través de `MaterialColors.getColor(view, attr)` en lugar de literales `0xFF…`, alineando la lógica con el tema activo y permitiendo tematizar sin recompilar strings de color.
- El FAB de escaneo usa `?attr/colorPrimary` en reposo y `?attr/colorError` durante el escaneo/monitor activo, en línea con la semántica M3 de acción destructiva.
- El punto de estado del dispositivo (`statusDot`) usa `@color/status_online` cuando el dispositivo está online y `?attr/colorOnSurfaceVariant` cuando está offline, evitando el gris hardcoded.
- Conservar los fallbacks numéricos en `DeviceAdapter.resolveThemeColor(...)` como red de seguridad si el tema no expone el atributo (defensa frente a temas incompletos).

# Arquitectura actual

- Todos los layouts de herramientas (`tool_wol.xml`, `tool_traceroute.xml`, `tool_history.xml`, `tool_speedtest.xml`) usan exclusivamente atributos de tema M3 y tokens semánticos.
- `MainActivity` ya no contiene literales de color para SearchView, FAB, iconos del detalle o chips de puertos; todos se resuelven contra el tema mediante `MaterialColors`.
- `DeviceAdapter` centraliza la resolución de acento, outline y estado a través de `resolveThemeColor` y `@color/status_online`.
- `item_device.xml`, `network_summary.xml`, `scanner_status.xml`, `empty_state.xml` y `placeholder_cards.xml` heredan color de los widgets M3 configurados en `themes.xml`.
- `values-sw600dp/dimens.xml` y `values-w840dp/dimens.xml` ya proveen escalado tipográfico y de espaciado para tablet y pantallas anchas.

# Librerías usadas

- Material Components 1.12.0 (`com.google.android.material.color.MaterialColors`).
- AndroidX Core (`ContextCompat`, `WindowCompat`, `ViewCompat`).
- Sin dependencias nuevas.

# Archivos importantes modificados

- `app/src/main/res/layout/tool_wol.xml`
- `app/src/main/res/layout/tool_traceroute.xml`
- `app/src/main/res/layout/tool_history.xml`
- `app/src/main/java/com/thowilabs/wscanner/MainActivity.java`
- `app/src/main/java/com/thowilabs/wscanner/DeviceAdapter.java`

# Pendiente / próximos pasos

- Fase 4 opcional: refactor visual del `layout_device_detail_inner.xml` con jerarquía M3 más marcada (title/label/body separados en tipografía y color).
- Fase 7 opcional: layout adaptativo real para tablet — `GridLayoutManager` a 2 columnas en `sw600dp` o `SlidingPaneLayout` para maestro–detalle en `w840dp`.
- Fase 8 opcional: animaciones `MaterialContainerTransform` entre lista y detalle, y revisión de contraste WCAG AA sobre el tema oscuro definitivo.
