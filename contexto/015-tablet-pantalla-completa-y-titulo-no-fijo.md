# 015 · Tablet a pantalla completa y título no fijo

## Contexto
En tablets seguía apareciendo un área lateral sin uso porque la Activity estaba bloqueada a orientación vertical. En varios dispositivos Android, especialmente tablets, ese bloqueo puede generar letterbox/pillarbox aunque el layout use `match_parent`.

## Decisión
- Quitar `android:screenOrientation="userPortrait"` de `MainActivity` para que Android permita usar todo el tamaño real de la pantalla.
- Mantener `resizeableActivity="true"` para que tablets y pantallas grandes puedan redimensionar la Activity correctamente.
- Vaciar el título/subtítulo fijo del `MaterialToolbar` en XML y también desde Java después de asignarlo como ActionBar.

## Resultado
- La app ya no fuerza un carril vertical en tablets: puede ocupar todo el ancho disponible.
- El texto fijo `WScanner / Analizador de red local` desaparece de la barra superior.
- Se mantiene el mismo diseño base de smartphone, pero sin el hueco negro provocado por el bloqueo de orientación.