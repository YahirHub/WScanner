# 014 · Diseño tablet unificado y bloqueo vertical

## Contexto
En tablet la app usaba `layout-sw600dp` y `layout-w840dp` con estructuras propias que rompían la coherencia visual con la versión de smartphone y quedaban mal proporcionadas.

## Decisión
- Eliminar los recursos específicos de tablet:
  - `res/layout-sw600dp/`
  - `res/layout-w840dp/`
  - `res/values-sw600dp/`
  - `res/values-w840dp/`
- Bloquear la orientación de `MainActivity` a `portrait` en `AndroidManifest.xml`.

## Resultado
- Tablets usan el mismo diseño que smartphones, escalado por el sistema.
- La app no rota a horizontal en ningún dispositivo.
- Menos superficies que mantener (un solo layout por pantalla).
