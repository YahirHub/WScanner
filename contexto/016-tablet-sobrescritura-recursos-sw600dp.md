# 016 · Corrección real de tablet por recursos sw600dp heredados

## Contexto
La captura en tablet mostraba la interfaz como una columna estrecha a la izquierda con mucho espacio vacío a la derecha. Al revisar documentación de Android sobre pantallas grandes, el comportamiento puede venir por restricciones de orientación/compatibilidad, pero en este proyecto había además un problema práctico de actualización: el ZIP nuevo eliminaba carpetas tablet antiguas, pero si el usuario copiaba el proyecto encima del anterior, Android Studio podía conservar `layout-sw600dp`, `layout-w840dp`, `values-sw600dp` y `values-w840dp`.

Esas carpetas antiguas contenían un diseño split-pane con guía vertical al 40%/35% y toolbar con `WScanner / Analizador de red local`, justo lo que seguía viéndose en la tablet.

## Decisión
- Reintroducir `layout-sw600dp/activity_main.xml` y `layout-w840dp/activity_main.xml`, pero como copia del layout principal corregido.
- Reintroducir `layout-sw600dp/network_summary.xml` como copia del layout principal.
- Reintroducir `values-sw600dp/dimens.xml` y `values-w840dp/dimens.xml` vacíos/documentados para neutralizar dimensiones heredadas si se sobrescribe sobre el proyecto viejo.
- Añadir `<supports-screens>` al manifest declarando soporte para pantallas grandes y xlarge.

## Resultado
- Aunque el usuario copie el ZIP encima del proyecto anterior, las variantes tablet antiguas ya no quedan vivas.
- La tablet usa el mismo diseño visual que smartphone, pero ocupando todo el ancho disponible.
- Desaparece el split-pane lateral y desaparece el título/subtítulo antiguo en tablet.

## Nota de instalación
Para validar este cambio, es preferible reemplazar la carpeta del proyecto completa o limpiar el proyecto antes de compilar. Si se copian archivos manualmente, estas carpetas deben quedar exactamente como en el ZIP nuevo.
