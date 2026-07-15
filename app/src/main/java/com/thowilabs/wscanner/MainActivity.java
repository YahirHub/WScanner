package com.thowilabs.wscanner;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.AutoTransition;
import androidx.transition.Fade;
import androidx.transition.Slide;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.mikepenz.iconics.IconicsDrawable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private static final String TAG = "WScanner.UI";

    private DrawerLayout drawerLayout;
    private NavigationView navView;

    private final ArrayList<Device> devices = new ArrayList<>();
    private final Map<String, Integer> ipIndex = new HashMap<>();
    private DeviceAdapter adapter;

    private FloatingActionButton btnScan;
    private ProgressBar progressScan;
    private TextView txtStatus;
    private TextView txtSubnetValue;
    private TextView txtDeviceCount;
    private TextView txtGatewayValue;
    private LinearLayout layoutNetworkInfo;
    // Premium: empty state & placeholders
    private View layoutEmpty;
    private View layoutPlaceholders;
    private TextView txtEmptyHint;
    private boolean hasScannedBefore = false;

    // Acerca de view (embedded, no separate activity)
    private View layoutAbout;
    private View layoutScannerContent;
    private boolean showingAbout = false;

    // Device detail view (embedded)
    private View layoutDeviceDetail;
    private boolean showingDeviceDetail = false;
    private Device currentDetailDevice;

    // Sort chips
    private View chipSortIp, chipSortName, chipSortVendor, chipSortMethod;
    private String currentSort = "ip";

    // Premium: FAB rotation animation
    private ObjectAnimator fabRotationAnim;

    // Pulse animation for empty state radar
    private ObjectAnimator emptyPulseAnim;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        // Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Drawer + hamburguesa
        drawerLayout = findViewById(R.id.drawerLayout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.drawer_open, R.string.drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        // Navegación lateral
        navView = findViewById(R.id.navView);
        navView.setNavigationItemSelectedListener(this);
        navView.setCheckedItem(R.id.nav_scanner);

        // UI references
        btnScan = findViewById(R.id.btnScan);
        progressScan = findViewById(R.id.progressScan);
        txtStatus = findViewById(R.id.txtStatus);
        txtSubnetValue = findViewById(R.id.txtSubnetValue);
        txtDeviceCount = findViewById(R.id.txtDeviceCount);
        txtGatewayValue = findViewById(R.id.txtGatewayValue);
        layoutNetworkInfo = (LinearLayout) findViewById(R.id.layoutNetworkInfo);

        // Premium: empty state & placeholders
        layoutEmpty = findViewById(R.id.layoutEmpty);
        layoutPlaceholders = findViewById(R.id.layoutPlaceholders);
        txtEmptyHint = findViewById(R.id.txtEmptyHint);

        // Acerca de view
        layoutAbout = findViewById(R.id.layoutAbout);
        layoutScannerContent = findViewById(R.id.layoutScannerContent);

        // Device detail view
        layoutDeviceDetail = findViewById(R.id.layoutDeviceDetail);

        // Sort chips
        chipSortIp = findViewById(R.id.chipSortIp);
        chipSortName = findViewById(R.id.chipSortName);
        chipSortVendor = findViewById(R.id.chipSortVendor);
        chipSortMethod = findViewById(R.id.chipSortMethod);

        // RecyclerView
        RecyclerView rv = findViewById(R.id.listDevices);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DeviceAdapter(devices);
        adapter.setOnDeviceClickListener(this::showDeviceDetail);
        rv.setAdapter(adapter);

        // Mostrar info de red actual
        updateNetworkInfo();

        // FAB click with haptic feedback
        btnScan.setOnClickListener(v -> {
            HapticUtil.performConfirm(btnScan);
            startScan();
        });

        // FAB press state animation
        btnScan.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(100).start();
                    return false;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    return false;
            }
            return false;
        });

        // Sort chip click handlers
        setupSortChips();

        // Premium: setup FAB rotation animation
        setupFabRotation();

        // Premium: setup empty state pulse animation
        setupEmptyPulse();
    }

    // ── Info de red ────────────────────────────────────────────────

    private void updateNetworkInfo() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                WifiInfo info = wifi.getConnectionInfo();
                int ip = info.getIpAddress();
                if (ip != 0) {
                    String subnet = String.format("%d.%d.%d.0/24",
                            (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff));
                    txtSubnetValue.setText(subnet);
                }
                android.net.DhcpInfo dhcp = wifi.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    int gw = dhcp.gateway;
                    txtGatewayValue.setText(String.format("%d.%d.%d.%d",
                            (gw & 0xff), (gw >> 8 & 0xff),
                            (gw >> 16 & 0xff), (gw >> 24 & 0xff)));
                }

                // Premium: fade-in animation for network info header
                if (layoutNetworkInfo.getVisibility() != View.VISIBLE) {
                    layoutNetworkInfo.setAlpha(0f);
                    layoutNetworkInfo.setTranslationY(-20f);
                    layoutNetworkInfo.setVisibility(View.VISIBLE);
                    layoutNetworkInfo.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(400)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                }
            }
        } catch (Exception ignored) {
            Log.w(TAG, "No se pudo leer info de WiFi");
        }
    }

    // ── Drawer clicks ──────────────────────────────────────────────

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_scanner) {
            showScanner();
        } else if (id == R.id.nav_about) {
            showAbout();
        }

        drawerLayout.closeDrawer(navView);
        return true;
    }

    // ── Search setup (called from menu) ─────────────────────────

    private void setupSearchView(Menu menu) {
        MenuItem searchItem = menu.findItem(R.id.action_search);
        if (searchItem == null) return;

        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView == null) return;

        searchView.setQueryHint("Buscar por nombre, IP, vendor…");
        searchView.setMaxWidth(Integer.MAX_VALUE);

        // Style
        SearchView.SearchAutoComplete autoComplete = searchView.findViewById(
                androidx.appcompat.R.id.search_src_text);
        if (autoComplete != null) {
            autoComplete.setTextColor(0xFFE6EDF3);
            autoComplete.setHintTextColor(0xFF6E7681);
        }

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                hideKeyboard();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                adapter.getFilter().filter(newText);
                return true;
            }
        });

        searchView.setOnCloseListener(() -> {
            hideKeyboard();
            return false;
        });
    }

    private void showScanner() {
        if (showingAbout || showingDeviceDetail) {
            showingAbout = false;
            showingDeviceDetail = false;
            currentDetailDevice = null;

            // Animación de transición: el contenido actual sale, scanner entra
            ViewGroup root = findViewById(R.id.rootConstraint);
            TransitionManager.beginDelayedTransition(root,
                    new AutoTransition());

            layoutScannerContent.setVisibility(View.VISIBLE);
            btnScan.setVisibility(View.VISIBLE);
            layoutAbout.setVisibility(View.GONE);
            layoutDeviceDetail.setVisibility(View.GONE);

            navView.setCheckedItem(R.id.nav_scanner);
        }
    }

    private void showAbout() {
        if (showingAbout) return;
        showingAbout = true;
        showingDeviceDetail = false;
        currentDetailDevice = null;

        // Animación de transición: fade + slide
        ViewGroup root = findViewById(R.id.rootConstraint);
        TransitionSet set = new TransitionSet()
                .addTransition(new Slide(android.view.Gravity.END))
                .addTransition(new Fade())
                .setDuration(350);
        TransitionManager.beginDelayedTransition(root, set);

        layoutScannerContent.setVisibility(View.GONE);
        btnScan.setVisibility(View.GONE);
        layoutDeviceDetail.setVisibility(View.GONE);
        layoutAbout.setVisibility(View.VISIBLE);
        navView.setCheckedItem(R.id.nav_about);
    }

    // ── Escaneo ────────────────────────────────────────────────────

    private void startScan() {
        Log.i(TAG, "🧹 Limpiando lista previa (" + devices.size() + " dispositivos)");
        hasScannedBefore = true;
        devices.clear();
        ipIndex.clear();
        adapter.notifyDataSetChanged();
        updateNetworkInfo();

        setScanning(true);
        Log.i(TAG, "🚀 Iniciando NetworkScanner...");

        new NetworkScanner().scan(this, new NetworkScanner.Callback() {
            @Override
            public void onDeviceFound(Device device) {
                runOnUiThread(() -> {
                    Integer existingIdx = ipIndex.get(device.ip);

                    if (existingIdx != null) {
                        Device existing = devices.get(existingIdx);

                        // Merge complementary info (ports, mac) always
                        if (!device.openPorts.isEmpty()) {
                            existing.openPorts = device.openPorts;
                            existing.serviceNames = device.serviceNames;
                        }
                        if (device.mac != null && !device.mac.equals("N/A")
                                && (existing.mac == null || existing.mac.equals("N/A"))) {
                            existing.mac = device.mac;
                            existing.vendor = device.vendor;
                        }

                        // Replace only if new source is better
                        if (isBetterSource(device.discoveryMethod, existing.discoveryMethod)) {
                            existing.name = device.name;
                            existing.vendor = device.vendor;
                            existing.mac = device.mac;
                            existing.discoveryMethod = device.discoveryMethod;
                            existing.discoveryDetail = device.discoveryDetail;
                            adapter.notifyItemChanged(existingIdx);
                            Log.d(TAG, "🔄 Actualizado: " + device.ip
                                    + " → \"" + device.name + "\" (" + device.discoveryMethod + ")");
                        } else if (!device.openPorts.isEmpty()) {
                            // Only ports updated, still need to refresh UI
                            adapter.notifyItemChanged(existingIdx);
                        }
                    } else {
                        // Nuevo dispositivo
                        devices.add(device);
                        int idx = devices.size() - 1;
                        ipIndex.put(device.ip, idx);
                        adapter.notifyItemInserted(idx);
                        txtDeviceCount.setText(String.valueOf(devices.size()));
                        Log.d(TAG, "➕ Device #" + devices.size() + ": "
                                + device.ip + " → \"" + device.name
                                + "\" [" + device.discoveryMethod + "]");
                    }

                    // Premium: hide placeholders when first device appears
                    if (devices.size() == 1) {
                        layoutPlaceholders.setVisibility(View.GONE);
                    }

                    updateEmptyState();
                });
            }

            @Override
            public void onProgress(int percent, int scanned, int total) {
                runOnUiThread(() -> {
                    progressScan.setProgress(percent);
                    txtStatus.setText(String.format("Escaneando… %d/%d  · %d encontrados",
                            scanned, total, devices.size()));
                });
            }

            @Override
            public void onFinished(int totalFound) {
                runOnUiThread(() -> {
                    setScanning(false);
                    txtStatus.setText("Escaneo completo — " + totalFound + " dispositivo(s)");
                    txtDeviceCount.setText(String.valueOf(devices.size()));

                    // Premium: haptic feedback on scan complete
                    HapticUtil.performHeavy(btnScan);

                    // updateEmptyState() is already called inside setScanning()
                });
            }
        });
    }

    /**
     * Determina si source1 es una fuente "mejor" (más informativa) que source2.
     * Prioridad: mDNS > SSDP > NetBIOS > OUI DB > DNS > HTTP > Heurística
     */
    private boolean isBetterSource(String src1, String src2) {
        return sourceRank(src1) > sourceRank(src2);
    }

    private int sourceRank(String source) {
        if (source == null) return 0;
        switch (source) {
            case "mDNS":     return 7;
            case "SSDP":     return 6;
            case "NetBIOS":  return 5;
            case "OUI DB":   return 4;
            case "DNS":      return 3;
            case "HTTP":     return 2;
            default:         return 1;  // Heurística
        }
    }

    private void setScanning(boolean scanning) {
        btnScan.setEnabled(!scanning);
        progressScan.setVisibility(scanning ? View.VISIBLE : View.GONE);

        // Premium: FAB rotation animation
        if (scanning) {
            progressScan.setProgress(0);
            txtStatus.setText("Iniciando escaneo…");
            txtDeviceCount.setText("…");

            // Premium: show placeholders during scan with shimmer
            if (devices.isEmpty()) {
                layoutPlaceholders.setVisibility(View.VISIBLE);
                animatePlaceholders(layoutPlaceholders);
            }

            // Start FAB rotation
            if (fabRotationAnim != null && !fabRotationAnim.isRunning()) {
                fabRotationAnim.start();
            }

            // Start empty state pulse
            if (emptyPulseAnim != null && !emptyPulseAnim.isRunning()) {
                emptyPulseAnim.start();
            }
        } else {
            // Stop FAB rotation with smooth finish
            if (fabRotationAnim != null && fabRotationAnim.isRunning()) {
                fabRotationAnim.cancel();
            }
            btnScan.animate().rotation(0f).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            // Stop pulse
            if (emptyPulseAnim != null && emptyPulseAnim.isRunning()) {
                emptyPulseAnim.cancel();
            }

            // Hide placeholders
            layoutPlaceholders.setVisibility(View.GONE);
        }

        updateEmptyState();
    }

    // ── Device Detail view ──────────────────────────────────────

    private void showDeviceDetail(Device device) {
        if (showingDeviceDetail && currentDetailDevice == device) return;

        showingDeviceDetail = true;
        showingAbout = false;
        currentDetailDevice = device;

        // Animación de transición: slide desde la derecha
        ViewGroup root = findViewById(R.id.rootConstraint);
        TransitionSet set = new TransitionSet()
                .addTransition(new Slide(android.view.Gravity.END))
                .addTransition(new Fade())
                .setDuration(300);
        TransitionManager.beginDelayedTransition(root, set);

        layoutScannerContent.setVisibility(View.GONE);
        btnScan.setVisibility(View.GONE);
        layoutAbout.setVisibility(View.GONE);
        layoutDeviceDetail.setVisibility(View.VISIBLE);

        populateDeviceDetail(device);
    }

    private void populateDeviceDetail(Device d) {
        Context ctx = this;

        // Icon
        ImageView iconView = findViewById(R.id.detailIcon);
        int sizePx = (int) (32 * ctx.getResources().getDisplayMetrics().density);
        IconicsDrawable drawable = new IconicsDrawable(ctx, "cmd_laptop");
        drawable.setColorList(android.content.res.ColorStateList.valueOf(0xFF00E5FF));
        drawable.setSizeXPx(sizePx);
        drawable.setSizeYPx(sizePx);
        iconView.setImageDrawable(drawable);

        // Name
        TextView nameView = findViewById(R.id.detailName);
        nameView.setText(d.name);

        // Method badge
        TextView badgeView = findViewById(R.id.detailMethodBadge);
        if (d.discoveryMethod != null && !d.discoveryMethod.equals("Heurística")) {
            badgeView.setText("vía " + d.discoveryMethod);
            badgeView.setVisibility(View.VISIBLE);
        } else {
            badgeView.setVisibility(View.GONE);
        }

        // IP
        TextView ipView = findViewById(R.id.detailIp);
        ipView.setText(d.ip);

        // MAC
        View macRow = findViewById(R.id.detailMacRow);
        TextView macView = findViewById(R.id.detailMac);
        if (d.mac != null && !d.mac.equals("N/A")) {
            macView.setText(d.mac.toUpperCase());
            macRow.setVisibility(View.VISIBLE);
        } else {
            macRow.setVisibility(View.GONE);
        }

        // Vendor
        View vendorRow = findViewById(R.id.detailVendorRow);
        TextView vendorView = findViewById(R.id.detailVendor);
        if (d.vendor != null && !d.vendor.equals("Desconocido") && !d.vendor.equals(d.name)) {
            vendorView.setText(d.vendor);
            vendorRow.setVisibility(View.VISIBLE);
        } else {
            vendorRow.setVisibility(View.GONE);
        }

        // Method
        TextView methodView = findViewById(R.id.detailMethod);
        if (d.discoveryMethod != null) {
            methodView.setText(d.discoveryMethod);
        } else {
            methodView.setText("Heurística");
        }

        // Extra detail
        View extraRow = findViewById(R.id.detailExtraRow);
        TextView extraView = findViewById(R.id.detailExtra);
        if (d.discoveryDetail != null && !d.discoveryDetail.isEmpty()) {
            extraView.setText(d.discoveryDetail);
            extraRow.setVisibility(View.VISIBLE);
        } else {
            extraRow.setVisibility(View.GONE);
        }

        // Ports card
        View portsCard = findViewById(R.id.detailPortsCard);
        LinearLayout portsList = findViewById(R.id.detailPortsList);
        portsList.removeAllViews();

        if (!d.openPorts.isEmpty()) {
            portsCard.setVisibility(View.VISIBLE);
            for (int i = 0; i < d.openPorts.size(); i++) {
                int port = d.openPorts.get(i);
                String svc = NetworkScanner.serviceName(port);
                String label = svc != null ? port + " (" + svc + ")" : String.valueOf(port);

                TextView portView = new TextView(ctx);
                portView.setText("🔌  " + label);
                portView.setTextColor(0xFF00E5FF);
                portView.setTextSize(13);
                portView.setPadding(0, 0, 0, (i < d.openPorts.size() - 1) ? 6 : 0);
                portsList.addView(portView);
            }
        } else {
            portsCard.setVisibility(View.GONE);
        }

        // Button: Back
        findViewById(R.id.detailBtnBack).setOnClickListener(v -> {
            // En tablet: solo ocultar detail (list sigue visible); en phone: volver a scanner
            showScanner();
        });

        // Button: Copy IP
        findViewById(R.id.detailBtnCopyIp).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("IP", d.ip);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(ctx, d.ip + " copiado", Toast.LENGTH_SHORT).show();
            HapticUtil.performClick(v);
        });

        // Button: Copy MAC
        TextView btnCopyMac = findViewById(R.id.detailBtnCopyMac);
        if (d.mac != null && !d.mac.equals("N/A")) {
            btnCopyMac.setVisibility(View.VISIBLE);
            btnCopyMac.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("MAC", d.mac);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(ctx, d.mac + " copiado", Toast.LENGTH_SHORT).show();
                HapticUtil.performClick(v);
            });
        } else {
            btnCopyMac.setVisibility(View.GONE);
        }

        // Button: Open in browser
        findViewById(R.id.detailBtnOpenBrowser).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("http://" + d.ip)));
            } catch (Exception ignored) {
                Toast.makeText(ctx, "No se puede abrir el navegador", Toast.LENGTH_SHORT).show();
            }
        });

        // Button: Ping
        findViewById(R.id.detailBtnPing).setOnClickListener(v -> {
            pingDevice(d.ip);
        });
    }

    private void pingDevice(String ip) {
        Toast.makeText(this, "Haciendo ping a " + ip + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
                long t0 = System.currentTimeMillis();
                boolean reachable = addr.isReachable(2000);
                long rtt = System.currentTimeMillis() - t0;
                String msg = reachable
                        ? ip + " responde — " + rtt + " ms"
                        : ip + " no responde";
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Error haciendo ping", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ── Sort chips ──────────────────────────────────────────────

    private void setupSortChips() {
        View.OnClickListener sortListener = v -> {
            int id = v.getId();
            String criteria;
            if (id == R.id.chipSortIp) criteria = "ip";
            else if (id == R.id.chipSortName) criteria = "name";
            else if (id == R.id.chipSortVendor) criteria = "vendor";
            else if (id == R.id.chipSortMethod) criteria = "method";
            else return;

            currentSort = criteria;
            updateSortChipStyles();
            adapter.sortBy(criteria);
            HapticUtil.performClick(chipSortIp);
        };

        chipSortIp.setOnClickListener(sortListener);
        chipSortName.setOnClickListener(sortListener);
        chipSortVendor.setOnClickListener(sortListener);
        chipSortMethod.setOnClickListener(sortListener);

        updateSortChipStyles();
    }

    private void updateSortChipStyles() {
        int activeColor = 0xFF00E5FF;
        int inactiveColor = 0xFF8B949E;
        int activeBgRes = R.drawable.badge_empty_hint;

        View[] chips = {chipSortIp, chipSortName, chipSortVendor, chipSortMethod};
        String[] criteria = {"ip", "name", "vendor", "method"};

        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;
            TextView chip = (TextView) chips[i];
            boolean active = currentSort.equals(criteria[i]);
            chip.setTextColor(active ? activeColor : inactiveColor);
            chip.setBackgroundResource(active ? activeBgRes : 0);
        }
    }

    private void hideKeyboard() {
        View view = getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        setupSearchView(menu);
        return true;
    }

    private void updateEmptyState() {
        boolean scanning = !btnScan.isEnabled();

        if (devices.isEmpty() && !scanning) {
            // Show empty state
            layoutEmpty.setVisibility(View.VISIBLE);
            layoutEmpty.setAlpha(0f);
            layoutEmpty.animate().alpha(1f).setDuration(300).start();

            if (hasScannedBefore) {
                // Scan completed with 0 results
                txtEmptyHint.setVisibility(View.VISIBLE);
            } else {
                // Initial state — never scanned
                txtEmptyHint.setVisibility(View.GONE);
            }
        } else if (layoutEmpty.getVisibility() == View.VISIBLE) {
            // Fade out and hide empty state (has devices or scanning)
            layoutEmpty.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> layoutEmpty.setVisibility(View.GONE))
                    .start();
        }
        // If already GONE, no action needed
    }

    // ── Premium: Empty State Pulse Animation ────────────────────────

    private void setupEmptyPulse() {
        View radarBg = findViewById(R.id.imgEmptyRadarBg);
        if (radarBg == null) return;

        emptyPulseAnim = ObjectAnimator.ofFloat(radarBg, "alpha", 0.3f, 0.7f);
        emptyPulseAnim.setDuration(1500);
        emptyPulseAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        emptyPulseAnim.setRepeatCount(ObjectAnimator.INFINITE);
        emptyPulseAnim.setRepeatMode(ObjectAnimator.REVERSE);
    }

    // ── Premium: Placeholder Shimmer ──────────────────────────────

    private void animatePlaceholders(View layoutPlaceholders) {
        if (!(layoutPlaceholders instanceof ViewGroup)) return;
        ViewGroup container = (ViewGroup) layoutPlaceholders;
        for (int i = 0; i < container.getChildCount(); i++) {
            View card = container.getChildAt(i);
            // Pulsar alpha suavemente en cada card placeholder
            card.setAlpha(0.4f);
            card.animate()
                    .alpha(0.7f)
                    .setDuration(800 + (i * 150))
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> card.animate()
                            .alpha(0.4f)
                            .setDuration(800)
                            .setInterpolator(new AccelerateDecelerateInterpolator())
                            .start())
                    .start();
        }
    }

    // ── Premium: FAB Rotation Animation ────────────────────────────

    private void setupFabRotation() {
        fabRotationAnim = ObjectAnimator.ofFloat(btnScan, "rotation", 0f, 360f);
        fabRotationAnim.setDuration(3000);
        fabRotationAnim.setInterpolator(new LinearInterpolator());
        fabRotationAnim.setRepeatCount(ObjectAnimator.INFINITE);
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(navView)) {
            drawerLayout.closeDrawer(navView);
        } else if (showingDeviceDetail || showingAbout) {
            showScanner();
        } else {
            super.onBackPressed();
        }
    }
}
