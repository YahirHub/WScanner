package com.thowilabs.wscanner;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Carga la base de datos OUI desde un archivo JSON en assets.
 * Formato: {"AABBCC": "Nombre del Fabricante", ...}
 * Ejemplo: {"001977": "Extreme Networks Headquarters", ...}
 */
public class VendorResolver {

    private static final String TAG = "WScanner.Vendor";
    private final Map<String, String> ouiMap = new HashMap<>();

    /**
     * Carga la base OUI desde assets/oui_database.json.
     */
    public void load(Context context) {
        long t0 = System.currentTimeMillis();
        Log.d(TAG, "═══════════════════════════════════════");
        Log.d(TAG, "Cargando base OUI desde assets/oui_database.json...");
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            context.getAssets().open("oui_database.json")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            Log.d(TAG, "Archivo leído: " + sb.length() + " bytes");

            JSONObject json = new JSONObject(sb.toString());
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                ouiMap.put(key, json.getString(key));
            }
            long elapsed = System.currentTimeMillis() - t0;
            Log.i(TAG, "✅ Base OUI cargada: " + ouiMap.size() + " entradas en " + elapsed + " ms");
            Log.d(TAG, "═══════════════════════════════════════");
        } catch (Exception e) {
            Log.e(TAG, "❌ ERROR al cargar OUI database: " + e.getMessage(), e);
        }
    }

    /**
     * Resuelve fabricante a partir de una MAC.
     * @param mac MAC en formato AA:BB:CC:DD:EE:FF, AABBCCDDEEFF, etc.
     * @return Nombre del fabricante o "Desconocido"
     */
    public String resolve(String mac) {
        if (mac == null || mac.isEmpty()) {
            Log.v(TAG, "resolve('" + mac + "') → Desconocido (null/vacía)");
            return "Desconocido";
        }

        String clean = mac.replaceAll("[:\\-. ]", "").toUpperCase();
        if (clean.length() < 6) {
            Log.v(TAG, "resolve('" + mac + "') → Desconocido (MAC muy corta: " + clean.length() + " chars)");
            return "Desconocido";
        }

        // MAC locally-administered (bit 1 del primer octeto) → aleatoria/privada.
        // Android 10+, iOS 14+ y Windows 10+ rotan la MAC por red para preservar
        // la privacidad, y la base OUI no contiene esos prefijos por diseño.
        try {
            int firstOctet = Integer.parseInt(clean.substring(0, 2), 16);
            if ((firstOctet & 0x02) != 0) {
                Log.v(TAG, "resolve('" + mac + "') → MAC aleatoria (LAA)");
                return "MAC aleatoria (privacidad)";
            }
        } catch (NumberFormatException ignored) {}

        String prefix = clean.substring(0, 6);
        String vendor = ouiMap.get(prefix);
        String result = (vendor != null) ? vendor : "Desconocido";
        Log.v(TAG, "resolve('" + mac + "') → prefix=" + prefix + " → " + result);
        return result;
    }

    public int getEntryCount() {
        return ouiMap.size();
    }

    /**
     * Devuelve true si la MAC tiene el bit "locally administered" activo,
     * síntoma de que el sistema operativo la aleatoriza por privacidad.
     * Útil para que la UI muestre un aviso en lugar de un fabricante inventado.
     */
    public static boolean isRandomized(String mac) {
        if (mac == null) return false;
        String clean = mac.replaceAll("[:\\-. ]", "").toUpperCase();
        if (clean.length() < 2) return false;
        try {
            int firstOctet = Integer.parseInt(clean.substring(0, 2), 16);
            return (firstOctet & 0x02) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
