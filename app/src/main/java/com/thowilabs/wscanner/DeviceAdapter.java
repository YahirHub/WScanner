package com.thowilabs.wscanner;

import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.mikepenz.iconics.IconicsDrawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder>
        implements Filterable {

    private final List<Device> items;
    private List<Device> filteredItems;  // null = sin filtro activo, usar items directamente
    private OnDeviceClickListener clickListener;

    public interface OnDeviceClickListener {
        void onDeviceClick(Device device);
    }

    public DeviceAdapter(List<Device> items) {
        this.items = items;
        this.filteredItems = null;  // sin filtro → usar items directamente
    }

    public void setOnDeviceClickListener(OnDeviceClickListener listener) {
        this.clickListener = listener;
    }

    /** Ordena la lista por el criterio dado. */
    public void sortBy(String criteria) {
        Comparator<Device> comparator;
        switch (criteria) {
            case "name":
                comparator = Comparator.comparing(d -> d.name.toLowerCase());
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
                comparator = (a, b) -> {
                    String[] pa = a.ip.split("\\.");
                    String[] pb = b.ip.split("\\.");
                    for (int i = 0; i < 4; i++) {
                        int va = Integer.parseInt(pa[i]);
                        int vb = Integer.parseInt(pb[i]);
                        if (va != vb) return Integer.compare(va, vb);
                    }
                    return 0;
                };
                break;
        }
        Collections.sort(items, comparator);
        resetFilter();
        notifyDataSetChanged();
    }

    private void resetFilter() {
        filteredItems = null;  // sin filtro activo → usar items directamente
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        Context ctx = parent.getContext();

        // Card container
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(16, 16, 16, 16);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 10);
        card.setLayoutParams(params);
        card.setBackground(createCardBackground(ctx));
        card.setElevation(2f);

        // ── Contenedor del ícono con badge de estado ──
        android.widget.FrameLayout iconContainer = new android.widget.FrameLayout(ctx);
        LinearLayout.LayoutParams iconCtrParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        iconCtrParams.setMargins(0, 0, 14, 0);
        iconContainer.setLayoutParams(iconCtrParams);

        // Icono (ImageView + IconicsDrawable)
        ImageView iconView = new ImageView(ctx);
        iconView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                dp(44, ctx), dp(44, ctx)));
        iconView.setPadding(dp(6, ctx), dp(6, ctx), dp(6, ctx), dp(6, ctx));

        // Puntito verde superpuesto en esquina inferior derecha
        TextView badge = new TextView(ctx);
        badge.setText("●");
        badge.setTextColor(0xFF3FB950);
        badge.setTextSize(12);
        badge.setGravity(Gravity.CENTER);
        android.widget.FrameLayout.LayoutParams badgeParams =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.gravity = Gravity.BOTTOM | Gravity.END;
        badgeParams.setMargins(0, 0, dp(2, ctx), dp(2, ctx));
        badge.setLayoutParams(badgeParams);

        iconContainer.addView(iconView);
        iconContainer.addView(badge);

        // ── Columna de texto ──
        LinearLayout rightCol = new LinearLayout(ctx);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightCol.setLayoutParams(rightParams);

        TextView txtName = new TextView(ctx);
        txtName.setTextSize(14);
        txtName.setTextColor(0xFFE6EDF3);
        txtName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        txtName.setPadding(0, 0, 0, 4);

        TextView txtIp = new TextView(ctx);
        txtIp.setTextSize(12);
        txtIp.setTextColor(0xFF00E5FF);
        txtIp.setTypeface(android.graphics.Typeface.MONOSPACE);
        txtIp.setPadding(0, 0, 0, 2);

        TextView txtVendor = new TextView(ctx);
        txtVendor.setTextSize(11);
        txtVendor.setTextColor(0xFF8B949E);
        txtVendor.setPadding(0, 0, 0, 2);

        TextView txtMethod = new TextView(ctx);
        txtMethod.setTextSize(10);
        txtMethod.setTextColor(0xFF3FB950);
        txtMethod.setPadding(0, 2, 0, 0);

        rightCol.addView(txtName);
        rightCol.addView(txtIp);
        rightCol.addView(txtVendor);
        rightCol.addView(txtMethod);

        // ── Badge de tipo ──
        LinearLayout typeCol = new LinearLayout(ctx);
        typeCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        typeParams.setMarginStart(8);
        typeCol.setLayoutParams(typeParams);

        TextView txtType = new TextView(ctx);
        txtType.setTextSize(10);
        txtType.setTextColor(0xFF00E5FF);
        txtType.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        txtType.setPadding(10, 4, 10, 4);
        txtType.setBackgroundColor(0x1A00E5FF);

        typeCol.addView(txtType);

        // ── Premium: Ripple touch feedback ──
        int[] attrs = new int[]{android.R.attr.selectableItemBackground};
        TypedArray ta = ctx.obtainStyledAttributes(attrs);
        Drawable ripple = ta.getDrawable(0);
        ta.recycle();
        if (ripple != null) {
            card.setForeground(ripple);
        }

        // ── Premium: StateListAnimator for elevation on press ──
        StateListAnimator sla = new StateListAnimator();
        sla.addState(new int[]{android.R.attr.state_pressed},
                ObjectAnimator.ofFloat(card, "elevation", dp(2, ctx), dp(8, ctx)).setDuration(120));
        sla.addState(new int[]{},
                ObjectAnimator.ofFloat(card, "elevation", dp(8, ctx), dp(2, ctx)).setDuration(200));
        card.setStateListAnimator(sla);

        // ── Premium: Press state scale animation ──
        card.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start();
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                            .start();
                    return false;
            }
            return false;
        });

        card.addView(iconContainer);
        card.addView(rightCol);
        card.addView(typeCol);

        return new Holder(card, iconView, txtName, txtIp, txtVendor, txtMethod, txtType);
    }

    @Override
    public void onBindViewHolder(Holder h, int i) {
        Device d = (filteredItems != null) ? filteredItems.get(i) : items.get(i);
        Context ctx = h.itemView.getContext();

        // Icono profesional via IconicsDrawable
        int sizePx = dp(32, ctx);
        IconicsDrawable drawable = new IconicsDrawable(ctx, deviceIconName(d));
        drawable.setColorList(android.content.res.ColorStateList.valueOf(0xFF00E5FF));
        drawable.setSizeXPx(sizePx);
        drawable.setSizeYPx(sizePx);
        h.iconView.setImageDrawable(drawable);

        // Show userLabel if set, otherwise device name
        String displayName = (d.userLabel != null && !d.userLabel.isEmpty())
                ? d.userLabel + "  ·  " + d.name
                : d.name;
        h.txtName.setText(displayName);
        h.txtIp.setText(d.ip);

        // Vendor / MAC
        String info = "";
        if (d.mac != null && !d.mac.equals("N/A")) {
            info = "MAC " + d.mac;
        }
        if (d.vendor != null && !d.vendor.equals("Desconocido") && !d.vendor.equals(d.name)) {
            info += (info.isEmpty() ? "" : "  ·  ") + d.vendor;
        }
        if (d.manufacturer != null && !d.manufacturer.isEmpty()
                && (d.vendor == null || !d.manufacturer.equalsIgnoreCase(d.vendor))) {
            info += (info.isEmpty() ? "" : "  ·  ") + d.manufacturer;
        }
        if (d.model != null && !d.model.isEmpty()) {
            info += (info.isEmpty() ? "" : "  ·  ") + d.model;
        }
        if (d.deviceType != null && !d.deviceType.isEmpty() && !d.deviceType.equalsIgnoreCase(d.name)) {
            info += (info.isEmpty() ? "" : "  ·  ") + d.deviceType;
        }
        h.txtVendor.setText(info);
        h.txtVendor.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);

        // Método de descubrimiento
        String method = d.discoveryMethod;
        if (method != null && !method.equals("Heurística")) {
            h.txtMethod.setText("vía " + methodLabel(method));
            h.txtMethod.setVisibility(View.VISIBLE);
        } else {
            h.txtMethod.setVisibility(View.GONE);
        }

        // Type badge
        if (d.discoveryMethod != null && !d.discoveryMethod.equals("Heurística")) {
            h.txtType.setText(shortLabel(d.discoveryMethod));
            h.txtType.setVisibility(View.VISIBLE);
        } else {
            h.txtType.setVisibility(View.GONE);
        }

        // Actualizar estado sin reiniciar alpha a cero. En modo monitor una tarjeta
        // existente puede recibir varias señales por ciclo; reanimarla desde 0 en cada
        // notifyItemChanged provocaba parpadeos y daba sensación de desconexión.
        float targetAlpha = d.online ? 1f : 0.45f;
        // El monitor puede reenlazar esta fila muchas veces durante un mismo ciclo.
        // Cancelar cualquier animación heredada y aplicar el estado directamente evita
        // que una ViewPropertyAnimator sobreviva al reciclado/reatachado del ViewHolder.
        h.itemView.animate().cancel();
        h.itemView.setTranslationY(0f);
        h.itemView.setAlpha(targetAlpha);

        // ── Online/offline badge ──
        android.view.ViewGroup parent = (android.view.ViewGroup) h.iconView.getParent();
        if (parent != null && parent.getChildCount() > 1) {
            View badge = parent.getChildAt(1);
            if (badge instanceof TextView) {
                TextView badgeTv = (TextView) badge;
                badgeTv.setTextColor(d.online ? 0xFF3FB950 : 0xFF6E7681);
                badgeTv.setText(d.online ? "●" : "○");
            }
        }

        // Tap → device detail (or open browser / copy IP as fallback)
        h.itemView.setOnClickListener(v -> {
            // Long press → copy IP (legacy behavior)
            // Normal tap → device detail
            if (clickListener != null) {
                HapticUtil.performClick(v);
                clickListener.onDeviceClick(d);
            } else {
                // Fallback: copy IP
                ClipboardManager clipboard = (ClipboardManager)
                        v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("IP", d.ip);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(v.getContext(), d.ip + " copiado", Toast.LENGTH_SHORT).show();
                HapticUtil.performClick(v);
            }
        });

        // Long press → copy IP always
        h.itemView.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)
                    v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("IP", d.ip);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(v.getContext(), d.ip + " copiado", Toast.LENGTH_SHORT).show();
            HapticUtil.performClick(v);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return filteredItems != null ? filteredItems.size() : items.size();
    }

    // ── Filterable implementation ──────────────────────────────────

    @Override
    public Filter getFilter() {
        return deviceFilter;
    }

    private final Filter deviceFilter = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            // ponytail: ConcurrentModification posible si se filtra durante escaneo;
            // sincronizar sobre items si se vuelve necesario.
            List<Device> filtered = new ArrayList<>();
            if (constraint == null || constraint.length() == 0) {
                filtered.addAll(items);
            } else {
                String query = constraint.toString().toLowerCase().trim();
                for (Device d : items) {
                    if (matches(d, query)) {
                        filtered.add(d);
                    }
                }
            }
            FilterResults results = new FilterResults();
            results.values = filtered;
            return results;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence constraint, FilterResults results) {
            if (constraint == null || constraint.length() == 0) {
                filteredItems = null;  // sin filtro → usar items directamente
            } else {
                filteredItems = (List<Device>) results.values;
            }
            notifyDataSetChanged();
        }
    };

    private boolean matches(Device d, String query) {
        if (d.name != null && d.name.toLowerCase().contains(query)) return true;
        if (d.ip != null && d.ip.toLowerCase().contains(query)) return true;
        if (d.vendor != null && d.vendor.toLowerCase().contains(query)) return true;
        if (d.mac != null && d.mac.toLowerCase().contains(query)) return true;
        if (d.discoveryMethod != null && d.discoveryMethod.toLowerCase().contains(query)) return true;
        if (d.deviceType != null && d.deviceType.toLowerCase().contains(query)) return true;
        if (d.manufacturer != null && d.manufacturer.toLowerCase().contains(query)) return true;
        if (d.model != null && d.model.toLowerCase().contains(query)) return true;
        if (d.osHint != null && d.osHint.toLowerCase().contains(query)) return true;
        return false;
    }

    private String deviceIconName(Device d) {
        String type = d.deviceType == null ? "" : d.deviceType.toLowerCase();
        String name = d.name == null ? "" : d.name.toLowerCase();
        String signals = type + " " + name + " " + String.join(" ", d.serviceNames).toLowerCase();

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
        if (signals.contains("pc") || signals.contains("estación de trabajo")
                || signals.contains("rdp") || signals.contains("vnc")) return "cmd_laptop";
        return "cmd_laptop";
    }

    // ── Labels ──────────────────────────────────────────────────────

    private int discoveryMethodRank(String method) {
        return DeviceIdentity.sourceRank(method);
    }

    private String methodLabel(String method) {
        switch (method) {
            case "mDNS":     return "Bonjour (mDNS)";
            case "SSDP":          return "UPnP (SSDP)";
            case "WS-Discovery": return "WS-Discovery";
            case "SNMP":          return "SNMP";
            case "Gateway":       return "Gateway";
            case "NetBIOS":       return "NetBIOS";
            case "DNS":      return "DNS inverso";
            case "HTTP":     return "HTTP local";
            case "TLS":      return "certificado TLS";
            case "OUI DB":   return "OUI (MAC)";
            default:         return method;
        }
    }

    private String shortLabel(String method) {
        switch (method) {
            case "SSDP":          return "UPnP";
            case "WS-Discovery": return "WSD";
            case "SNMP":          return "SNMP";
            case "Gateway":       return "GW";
            case "mDNS":          return "MDNS";
            case "NetBIOS":       return "NETB";
            case "OUI DB":        return "MAC";
            case "TLS":           return "TLS";
            default:         return method.length() > 5
                    ? method.substring(0, 4).toUpperCase() : method.toUpperCase();
        }
    }

    // ── Fondo de tarjeta ────────────────────────────────────────────

    private android.graphics.drawable.Drawable createCardBackground(Context ctx) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(12 * ctx.getResources().getDisplayMetrics().density);
        bg.setColor(0xFF1C2333);
        bg.setStroke((int)(1 * ctx.getResources().getDisplayMetrics().density), 0xFF30363D);
        return bg;
    }

    private int dp(int dp, Context ctx) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density);
    }

    // ── Public helpers ────────────────────────────────────────────
    // (ping y openInBrowser se manejan en MainActivity para acceso al contexto de Activity)

    // ── ViewHolder ───────────────────────────────────────────────────

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView iconView;
        final TextView txtName;
        final TextView txtIp;
        final TextView txtVendor;
        final TextView txtMethod;
        final TextView txtType;

        Holder(View itemView, ImageView iconView,
               TextView txtName, TextView txtIp,
               TextView txtVendor, TextView txtMethod,
               TextView txtType) {
            super(itemView);
            this.iconView = iconView;
            this.txtName = txtName;
            this.txtIp = txtIp;
            this.txtVendor = txtVendor;
            this.txtMethod = txtMethod;
            this.txtType = txtType;
        }
    }
}
