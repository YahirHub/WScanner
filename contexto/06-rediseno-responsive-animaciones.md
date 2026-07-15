# Rediseño Responsive + Animaciones Modernas

**Fecha:** 2026-07-15
**Relacionado con:** `000-contexto-maestro.md`

---

## Objetivo

Transformar WScanner en una app con diseño moderno, completamente responsive para tablets y móviles, con transiciones animadas fluidas entre pantallas y micro-interacciones pulidas, manteniendo la arquitectura Java + XML Views existente sin introducir Fragments.

---

## Cambios implementados

### 1. Animaciones (res/anim/)

8 archivos XML creados con interpoladores Material:
- `slide_in_right.xml`, `slide_out_left.xml` — slide horizontal + fade
- `slide_in_left.xml`, `slide_out_right.xml` — slide reverso + fade
- `fade_in.xml`, `fade_out.xml` — fade simple
- `scale_up.xml`, `scale_down.xml` — scale + fade combinado

**Nota:** Las transiciones entre pantallas usan `androidx.transition.TransitionManager` programáticamente. Los XML existen como recursos disponibles pero no son referenciados directamente.

### 2. Transiciones entre pantallas (MainActivity.java)

- **showScanner()**: `AutoTransition` (Fade + ChangeBounds) al volver de About o Detail
- **showAbout()**: `TransitionSet` con `Slide(Gravity.END)` + `Fade` (350ms)
- **showDeviceDetail()**: `TransitionSet` con `Slide(Gravity.END)` + `Fade` (300ms)
- Se usa `findViewById(R.id.rootConstraint)` como `ViewGroup` raíz para `TransitionManager.beginDelayedTransition()`

### 3. ConstraintLayout base (activity_main.xml)

El layout principal del teléfono fue refactorizado:
- `LinearLayout` raíz del scanner content → `ConstraintLayout`
- `AppBarLayout` anclado top-to-top del parent
- `layoutScannerContent`, `layoutDeviceDetail`, `layoutAbout` ocupan el mismo espacio (0dp×0dp) debajo de la AppBar
- Visibility toggling mantiene solo uno visible a la vez
- `FrameLayout` (listContainer) contiene `RecyclerView` + `empty_state` + `placeholder_cards`

### 4. Layouts responsive para tablets

#### layout-sw600dp (tablets 7")
- **Split pane**: Guideline vertical al 40%
- Panel izquierdo (40%): header de red compacto, chips, status, lista de dispositivos
- Panel derecho (60%): detalle de dispositivo inline (layout_device_detail_inner dentro de ScrollView)
- Divider vertical entre paneles
- Chips usan `@dimen/chip_text_size`, `@dimen/chip_padding_horizontal`, etc.

#### layout-w840dp (tablets 10" / landscape grande)
- **Split pane**: Guideline vertical al 35% (más espacio para detalle)
- Misma estructura que sw600dp con padding y texto más grandes
- Márgenes FAB aumentados (28dp)

### 5. Dimensiones responsive

- `values-sw600dp/dimens.xml` — Textos, paddings, radios, anchos máximos
- `values-w840dp/dimens.xml` — Valores más grandes para tablets de 10"

### 6. Micro-interacciones (DeviceAdapter.java)

- **Ripple touch feedback**: `selectableItemBackground` como foreground de la tarjeta (API 23+, minSdk=24)
- **StateListAnimator**: elevación 2dp→8dp en press, 8dp→2dp en release
- **Staggered reveal**: `setStartDelay(Math.min(i * 40L, 300L))` en fade-in de tarjetas
- **Press scale**: 0.96x con `OvershootInterpolator(1.2f)`

### 7. Empty state + Placeholder mejoras

- **Empty state pulse**: `ObjectAnimator` alpha 0.3↔0.7 en el círculo de fondo del radar (1500ms, REVERSE)
- **Placeholder shimmer**: Pulsos alpha 0.4→0.7→0.4 escalonados por tarjeta placeholder
- **Texto actualizado**: "Presiona el botón Escanear para descubrir dispositivos..." (sin referencia a swipe)
- `imgEmptyRadarBg` ID añadido al View del círculo de fondo

### 8. Tema actualizado

- `android:windowContentTransitions` = true
- `android:windowAllowEnterTransitionOverlap` = false
- `android:windowAllowReturnTransitionOverlap` = false
- Aplicado en `values/themes.xml` y `values-night/themes.xml`

### 9. Limpieza

- Eliminada dependencia `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` de `build.gradle.kts`
- SwipeRefreshLayout ya había sido removido del layout en commit anterior

### 10. Layouts extraídos (evitar duplicación)

- `about_content.xml` — Contenido de pantalla "Acerca de" compartido entre phone y tablet
- `layout_device_detail_inner.xml` — Contenido interno del detalle compartido entre phone y tablet
- `layout_device_detail.xml` — Simplificado a ScrollView con include de `layout_device_detail_inner`

---

## Archivos creados

| Archivo | Propósito |
|---------|-----------|
| `res/anim/slide_in_right.xml` | Animación slide enter desde derecha |
| `res/anim/slide_out_left.xml` | Animación slide exit hacia izquierda |
| `res/anim/slide_in_left.xml` | Animación slide enter desde izquierda |
| `res/anim/slide_out_right.xml` | Animación slide exit hacia derecha |
| `res/anim/fade_in.xml` | Animación fade enter |
| `res/anim/fade_out.xml` | Animación fade exit |
| `res/anim/scale_up.xml` | Animación scale + fade enter |
| `res/anim/scale_down.xml` | Animación scale + fade exit |
| `res/layout-sw600dp/activity_main.xml` | Layout tablet 7" split pane |
| `res/layout-w840dp/activity_main.xml` | Layout tablet 10" / landscape grande |
| `res/layout/about_content.xml` | Contenido "Acerca de" reutilizable |
| `res/layout/layout_device_detail_inner.xml` | Contenido interno del detalle reutilizable |
| `res/values-sw600dp/dimens.xml` | Dimensiones para tablet 7" |
| `res/values-w840dp/dimens.xml` | Dimensiones para tablet 10" |

## Archivos modificados

| Archivo | Cambios |
|---------|---------|
| `res/layout/activity_main.xml` | Refactorizado a ConstraintLayout + about_content include |
| `res/layout/empty_state.xml` | Texto sin swipe, imgEmptyRadarBg ID para pulse |
| `res/layout/layout_device_detail.xml` | Simplificado a ScrollView + include inner |
| `res/values/themes.xml` | windowContentTransitions |
| `res/values-night/themes.xml` | windowContentTransitions |
| `MainActivity.java` | Transiciones, pulse, shimmer, setupEmptyPulse, animatePlaceholders |
| `DeviceAdapter.java` | Staggered reveal, ripple, StateListAnimator |
| `app/build.gradle.kts` | Eliminada dependencia swiperefreshlayout |

---

## Decisiones técnicas

- **Sin Fragments**: Se mantiene el modelo de views embebidas con visibility toggling. `TransitionManager` funciona correctamente con `ConstraintLayout`.
- **ConstraintLayout como base**: Mejor performance que `LinearLayout` anidado, especialmente en tablets donde el layout es más complejo.
- **Guideline-based split**: El split pane usa `Guideline` con porcentaje en vez de pesos, permitiendo control preciso en diferentes tamaños.
- **Includes para DRY**: `about_content.xml` y `layout_device_detail_inner.xml` evitan duplicación entre layouts phone y tablet.

---

## Issues conocidos post-implementación

1. **8 archivos anim/ no usados directamente** — Existen como recursos pero las transiciones usan TransitionManager. No afectan funcionamiento.
2. **w840dp no usa @dimen/** — Hardcodea tamaños de chips en vez de referenciar `values-w840dp/dimens.xml`. Funcional, pero inconsistente con sw600dp.
3. **animatePlaceholders() no repite** — El shimmer de placeholders es one-shot (0.4→0.7→0.4). Durante escaneos largos (>1.6s) la animación se detiene.
