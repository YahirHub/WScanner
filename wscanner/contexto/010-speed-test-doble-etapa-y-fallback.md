# Fecha

2026-07-20

# Objetivo

Convertir el Speed Test existente en una herramienta funcional de medición bidireccional y conservar la descarga HTTP histórica como segunda prueba independiente, con fallback automático de servidores sin romper ni cambiar el flujo visual.

# Decisiones tomadas

- Mantener `SpeedTestTool` sin dependencias nuevas y usar `HttpURLConnection`/Java estándar.
- El test normal mide latencia HTTP, jitter, descarga y subida.
- Cloudflare (`speed.cloudflare.com`) es el backend normal principal porque expone endpoints públicos de descarga y subida usados por su propio motor de Speed Test.
- Si Cloudflare falla, se obtiene dinámicamente la lista pública oficial de servidores de LibreSpeed (`https://librespeed.org/backend-servers/servers.php`).
- La lista LibreSpeed se sondea con concurrencia acotada y presupuesto temporal global; se seleccionan hasta tres servidores respondientes de menor latencia para intentos completos.
- Si los backends bidireccionales fallan, el motor histórico de descarga directa queda como compatibilidad reducida; la subida se reporta como no disponible en vez de inventar un valor.
- Los cambios de proveedor son internos y se registran en log; la UI no muestra errores intermedios ni obliga al usuario a elegir servidor.
- Después del test normal, la UI cambia automáticamente a una segunda pantalla titulada `Prueba de descarga real`.
- La segunda etapa conserva las URLs históricas de Tele2 y ThinkBroadband y prueba la segunda si la primera no responde.
- Salir de la pantalla invalida la generación activa e interrumpe el worker del Speed Test para descartar callbacks tardíos y reducir trabajo residual.
- No se envía telemetría de resultados desde WScanner.

# Arquitectura actual

```text
Speed Test
  ↓
Proveedor normal 1: Cloudflare
  ↓ fallo
Fallback: lista pública LibreSpeed
  ↓
sondeo paralelo acotado → hasta 3 servidores más rápidos
  ↓ fallo
Compatibilidad: descarga directa histórica (sin upload)
  ↓
Resultado normal: ping + jitter + download + upload
  ↓
Cambio de pantalla
  ↓
"Iniciando test de descarga real…"
  ↓
Tele2 10 MB
  ↓ fallo
ThinkBroadband 5 MB
  ↓
Comparación final: test normal vs descarga real
```

El download normal usa cuatro streams concurrentes y el upload tres. Un warm-up pequeño estima el ancho de banda para elegir dinámicamente el tamaño por stream. Si la primera medición revela que la muestra quedó demasiado pequeña, se permite una única rampa adicional sin superar el presupuesto total configurado por dirección. El gauge puede autoescalar hasta enlaces de 10 Gbps.

# Librerías usadas

No se agregaron dependencias.

Se reutilizan:

- `java.net.HttpURLConnection`
- `java.util.concurrent`
- `android.os.Handler`
- `SpeedometerGauge`

# Archivos importantes modificados

- `app/src/main/java/com/thowilabs/wscanner/SpeedTestTool.java`
- `app/src/main/java/com/thowilabs/wscanner/MainActivity.java`
- `app/src/main/java/com/thowilabs/wscanner/SpeedometerGauge.java`
- `app/src/main/res/layout/tool_speedtest.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/thowilabs/wscanner/SpeedTestToolTest.java`
- `README.md`
- `README.txt`
- `knowledge.md`
- `contexto/000-contexto-maestro.md`
- `contexto/010-speed-test-doble-etapa-y-fallback.md`

# Problemas encontrados

- El Speed Test anterior solo hacía una comprobación de latencia de mejor esfuerzo y descargaba un archivo estático.
- La subida siempre se devolvía como `0`, aunque la interfaz y el callback sugerían que existía una prueba de upload.
- Dependía directamente de dos hosts de archivos estáticos y no tenía un backend normal bidireccional.
- No existía separación entre una medición de throughput y una descarga real de contraste.
- No había cancelación lógica al abandonar la pantalla, por lo que un callback tardío podía intentar actualizar una vista oculta.

# Soluciones implementadas

- Medición HTTP de latencia y jitter.
- Download adaptativo multistream.
- Upload adaptativo multistream.
- Cadena de proveedores con fallback transparente.
- Descubrimiento dinámico de servidores públicos LibreSpeed y selección por latencia con timeout global.
- Compatibilidad reducida usando el motor histórico cuando no hay backend bidireccional.
- Segunda pantalla para descarga real automática.
- Comparación visual entre resultado normal y descarga real.
- Cancelación por generación para ignorar callbacks obsoletos e interrupción del worker activo al salir/reiniciar.
- Tests unitarios para cálculo Mbps, jitter, percentil, dimensionamiento adaptativo y decisión de rampa adicional.

# Pendientes

- Validar en un dispositivo Android real que los endpoints públicos acepten el volumen de upload/download esperado desde la red del usuario.
- Medir resultados contra otra herramienta de referencia en conexiones lentas, medias y rápidas.
- Ajustar duración/volumen si se detecta consumo excesivo en conexiones de gigabit o resultados inestables en conexiones muy lentas.
- Considerar en el futuro selección geográfica estandarizada mediante una infraestructura como M-Lab/NDT7 si se acepta incorporar el protocolo o una dependencia específica.

# Próximos pasos

1. Ejecutar `gradlew.bat :app:testDebugUnitTest`.
2. Ejecutar `gradlew.bat :app:assembleDebug`.
3. Ejecutar `gradlew.bat :app:lintDebug`.
4. Probar el test completo y verificar que muestra upload real, no `0` fijo.
5. Verificar la transición automática a la descarga real.
6. Probar desconexión/fallo de un backend y confirmar fallback transparente.
