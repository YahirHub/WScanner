package com.thowilabs.wscanner;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.mikepenz.iconics.IconicsDrawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder>
        implements Filterable {

    private final List<Device> items;
    private final Set<String> revealedDevices = new HashSet<>();
    private List<Device> filteredItems;
    private OnDeviceClickListener clickListener;

    public interface OnDeviceClickListener {
        void onDeviceClick(Device device);
    }

    public DeviceAdapter(List<Device> items) {
        this.items = items;
        this.filteredItems = null;
        setHasStableIds(true);
    }

    public void setOnDeviceClickListener(OnDeviceClickListener listener) {
        this.clickListener = listener;
    }

    public void sortBy(String criteria) {
        Comparator<Device> comparator;
        switch (criteria) {
            case "name":
                comparator = Comparator.comparing(d -> safeLower(d.name));
                break;
            case "vendor":
                comparator = Comparator.comparing(d ->
                        (d.vendor != null && !d.vendor.equals("Desconocido"))
                                ? d.vendor.toLowerCase() : "zzz");
                break;
            case "method":
                comparator = Comparator.comparingInt(d ->
                        d.discoveryMethod != null ? discoveryMethodRank(d.discoveryMethod) : 0);
                break;
            case "ip":
            default:
                comparator = (a, b) -> compareIp(a.ip, b.ip);
                break;
        }
        Collections.sort(items, comparator);
        resetFilter();
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        Device device = itemAt(position);
        if (device == null || device.ip == null) return RecyclerView.NO_ID;
        String[] parts = device.ip.split("\\.");
        if (parts.length == 4) {
            try {
                long value = 0;
                for (String part : parts) value = (value << 8) | Integer.parseInt(part);
                return value;
            } catch (NumberFormatException ignored) {
                // Fallback estable por texto.
            }
        }
        return device.ip.hashCode();
    }

    private void resetFilter() {
        filteredItems = null;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        PressStateUtil.attach(view, 0.975f);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Device d = itemAt(position);
        if (d == null) return;
        Context ctx = h.itemView.getContext();

        int iconSize = dp(34, ctx);
        IconicsDrawable drawable = new IconicsDrawable(ctx, deviceIconName(d));
        drawable.setColorList(ColorStateList.valueOf(0xFF42D9FF));
        drawable.setSizeXPx(iconSize);
        drawable.setSizeYPx(iconSize);
        h.iconView.setImageDrawable(drawable);

        String displayName = (d.userLabel != null && !d.userLabel.isEmpty())
                ? d.userLabel + "  ·  " + d.name
                : d.name;
        h.txtName.setText(displayName);
        h.txtIp.setText(d.ip);

        String info = buildIdentityLine(d);
        h.txtVendor.setText(info);
        h.txtVendor.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);

        String method = d.discoveryMethod;
        if (method != null && !method.equals("Heurística")) {
            h.txtMethod.setText("Detectado por " + methodLabel(method));
            h.txtMethod.setVisibility(View.VISIBLE);
            h.txtType.setText(shortLabel(method));
            h.txtType.setVisibility(View.VISIBLE);
        } else {
            h.txtMethod.setVisibility(View.GONE);
            h.txtType.setVisibility(View.GONE);
        }

        float targetAlpha = d.online ? 1f : 0.48f;
        h.itemView.animate().cancel();
        h.itemView.setScaleX(1f);
        h.itemView.setScaleY(1f);
        h.itemView.setTranslationY(0f);
        h.itemView.setAlpha(targetAlpha);

        int dotColor = d.online ? 0xFF4ADE80 : 0xFF68778C;
        h.statusDot.setBackgroundTintList(ColorStateList.valueOf(dotColor));
        h.card.setStrokeColor(d.online ? 0xFF223149 : 0xFF182235);

        h.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                HapticUtil.performClick(v);
                clickListener.onDeviceClick(d);
            } else {
                copyIp(v, d.ip);
            }
        });
        h.itemView.setOnLongClickListener(v -> {
            copyIp(v, d.ip);
            return true;
        });

        if (revealedDevices.add(d.ip)) {
            h.itemView.setAlpha(0f);
            h.itemView.setTranslationY(dp(14, ctx));
            h.itemView.animate()
                    .alpha(targetAlpha)
                    .translationY(0f)
                    .setDuration(230L)
                    .setStartDelay(Math.min(position * 28L, 180L))
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .start();
        }
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        PressStateUtil.reset(holder.itemView);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return filteredItems != null ? filteredItems.size() : items.size();
    }

    @Override
    public Filter getFilter() {
        return deviceFilter;
    }

    private final Filter deviceFilter = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            List<Device> filtered = new ArrayList<>();
            if (constraint == null || constraint.length() == 0) {
                filtered.addAll(items);
            } else {
                String query = constraint.toString().toLowerCase().trim();
                for (Device d : new ArrayList<>(items)) {
                    if (matches(d, query)) filtered.add(d);
                }
            }
            FilterResults results = new FilterResults();
            results.values = filtered;
            return results;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence constraint, FilterResults results) {
            filteredItems = (constraint == null || constraint.length() == 0)
                    ? null : (List<Device>) results.values;
            notifyDataSetChanged();
        }
    };

    private Device itemAt(int position) {
        List<Device> source = filteredItems != null ? filteredItems : items;
        return position >= 0 && position < source.size() ? source.get(position) : null;
    }

    private boolean matches(Device d, String query) {
        return contains(d.name, query)
                || contains(d.ip, query)
                || contains(d.vendor, query)
                || contains(d.mac, query)
                || contains(d.discoveryMethod, query)
                || contains(d.deviceType, query)
                || contains(d.manufacturer, query)
                || contains(d.model, query)
                || contains(d.osHint, query);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }

    private String buildIdentityLine(Device d) {
        StringBuilder info = new StringBuilder();
        if (d.vendor != null && !d.vendor.equals("Desconocido") && !d.vendor.equals(d.name)) {
            appendPart(info, d.vendor);
        }
        if (d.manufacturer != null && !d.manufacturer.isEmpty()
                && (d.vendor == null || !d.manufacturer.equalsIgnoreCase(d.vendor))) {
            appendPart(info, d.manufacturer);
        }
        if (d.model != null && !d.model.isEmpty()) appendPart(info, d.model);
        if (d.deviceType != null && !d.deviceType.isEmpty()
                && !d.deviceType.equalsIgnoreCase(d.name)) {
            appendPart(info, d.deviceType);
        }
        if (info.length() == 0 && d.mac != null && !d.mac.equals("N/A")) {
            appendPart(info, "MAC " + d.mac);
        }
        return info.toString();
    }

    private void appendPart(StringBuilder builder, String value) {
        if (builder.length() > 0) builder.append("  ·  ");
        builder.append(value);
    }

    private void copyIp(View view, String ip) {
        ClipboardManager clipboard = (ClipboardManager)
                view.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("IP", ip));
            Toast.makeText(view.getContext(), ip + " copiado", Toast.LENGTH_SHORT).show();
        }
        HapticUtil.performClick(view);
    }

    private String deviceIconName(Device d) {
        String type = d.deviceType == null ? "" : d.deviceType.toLowerCase();
        String name = d.name == null ? "" : d.name.toLowerCase();
        String signals = type + " " + name + " "
                + String.join(" ", d.serviceNames).toLowerCase();

        if (signals.contains("router") || signals.contains("gateway")
                || signals.contains("infraestructura de red") || signals.contains("punto de acceso")
                || signals.contains("switch de red")) return "cmd_router_network";
        if (signals.contains("cámara") || signals.contains("camera") || signals.contains("video")
                || signals.contains("onvif")) return "cmd_camera";
        if (signals.contains("impresora") || signals.contains("printer")) return "cmd_printer";
        if (signals.contains("reproductor multimedia") || signals.contains("android tv")
                || signals.contains("airplay") || signals.contains("googlecast")) return "cmd_television";
        if (signals.contains("iot") || signals.contains("domótica") || signals.contains("homekit")
                || signals.contains("mqtt")) return "cmd_chip";
        if (signals.contains("dispositivo android") || signals.contains("teléfono")
                || signals.contains("phone")) return "cmd_cellphone";
        if (signals.contains("tablet")) return "cmd_tablet_android";
        if (signals.contains("servidor") || signals.contains("nas") || signals.contains("ssh")
                || signals.contains("nfs") || signals.contains("smb")) return "cmd_server";
        return "cmd_laptop";
    }

    private int discoveryMethodRank(String method) {
        return DeviceIdentity.sourceRank(method);
    }

    private String methodLabel(String method) {
        switch (method) {
            case "mDNS": return "Bonjour";
            case "SSDP": return "UPnP";
            case "WS-Discovery": return "WS-Discovery";
            case "SNMP": return "SNMP";
            case "Gateway": return "gateway";
            case "NetBIOS": return "NetBIOS";
            case "DNS": return "DNS inverso";
            case "HTTP": return "HTTP local";
            case "TLS": return "certificado TLS";
            case "OUI DB": return "dirección MAC";
            default: return method;
        }
    }

    private String shortLabel(String method) {
        switch (method) {
            case "SSDP": return "UPNP";
            case "WS-Discovery": return "WSD";
            case "Gateway": return "GW";
            case "mDNS": return "MDNS";
            case "NetBIOS": return "NETB";
            case "OUI DB": return "MAC";
            default:
                return method.length() > 5
                        ? method.substring(0, 4).toUpperCase() : method.toUpperCase();
        }
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    private static int compareIp(String a, String b) {
        try {
            String[] pa = a.split("\\.");
            String[] pb = b.split("\\.");
            for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
                int va = Integer.parseInt(pa[i]);
                int vb = Integer.parseInt(pb[i]);
                if (va != vb) return Integer.compare(va, vb);
            }
        } catch (Exception ignored) {
            return safeLower(a).compareTo(safeLower(b));
        }
        return 0;
    }

    private static int dp(int value, Context context) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView iconView;
        final View statusDot;
        final TextView txtName;
        final TextView txtIp;
        final TextView txtVendor;
        final TextView txtMethod;
        final TextView txtType;

        Holder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.cardDevice);
            iconView = itemView.findViewById(R.id.deviceIcon);
            statusDot = itemView.findViewById(R.id.deviceStatusDot);
            txtName = itemView.findViewById(R.id.deviceName);
            txtIp = itemView.findViewById(R.id.deviceIp);
            txtVendor = itemView.findViewById(R.id.deviceVendor);
            txtMethod = itemView.findViewById(R.id.deviceMethod);
            txtType = itemView.findViewById(R.id.deviceTypeChip);
        }
    }
}
