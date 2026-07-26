# Fecha
2026-07-13

# Objetivo
Rediseño completo de la UI a un look profesional oscuro con estética cyber/network-tool.

# Diseño

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

## Componentes rediseñados

### activity_main.xml
- `CoordinatorLayout` como raíz
- `AppBarLayout` + `Toolbar` con ProgressBar integrada debajo (3dp height, cyan tint)
- **Header de info de red**: 3 columnas con SUBRED (cyan), DISPOSITIVOS (verde, 24sp bold), GATEWAY (blanco)
- `SwipeRefreshLayout` envolviendo el `RecyclerView`
- **FAB cyan**: botón de escaneo flotante abajo-derecha con ícono radar
- Separadores con `@color/divider` (1dp)

### nav_header.xml
- Gradiente oscuro→cyan (315°): `#CC0D1117` → `#CC00B8D4`
- Título "WScanner" en cyan `#00E5FF`, 26sp bold
- Subtítulo "Network Scanner" + "v1.0 · by Thowilabs"

### drawer_menu.xml
- Íconos vectoriales personalizados (24dp):
  - `ic_radar.xml` — círculos concéntricos (cyan)
  - `ic_history.xml` — reloj con flecha (gris)
  - `ic_settings.xml` — engranaje (gris)
  - `ic_about.xml` — ⓘ info (gris)
- `app:itemIconTint="#FF8B949E"`, `app:itemTextColor="#FFE6EDF3"`

### DeviceAdapter.java
- Tarjeta horizontal: icono + info + badge de tipo
- Icono emoji grande (28sp) + puntito verde ● como status
- IP en cyan con fuente monospace
- Método en verde `#3FB950`
- Badge de tipo a la derecha (cyan semi-transparente `10%`)
- Fondo programático con `GradientDrawable`: `#1C2333`, bordes redondeados 12dp, stroke `#30363D` 1dp
- Tap-to-copy IP con Toast

### Temas
- `Theme.MaterialComponents.DayNight.NoActionBar` (día y noche idénticos: siempre oscuro)
- `colorControlNormal` = gris, `colorControlActivated` = cyan
- `DrawerText` style: 14sp, sans-serif-medium

## Archivos modificados

| Archivo | Cambio |
|---------|--------|
| `values/colors.xml` | Paleta completa dark cyber |
| `values/themes.xml` | Tema unificado oscuro + DrawerText style |
| `values-night/themes.xml` | Idéntico al día |
| `layout/activity_main.xml` | CoordinatorLayout + AppBarLayout + header red + SwipeRefresh + FAB |
| `layout/nav_header.xml` | Gradiente + tipografía refinada |
| `menu/drawer_menu.xml` | Íconos vectoriales propios |
| `MainActivity.java` | SwipeRefresh, header info dinámica, FAB, LinearLayout import |
| `DeviceAdapter.java` | Tarjetas horizontales premium con badge de tipo |

## Archivos nuevos

| Archivo | Descripción |
|---------|-------------|
| `drawable/gradient_dark.xml` | Gradiente oscuro para header |
| `drawable/gradient_header.xml` | Gradiente nav header |
| `drawable/card_bg.xml` | Fondo de tarjetas (referencia) |
| `drawable/badge_online.xml` | Badge redondeado cyan |
| `drawable/ic_radar.xml` | Ícono radar |
| `drawable/ic_history.xml` | Ícono historial |
| `drawable/ic_settings.xml` | Ícono configuración |
| `drawable/ic_about.xml` | Ícono acerca de |
