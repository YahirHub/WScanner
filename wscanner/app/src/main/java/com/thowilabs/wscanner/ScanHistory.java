package com.thowilabs.wscanner;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ScanHistory {

    public static class HistoryEntry {
        public final String filename;
        public final String date;
        public final int deviceCount;

        HistoryEntry(String filename, String date, int deviceCount) {
            this.filename = filename;
            this.date = date;
            this.deviceCount = deviceCount;
        }
    }

    private static final String DIR = "scan_history";
    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    /** Guarda una lista de dispositivos como archivo JSON. */
    public static void save(Context ctx, List<Device> devices) {
        try {
            File dir = new File(ctx.getFilesDir(), DIR);
            if (!dir.exists()) dir.mkdirs();

            String filename = "scan_" + System.currentTimeMillis() + ".json";
            File file = new File(dir, filename);

            JSONArray arr = new JSONArray();
            for (Device d : devices) {
                JSONObject obj = new JSONObject();
                obj.put("name", d.name);
                obj.put("ip", d.ip);
                obj.put("mac", d.mac != null ? d.mac : "N/A");
                obj.put("vendor", d.vendor != null ? d.vendor : "Desconocido");
                obj.put("discoveryMethod", d.discoveryMethod);
                obj.put("discoveryDetail", d.discoveryDetail != null ? d.discoveryDetail : "");
                obj.put("online", d.online);
                obj.put("userLabel", d.userLabel != null ? d.userLabel : "");
                obj.put("openPorts", new JSONArray(d.openPorts));
                obj.put("serviceNames", new JSONArray(d.serviceNames));
                obj.put("savedAt", System.currentTimeMillis());
                arr.put(obj);
            }

            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(arr.toString(2));
            writer.close();
        } catch (Exception ignored) {
        }
    }

    /** Carga el historial de escaneos guardados. */
    public static List<HistoryEntry> loadAll(Context ctx) {
        List<HistoryEntry> entries = new ArrayList<>();
        try {
            File dir = new File(ctx.getFilesDir(), DIR);
            if (!dir.exists()) return entries;

            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files == null) return entries;

            for (File f : files) {
                int count = countDevices(f);
                String name = f.getName();
                // Extraer timestamp del nombre: scan_TIMESTAMP.json
                long ts = 0;
                try {
                    ts = Long.parseLong(name.replace("scan_", "").replace(".json", ""));
                } catch (NumberFormatException ignored) {}
                String date = ts > 0 ? DATE_FMT.format(new Date(ts)) : name;
                entries.add(new HistoryEntry(name, date, count));
            }
            // Más reciente primero
            entries.sort((a, b) -> b.filename.compareTo(a.filename));
        } catch (Exception ignored) {
        }
        return entries;
    }

    /** Carga los dispositivos de un escaneo guardado. */
    public static List<Device> load(Context ctx, String filename) {
        List<Device> devices = new ArrayList<>();
        try {
            File file = new File(new File(ctx.getFilesDir(), DIR), filename);
            if (!file.exists()) return devices;

            FileInputStream fis = new FileInputStream(file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Device d = new Device(
                        obj.optString("name", "Desconocido"),
                        obj.optString("ip", ""),
                        obj.optString("mac", "N/A"),
                        obj.optString("vendor", "Desconocido"),
                        obj.optString("discoveryMethod", "Heurística"),
                        obj.optString("discoveryDetail", ""));
                d.online = obj.optBoolean("online", true);
                String label = obj.optString("userLabel", "");
                if (!label.isEmpty()) d.userLabel = label;

                JSONArray ports = obj.optJSONArray("openPorts");
                if (ports != null) {
                    for (int j = 0; j < ports.length(); j++)
                        d.openPorts.add(ports.getInt(j));
                }
                JSONArray svcs = obj.optJSONArray("serviceNames");
                if (svcs != null) {
                    for (int j = 0; j < svcs.length(); j++)
                        d.serviceNames.add(svcs.getString(j));
                }
                devices.add(d);
            }
        } catch (Exception ignored) {
        }
        return devices;
    }

    private static int countDevices(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int total = 0;
            while (total < data.length) {
                int read = fis.read(data, total, data.length - total);
                if (read < 0) break;
                total += read;
            }
            fis.close();
            if (total == 0) return 0;
            JSONArray arr = new JSONArray(new String(data, 0, total, "UTF-8"));
            return arr.length();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
