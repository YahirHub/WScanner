# 013 · Rediseño Speed Test y animación FAB pulso-halo

## Contexto
El test de velocidad usaba un layout largo con secciones apiladas de baja jerarquía. La animación del FAB de escaneo era una rotación lineal poco expresiva.

## Decisiones
- **Speed Test dashboard-first**: header eyebrow + título, panel grande de velocidad de descarga en vivo con métricas satélite (Upload / Ping / Jitter) siempre visibles, tarjetas M3 con `?attr/colorSurfaceContainer` y `LinearProgressIndicator` M3. Se conserva la etapa 2 de "descarga real" con comparativa contra el test normal.
- **IDs**: dashboard usa `txtDownloadSpeed` (nuevo). El resumen normal-vs-real en etapa 2 conserva `txtNormalDownloadSummary`.
- **FAB animation**: se sustituye la rotación 360° por `AnimatorSet` con:
  - dos anillos halo (`fabHaloOuter`/`fabHaloInner`) escalando 0.85→2.1 y alpha 0.55→0 con `DecelerateInterpolator`, escalonados 750 ms.
  - respiración sutil del propio FAB (scale 1↔1.06) con `AccelerateDecelerateInterpolator`.
- **Inset listener** movido de `btnScan` a `btnScanContainer` (FrameLayout envolvente) para que el margen de barras del sistema aplique a los halos también.
- **Reset**: al cancelar la animación se restablecen alpha/scale de halos y FAB.

## Cambios
- `res/layout/tool_speedtest.xml` — rediseño completo dashboard-first.
- `res/layout{,-sw600dp,-w840dp}/activity_main.xml` — FAB envuelto en `FrameLayout` con anillos halo.
- `res/drawable/bg_fab_halo_ring.xml` — anillo circular de 2 dp con `colorPrimary`.
- `MainActivity.java` — `setupFabRotation()` reescrito con `AnimatorSet`, `buildHaloPulse()`, `resetFabPulseState()`. Actualiza `txtDownloadSpeed` en cada callback de descarga.

## Notas para siguientes fases
- Reevaluar `layout_device_detail.xml` (Fase 4) y `scanner_status.xml`.
- Si el usuario reporta que los halos entorpecen la pulsación, elevar `btnScan` con `translationZ` o poner `android:clickable="false"` en los halos (ya son `View` sin listener, no debería interferir).
