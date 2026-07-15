package com.thowilabs.wscanner;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.regex.Pattern;

/**
 * Utilidad estática para enviar paquetes mágicos Wake-on-LAN (WoL).
 * <p>
 * Construye un magic packet de 102 bytes (6 × 0xFF + MAC repetida 16 veces)
 * y lo envía por UDP broadcast al puerto 9.
 */
public final class WakeOnLanTool {

    private static final int WOL_PORT = 9;
    private static final Pattern MAC_PATTERN =
            Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$");

    private WakeOnLanTool() {
        // utilidad estática — no instanciar
    }

    /**
     * Valida el formato de una dirección MAC.
     * Acepta separadores {@code :} y {@code -}.
     */
    public static boolean isValidMac(String mac) {
        return mac != null && MAC_PATTERN.matcher(mac).matches();
    }

    /**
     * Construye y envía un magic packet WoL al broadcast indicado.
     *
     * @param macAddress  MAC de la máquina destino (AA:BB:CC:DD:EE:FF o AA-BB-CC-DD-EE-FF)
     * @param broadcastIp IP de broadcast (ej. {@code 255.255.255.255})
     * @return {@code true} si el paquete se envió correctamente
     */
    public static boolean sendMagicPacket(String macAddress, String broadcastIp) {
        if (!isValidMac(macAddress)) return false;

        byte[] macBytes = parseMac(macAddress);
        byte[] packet = buildMagicPacket(macBytes);

        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            InetAddress addr = InetAddress.getByName(broadcastIp);
            DatagramPacket datagram = new DatagramPacket(packet, packet.length, addr, WOL_PORT);
            socket.send(datagram);
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Convierte una MAC en texto (AA:BB:CC:DD:EE:FF) a su representación de 6 bytes.
     */
    static byte[] parseMac(String mac) {
        String clean = mac.replaceAll("[:-]", "");
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            int idx = i * 2;
            bytes[i] = (byte) Integer.parseInt(clean.substring(idx, idx + 2), 16);
        }
        return bytes;
    }

    /**
     * Construye el magic packet WoL:
     * 6 bytes 0xFF seguidos de la MAC repetida 16 veces → 102 bytes.
     */
    private static byte[] buildMagicPacket(byte[] macBytes) {
        byte[] packet = new byte[6 + 16 * macBytes.length];
        for (int i = 0; i < 6; i++) {
            packet[i] = (byte) 0xFF;
        }
        for (int i = 0; i < 16; i++) {
            System.arraycopy(macBytes, 0, packet, 6 + i * macBytes.length, macBytes.length);
        }
        return packet;
    }
}
