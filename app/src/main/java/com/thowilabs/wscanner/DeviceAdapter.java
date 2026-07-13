package com.thowilabs.wscanner;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder> {

    private final List<Device> items;

    public DeviceAdapter(List<Device> items) {
        this.items = items;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        LinearLayout card = new LinearLayout(parent.getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 12);
        card.setLayoutParams(params);
        card.setBackgroundColor(0xFFF5F5F5);
        card.setElevation(2f);

        TextView txtName = new TextView(parent.getContext());
        txtName.setTextSize(16);
        txtName.setTypeface(null, Typeface.BOLD);
        txtName.setTextColor(0xFF1A1A1A);

        TextView txtIp = new TextView(parent.getContext());
        txtIp.setTextSize(13);
        txtIp.setTextColor(0xFF666666);
        txtIp.setPadding(0, 4, 0, 0);

        TextView txtVendor = new TextView(parent.getContext());
        txtVendor.setTextSize(12);
        txtVendor.setTextColor(0xFF999999);
        txtVendor.setPadding(0, 2, 0, 0);

        TextView txtMethod = new TextView(parent.getContext());
        txtMethod.setTextSize(11);
        txtMethod.setTextColor(0xFF66BB6A);
        txtMethod.setPadding(0, 2, 0, 0);

        card.addView(txtName);
        card.addView(txtIp);
        card.addView(txtVendor);
        card.addView(txtMethod);

        return new Holder(card, txtName, txtIp, txtVendor, txtMethod);
    }

    @Override
    public void onBindViewHolder(Holder h, int i) {
        Device d = items.get(i);

        h.txtName.setText(deviceIcon(d) + " " + d.name);
        h.txtIp.setText(d.ip);

        String info = "";
        if (d.mac != null && !d.mac.equals("N/A")) {
            info = "MAC: " + d.mac;
        }
        if (d.vendor != null && !d.vendor.equals("Desconocido") && !d.vendor.equals(d.name)) {
            info += (info.isEmpty() ? "" : "  ·  ") + d.vendor;
        }
        h.txtVendor.setText(info);
        h.txtVendor.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);

        // Tap to copy IP
        h.itemView.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)
                    v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("IP", d.ip);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(v.getContext(), "📋 " + d.ip + " copiado", Toast.LENGTH_SHORT).show();
        });
        // Mostrar método de descubrimiento (solo si no es heurística)
        String method = d.discoveryMethod;
        if (method != null && !method.equals("Heurística")) {
            String label = methodLabel(method);
            String detail = d.discoveryDetail;
            if (detail != null && !detail.isEmpty() && detail.length() < 40) {
                h.txtMethod.setText("vía " + label + " · " + detail);
            } else {
                h.txtMethod.setText("vía " + label);
            }
            h.txtMethod.setVisibility(View.VISIBLE);
        } else {
            h.txtMethod.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /**
     * Traduce el código de método a una etiqueta legible.
     */
    private String methodLabel(String method) {
        switch (method) {
            case "mDNS":     return "Bonjour (mDNS)";
            case "SSDP":     return "UPnP (SSDP)";
            case "NetBIOS":  return "NetBIOS";
            case "DNS":      return "DNS inverso";
            case "HTTP":     return "HTTP banner";
            case "OUI DB":   return "Base OUI (MAC)";
            default:         return method;
        }
    }

    private String deviceIcon(Device d) {
        String v = (d.vendor != null) ? d.vendor.toLowerCase() : "";
        if (v.contains("router") || v.contains("gateway") || v.contains("cisco")
                || v.contains("tp-link") || v.contains("d-link") || v.contains("netgear")
                || v.contains("mikrotik") || v.contains("ubiquiti") || v.contains("huawei")
                || v.contains("zte") || v.contains("fiberhome"))
            return "\uD83D\uDDD8";
        if (v.contains("samsung") || v.contains("apple") || v.contains("iphone")
                || v.contains("xiaomi") || v.contains("oneplus") || v.contains("google")
                || v.contains("motorola") || v.contains("nokia") || v.contains("sony")
                || v.contains("oppo") || v.contains("vivo"))
            return "\uD83D\uDCF1";
        if (v.contains("tv") || v.contains("television") || v.contains("roku")
                || v.contains("chromecast") || v.contains("hisense") || v.contains("tcl")
                || v.contains("philips"))
            return "\uD83D\uDCFA";
        if (v.contains("dell") || v.contains("lenovo")
                || v.contains("acer") || v.contains("asus")
                || v.contains("msi") || v.contains("intel") || v.contains("gigabyte")
                || v.contains("realtek") || v.contains("broadcom"))
            return "\uD83D\uDCBB";
        if (v.contains("printer") || v.contains("brother") || v.contains("epson")
                || v.contains("canon") || v.contains("xerox") || v.contains("hewlett"))
            return "\uD83D\uDDA8";
        if (v.contains("camera") || v.contains("nest") || v.contains("ring")
                || v.contains("arlo") || v.contains("hikvision") || v.contains("dahua"))
            return "\uD83D\uDCF7";
        if (v.contains("alexa") || v.contains("echo") || v.contains("speaker")
                || v.contains("sonos") || v.contains("jbl"))
            return "\uD83D\uDD0A";
        if (v.contains("playstation") || v.contains("xbox") || v.contains("nintendo"))
            return "\uD83C\uDFAE";
        if (v.contains("kindle"))
            return "\uD83D\uDCD6";
        if (v.contains("raspberry") || v.contains("arduino") || v.contains("esp"))
            return "\uD83E\uDD16";
        return "\uD83D\uDCBB";
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView txtName;
        final TextView txtIp;
        final TextView txtVendor;
        final TextView txtMethod;

        Holder(View itemView, TextView txtName, TextView txtIp,
               TextView txtVendor, TextView txtMethod) {
            super(itemView);
            this.txtName = txtName;
            this.txtIp = txtIp;
            this.txtVendor = txtVendor;
            this.txtMethod = txtMethod;
        }
    }
}
