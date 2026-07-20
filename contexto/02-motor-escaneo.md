# Fecha
2026-07-13

# Objetivo
Fusión de bases de datos OUI de múltiples fuentes.

# Fuentes descargadas
- Wireshark manuf: https://www.wireshark.org/download/automated/data/manuf (39,946 OUIs)
- Nmap mac-prefixes: https://raw.githubusercontent.com/nmap/nmap/master/nmap-mac-prefixes (38,938 OUIs)
- OUI Master DB (Ringmast4r): https://github.com/Ringmast4r/OUI-Master-Database (IEEE+Wireshark+Nmap+HDM, 13,839 adicionales)

# Resultado de la fusión
- Total unificado: 53,371 OUIs (+35% vs las 39,704 previas)
- 14,438 OUIs nuevos añadidos de fuentes que no estaban en nuestra DB anterior
- 419 OUIs validados en las 3 fuentes simultáneamente
- Archivo: app/src/main/assets/oui_database.json (1.6 MB)

# Formato
JSON compacto: {"AABBCC": "Nombre Fabricante", ...}
Cargado con org.json nativo de Android, sin dependencias externas.

# Problemas conocidos
- Android 10+ bloquea /proc/net/arp → no se obtienen MACs reales sin root
- Fallback: port scanning (80,443,22,445,8080,23,21,554,1883) + DNS inverso para inferir tipo
- Si no hay MAC → no hay vendor → se usa hostname DNS o heurística por IP/puertos

# Pendientes
- mDNS/Bonjour discovery para nombres de dispositivos locales
- HTTP fingerprinting para identificar routers/NAS/cámaras por banner
- UPnP/SSDP discovery


## Estado actual 2026-07-20

La base OUI se conserva como enriquecimiento local opcional. El motor de detección actual no depende de MAC/OUI para descubrir ni clasificar dispositivos, ya que Android moderno puede no exponer la tabla de vecinos a aplicaciones normales. El pipeline vigente está documentado en `008-mejora-deteccion-offline-y-refactor-motor.md`.
