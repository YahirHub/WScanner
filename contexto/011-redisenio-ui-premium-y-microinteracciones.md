# Rediseño UI premium y microinteracciones

# Fecha

2026-07-21

# Objetivo

Elevar la interfaz de WScanner a una experiencia visual profesional y coherente, con microinteracciones útiles, jerarquía clara, estados de carga y vacío explicativos, feedback háptico y comportamiento responsive, sin introducir dependencias innecesarias ni alterar el motor de red.

# Decisiones tomadas

- Mantener Android Views/XML y Material Components; no migrar a Compose ni agregar librerías de animación.
- Implementar estados de presión cancelables mediante APIs nativas, preservando clic, pulsación larga y desplazamiento.
- Usar animaciones breves y funcionales únicamente cuando comunican un cambio de estado.
- Limitar el shimmer a procesos activos; no usarlo como decoración permanente.
- Aplicar feedback háptico de selección, confirmación y éxito mediante capacidades nativas de Android.
- Sustituir tarjetas de dispositivos creadas programáticamente por un layout XML reutilizable y consistente.
- Mantener el estado visual del RecyclerView estable, cancelando animaciones al reciclar vistas para evitar regresiones del crash de ViewHolder.
- Conservar la identidad visual azul/cian existente, pero con una paleta oscura más contenida y mejor contraste.
- Mantener soporte responsive para teléfonos, tablets y diseños de panel dividido.

# Arquitectura actual

- `PressStateUtil` centraliza la respuesta táctil de botones, tarjetas y acciones.
- `ShimmerTextView` ofrece shimmer ligero y detiene su animador cuando la vista deja de estar visible.
- `HapticUtil` centraliza los patrones hápticos compatibles por versión de Android.
- `DeviceAdapter` infla `item_device.xml`, aplica stable IDs y controla animaciones de entrada únicamente para dispositivos nuevos.
- `MainActivity` coordina transiciones de pantalla, estados de carga, estado vacío, hápticos y animaciones de métricas.
- Los layouts principales, detalle de dispositivo, Speed Test, estado vacío, placeholders y drawer comparten estilos y recursos visuales Material.

# Librerías usadas

- AndroidX AppCompat.
- Material Components.
- RecyclerView.
- Mikepenz Iconics / Community Material Typeface, ya existente.
- Animadores y APIs hápticas nativas de Android.
- No se agregó ninguna dependencia nueva.

# Archivos importantes modificados

- `app/src/main/java/com/thowilabs/wscanner/MainActivity.java`
- `app/src/main/java/com/thowilabs/wscanner/DeviceAdapter.java`
- `app/src/main/java/com/thowilabs/wscanner/HapticUtil.java`
- `app/src/main/java/com/thowilabs/wscanner/PressStateUtil.java`
- `app/src/main/java/com/thowilabs/wscanner/ShimmerTextView.java`
- `app/src/main/java/com/thowilabs/wscanner/SpeedometerGauge.java`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout-sw600dp/activity_main.xml`
- `app/src/main/res/layout-w840dp/activity_main.xml`
- `app/src/main/res/layout/item_device.xml`
- `app/src/main/res/layout/network_summary.xml`
- `app/src/main/res/layout/scanner_status.xml`
- `app/src/main/res/layout/empty_state.xml`
- `app/src/main/res/layout/placeholder_cards.xml`
- `app/src/main/res/layout/layout_device_detail.xml`
- `app/src/main/res/layout/layout_device_detail_inner.xml`
- `app/src/main/res/layout/tool_speedtest.xml`
- `app/src/main/res/layout/about_content.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/themes.xml`
- `app/src/main/res/values-night/themes.xml`
- Recursos nuevos y actualizados dentro de `app/src/main/res/drawable/`.
- `scripts/eliminar_recursos_ui_obsoletos.py` para limpiar el PNG anterior al aplicar cambios sobre una copia existente.

# Problemas encontrados

- Jerarquía visual poco consistente entre escáner, detalle, Speed Test y pantallas auxiliares.
- Tarjetas construidas en Java difíciles de mantener y estilizar de forma uniforme.
- Estados táctiles limitados o sin cancelación visual al desplazar el dedo.
- Carga inicial y estados vacíos con poca orientación para el usuario.
- Animaciones repetidas durante rebinds que podían sentirse ruidosas y aumentar el riesgo de conflictos con RecyclerView.
- Elementos con iconografía o tratamientos visuales distintos para acciones equivalentes.
- Diseños de tablet que no compartían completamente la misma jerarquía visual del teléfono.

# Soluciones implementadas

- Nueva paleta oscura premium con superficies, bordes y estados semánticos consistentes.
- Estilos Material reutilizables para tarjetas, botones, textos y formas.
- Estados de presión con escala breve, retorno con resorte y cancelación al salir del área o iniciar scroll.
- Hápticos discretos para selección, confirmación y finalización exitosa.
- Entradas escalonadas solo para dispositivos nuevos; no se repiten al actualizar datos del mismo equipo.
- Reinicio explícito de propiedades y animadores al reciclar ViewHolders.
- Estado de carga con skeletons y shimmer de texto limitado a operaciones activas.
- Estado vacío explicativo con siguiente acción, privacidad local y ayuda para el modo monitor.
- Rediseño de resumen de red, tarjetas de dispositivos, ficha de detalle, Speed Test, Acerca de y drawer.
- Transiciones breves de fade/slide entre herramientas y estados.
- Ocultación del teclado al desplazar listas y búsqueda con tratamiento visual consistente.
- Recursos de marca del drawer optimizados a WebP para reducir tamaño sin cambiar su aspecto.

# Pendientes

- Ejecutar build, tests y lint con Android SDK/Gradle disponibles.
- Validar tamaños, contraste, hápticos y animaciones en varios teléfonos físicos y tablets.
- Revisar accesibilidad con TalkBack y fuentes del sistema aumentadas.
- Comprobar la experiencia con las animaciones del sistema reducidas o desactivadas.
- Ajustar detalles únicamente a partir de pruebas reales, evitando agregar movimiento decorativo.

# Próximos pasos

1. Compilar e instalar el APK en un dispositivo físico.
2. Recorrer escaneo, monitor continuo, búsqueda, detalle, Speed Test y drawer.
3. Probar clic, cancelación por desplazamiento, pulsación larga y reciclaje rápido de tarjetas.
4. Validar teléfono pequeño, teléfono grande y tablet.
5. Ejecutar pruebas de accesibilidad y corregir cualquier contraste o descripción faltante.
