package com.thowilabs.wscanner;

import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    // Acerca de view
    private View layoutAbout;
    private View layoutScannerContent;
    private boolean showingAbout = false;

    // Device detail view
    private View layoutDeviceDetail;
    private boolean showingDeviceDetail = false;
    private Device currentDetailDevice;

    // Speed Test view
    private View layoutSpeedTest;
    private boolean showingSpeedTest = false;
    private SpeedTestTool speedTestTool;

    // Device labels
    private SharedPreferences labelPrefs;

    // Premium: FAB rotation animation
    private ObjectAnimator fabRotationAnim;

    // Pulse animation for empty state radar
    private ObjectAnimator emptyPulseAnim;

    // Active scan mode (monitoreo continuo)
    private NetworkScanner networkScanner;
    private boolean activeScanMode = false;
    private int activeScanCycle = 0;
    private Handler activeScanHandler;

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

        // Speed Test view
        layoutSpeedTest = findViewById(R.id.layoutSpeedTest);
        speedTestTool = new SpeedTestTool();

        // Device labels
        labelPrefs = getSharedPreferences("wscanner_labels", MODE_PRIVATE);

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
            if (activeScanMode) {
                stopActiveScan();
            } else {
                startScan();
            }
        });

        // FAB long-press to toggle active scan (monitor mode)
        btnScan.setOnLongClickListener(v -> {
            HapticUtil.performHeavy(btnScan);
            toggleActiveScan();
            Toast.makeText(this,
                    activeScanMode ? "Modo monitor ACTIVADO" : "Modo monitor DESACTIVADO",
                    Toast.LENGTH_SHORT).show();
            return true;
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

        // Premium: setup FAB rotation animation
        setupFabRotation();

        // Premium: setup empty state pulse animation
        setupEmptyPulse();

        // Active scan infrastructure
        networkScanner = new NetworkScanner();
        activeScanHandler = new Handler(Looper.getMainLooper());
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
        } else if (id == R.id.nav_speedtest) {
            showSpeedTest();
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
        if (showingAbout || showingDeviceDetail || showingSpeedTest) {
            hideAllViews();

            ViewGroup root = findViewById(R.id.rootConstraint);
            TransitionManager.beginDelayedTransition(root,
                    new AutoTransition());

            layoutScannerContent.setVisibility(View.VISIBLE);
            btnScan.setVisibility(View.VISIBLE);

            navView.setCheckedItem(R.id.nav_scanner);
            invalidateOptionsMenu();
        }
    }

    private void hideAllViews() {
        showingAbout = false;
        showingDeviceDetail = false;
        showingSpeedTest = false;
        currentDetailDevice = null;

        layoutAbout.setVisibility(View.GONE);
        layoutDeviceDetail.setVisibility(View.GONE);
        layoutSpeedTest.setVisibility(View.GONE);
    }

    private void showAbout() {
        if (showingAbout) return;
        hideAllViews();
        showingAbout = true;

        ViewGroup root = findViewById(R.id.rootConstraint);
        TransitionSet set = new TransitionSet()
                .addTransition(new Slide(android.view.Gravity.END))
                .addTransition(new Fade())
                .setDuration(350);
        TransitionManager.beginDelayedTransition(root, set);

        layoutScannerContent.setVisibility(View.GONE);
        btnScan.setVisibility(View.GONE);
        layoutAbout.setVisibility(View.VISIBLE);
        navView.setCheckedItem(R.id.nav_about);
        invalidateOptionsMenu();
    }

    // ── Escaneo ────────────────────────────────────────────────────

    private void startScan() {
        if (activeScanCycle == 0) {
            Log.i(TAG, "🧹 Limpiando lista previa (" + devices.size() + " dispositivos)");
            hasScannedBefore = true;
            devices.clear();
            ipIndex.clear();
            adapter.notifyDataSetChanged();
        } else {
            Log.i(TAG, "🔄 Ciclo " + activeScanCycle + " — " + devices.size() + " dispositivos en lista");
            markAllPending();
        }

        updateNetworkInfo();
        setScanning(true);
        Log.i(TAG, "🚀 Iniciando NetworkScanner...");

        networkScanner.scan(this, new NetworkScanner.Callback() {
            @Override
            public void onDeviceFound(Device device) {
                runOnUiThread(() -> {
                    Integer existingIdx = ipIndex.get(device.ip);

                    if (existingIdx != null) {
                        Device existing = devices.get(existingIdx);
                        existing.online = true;
                        existing.lastSeen = System.currentTimeMillis();

                        if (!device.openPorts.isEmpty()) {
                            existing.openPorts = device.openPorts;
                            existing.serviceNames = device.serviceNames;
                        }
                        if (device.mac != null && !device.mac.equals("N/A")
                                && (existing.mac == null || existing.mac.equals("N/A"))) {
                            existing.mac = device.mac;
                            existing.vendor = device.vendor;
                        }

                        if (isBetterSource(device.discoveryMethod, existing.discoveryMethod)) {
                            existing.name = device.name;
                            existing.vendor = device.vendor;
                            existing.mac = device.mac;
                            existing.discoveryMethod = device.discoveryMethod;
                            existing.discoveryDetail = device.discoveryDetail;
                        }

                        adapter.notifyItemChanged(existingIdx);
                    } else {
                        device.online = true;
                        device.lastSeen = System.currentTimeMillis();
                        String label = labelPrefs.getString(device.ip, null);
                        if (label != null && !label.isEmpty()) device.userLabel = label;

                        devices.add(device);
                        int idx = devices.size() - 1;
                        ipIndex.put(device.ip, idx);
                        adapter.notifyItemInserted(idx);
                        txtDeviceCount.setText(String.valueOf(devices.size()));
                    }

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

                    HapticUtil.performHeavy(btnScan);

                    if (activeScanMode) {
                        activeScanHandler.postDelayed(() -> {
                            if (activeScanMode) {
                                activeScanCycle++;
                                startScan();
                            }
                        }, 5000);
                        updateMonitorChipStyle();
                    }
                });
            }
        });
    }

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
            default:         return 1;
        }
    }

    // ── Active Scan Mode ─────────────────────────────────────────

    private void toggleActiveScan() {
        if (activeScanMode) {
            stopActiveScan();
        } else {
            activeScanMode = true;
            activeScanCycle = 0;
            updateMonitorChipStyle();
            startScan();
        }
    }

    private void stopActiveScan() {
        activeScanMode = false;
        activeScanCycle = 0;
        activeScanHandler.removeCallbacks(null);
        for (Device d : devices) {
            d.online = true;
        }
        adapter.notifyDataSetChanged();
        updateMonitorChipStyle();
    }

    private void markAllPending() {
        for (Device d : devices) {
            d.online = false;
        }
        adapter.notifyDataSetChanged();
    }

    private void updateMonitorChipStyle() {
        if (activeScanMode) {
            btnScan.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF85149));
        } else {
            btnScan.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF00E5FF));
        }
    }

    private void setScanning(boolean scanning) {
        btnScan.setEnabled(!scanning);
        progressScan.setVisibility(scanning ? View.VISIBLE : View.GONE);

        if (scanning) {
            progressScan.setProgress(0);
            txtStatus.setText("Iniciando escaneo…");
            txtDeviceCount.setText("…");

            if (devices.isEmpty()) {
                layoutPlaceholders.setVisibility(View.VISIBLE);
                animatePlaceholders(layoutPlaceholders);
            }

            if (fabRotationAnim != null && !fabRotationAnim.isRunning()) {
                fabRotationAnim.start();
            }

            if (emptyPulseAnim != null && !emptyPulseAnim.isRunning()) {
                emptyPulseAnim.start();
            }
        } else {
            if (fabRotationAnim != null && fabRotationAnim.isRunning()) {
                fabRotationAnim.cancel();
            }
            btnScan.animate().rotation(0f).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            if (emptyPulseAnim != null && emptyPulseAnim.isRunning()) {
                emptyPulseAnim.cancel();
            }

            layoutPlaceholders.setVisibility(View.GONE);
        }

        updateEmptyState();
    }

    // ── Device Detail view ──────────────────────────────────────

    private void showDeviceDetail(Device device) {
        if (showingDeviceDetail && currentDetailDevice == device) return;

        hideAllViews();
        showingDeviceDetail = true;
        currentDetailDevice = device;

        ViewGroup root = findViewById(R.id.rootConstraint);
        TransitionSet set = new TransitionSet()
                .addTransition(new Slide(android.view.Gravity.END))
                .addTransition(new Fade())
                .setDuration(300);
        TransitionManager.beginDelayedTransition(root, set);

        layoutScannerContent.setVisibility(View.GONE);
        btnScan.setVisibility(View.GONE);
        layoutDeviceDetail.setVisibility(View.VISIBLE);
        invalidateOptionsMenu();

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

        // Name (show userLabel if set)
        TextView nameView = findViewById(R.id.detailName);
        nameView.setText(d.userLabel != null ? d.userLabel : d.name);

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
        findViewById(R.id.detailBtnBack).setOnClickListener(v -> showScanner());

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
        findViewById(R.id.detailBtnPing).setOnClickListener(v -> pingDevice(d.ip));
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

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem searchItem = menu.findItem(R.id.action_search);
        if (searchItem != null) {
            boolean onScanner = !showingAbout && !showingSpeedTest && !showingDeviceDetail;
            searchItem.setVisible(onScanner);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_export) {
            exportResults();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void exportResults() {
        if (devices.isEmpty()) {
            Toast.makeText(this, "No hay dispositivos para exportar", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("WScanner — Resultados del escaneo\n");
        sb.append("====================================\n\n");
        for (Device d : devices) {
            sb.append(d.name).append("\n");
            sb.append("  IP: ").append(d.ip).append("\n");
            if (d.mac != null && !d.mac.equals("N/A"))
                sb.append("  MAC: ").append(d.mac.toUpperCase()).append("\n");
            if (d.vendor != null && !d.vendor.equals("Desconocido"))
                sb.append("  Fabricante: ").append(d.vendor).append("\n");
            sb.append("  Método: ").append(d.discoveryMethod).append("\n");
            if (!d.openPorts.isEmpty())
                sb.append("  Puertos: ").append(d.openPorts.toString()).append("\n");
            sb.append("\n");
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(intent, "Exportar resultados"));
    }

    private void updateEmptyState() {
        boolean scanning = !btnScan.isEnabled();

        if (devices.isEmpty() && !scanning) {
            layoutEmpty.setVisibility(View.VISIBLE);
            layoutEmpty.setAlpha(0f);
            layoutEmpty.animate().alpha(1f).setDuration(300).start();

            if (hasScannedBefore) {
                txtEmptyHint.setVisibility(View.VISIBLE);
            } else {
                txtEmptyHint.setVisibility(View.GONE);
            }
        } else if (layoutEmpty.getVisibility() == View.VISIBLE) {
            layoutEmpty.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> layoutEmpty.setVisibility(View.GONE))
                    .start();
        }
    }

    // ── Empty State Pulse Animation ─────────────────────────────

    private void setupEmptyPulse() {
        View radarBg = findViewById(R.id.imgEmptyRadarBg);
        if (radarBg == null) return;

        emptyPulseAnim = ObjectAnimator.ofFloat(radarBg, "alpha", 0.3f, 0.7f);
        emptyPulseAnim.setDuration(1500);
        emptyPulseAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        emptyPulseAnim.setRepeatCount(ObjectAnimator.INFINITE);
        emptyPulseAnim.setRepeatMode(ObjectAnimator.REVERSE);
    }

    // ── Placeholder Shimmer ──────────────────────────────────────

    private void animatePlaceholders(View layoutPlaceholders) {
        if (!(layoutPlaceholders instanceof ViewGroup)) return;
        ViewGroup container = (ViewGroup) layoutPlaceholders;
        for (int i = 0; i < container.getChildCount(); i++) {
            View card = container.getChildAt(i);
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

    // ── FAB Rotation Animation ──────────────────────────────────

    private void setupFabRotation() {
        fabRotationAnim = ObjectAnimator.ofFloat(btnScan, "rotation", 0f, 360f);
        fabRotationAnim.setDuration(3000);
        fabRotationAnim.setInterpolator(new LinearInterpolator());
        fabRotationAnim.setRepeatCount(ObjectAnimator.INFINITE);
    }

    // ── Speed Test ───────────────────────────────────────────────

    private void showSpeedTest() {
        if (showingSpeedTest) return;
        hideAllViews();
        showingSpeedTest = true;

        ViewGroup root = findViewById(R.id.rootConstraint);
        TransitionSet set = new TransitionSet()
                .addTransition(new Slide(android.view.Gravity.END))
                .addTransition(new Fade())
                .setDuration(350);
        TransitionManager.beginDelayedTransition(root, set);

        layoutScannerContent.setVisibility(View.GONE);
        btnScan.setVisibility(View.GONE);
        layoutSpeedTest.setVisibility(View.VISIBLE);
        navView.setCheckedItem(R.id.nav_speedtest);
        invalidateOptionsMenu();

        setupSpeedTestView();
    }

    private void setupSpeedTestView() {
        View btnStart = layoutSpeedTest.findViewById(R.id.btnStartSpeedtest);
        ProgressBar progress = layoutSpeedTest.findViewById(R.id.speedtestProgress);
        TextView txtPhase = layoutSpeedTest.findViewById(R.id.txtSpeedtestPhase);
        View results = layoutSpeedTest.findViewById(R.id.speedtestResults);
        SpeedometerGauge gauge = layoutSpeedTest.findViewById(R.id.gaugeDownload);
        TextView txtPing = layoutSpeedTest.findViewById(R.id.txtPingResult);
        TextView txtJitter = layoutSpeedTest.findViewById(R.id.txtJitterResult);
        View btnShare = layoutSpeedTest.findViewById(R.id.btnShareSpeedtest);

        // Track final speed for share
        final double[] finalDown = {0};

        // Reset UI state
        progress.setVisibility(View.GONE);
        txtPhase.setVisibility(View.GONE);
        results.setVisibility(View.GONE);
        btnStart.setVisibility(View.VISIBLE);
        gauge.setSpeed(0);

        btnStart.setOnClickListener(v -> {
            HapticUtil.performConfirm(v);

            btnStart.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
            txtPhase.setVisibility(View.VISIBLE);
            results.setVisibility(View.VISIBLE);
            btnShare.setVisibility(View.GONE);
            gauge.setSpeed(0);

            speedTestTool.runTest(new SpeedTestTool.Callback() {
                @Override public void onPhase(String phase) {
                    runOnUiThread(() -> txtPhase.setText(phase));
                }
                @Override public void onProgress(int percent) {
                    runOnUiThread(() -> progress.setProgress(percent));
                }
                @Override public void onPingResult(double pingMs, double jitterMs) {
                    runOnUiThread(() -> {
                        txtPing.setText(pingMs > 0 ? String.format("%.0f", pingMs) : "—");
                        txtJitter.setText(jitterMs > 0 ? String.format("%.1f", jitterMs) : "—");
                    });
                }
                @Override public void onDownloadSpeedUpdate(double currentMbps) {
                    runOnUiThread(() -> gauge.setSpeed(currentMbps));
                }
                @Override public void onDownloadResult(double mbps) {
                    finalDown[0] = mbps;
                }
                @Override public void onUploadResult(double mbps) {}
                @Override public void onFinished(double pingMs, double jitterMs, double downMbps, double upMbps) {
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        txtPhase.setVisibility(View.GONE);
                        btnStart.setVisibility(View.VISIBLE);
                        btnShare.setVisibility(View.VISIBLE);
                        txtPing.setText(pingMs > 0 ? String.format("%.0f", pingMs) : "—");
                        txtJitter.setText(jitterMs > 0 ? String.format("%.1f", jitterMs) : "—");
                        HapticUtil.performHeavy(btnStart);
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        txtPhase.setText(message);
                        txtPhase.setVisibility(View.VISIBLE);
                        btnStart.setVisibility(View.VISIBLE);
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        btnShare.setOnClickListener(v -> {
            String text = String.format(
                    "WScanner Speed Test\nDescarga: %.1f Mbps\nPing: %s ms\nJitter: %s ms",
                    finalDown[0], txtPing.getText(), txtJitter.getText());
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(intent, "Compartir resultado"));
        });
    }

    // ── Back & Destroy ───────────────────────────────────────────

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(navView)) {
            drawerLayout.closeDrawer(navView);
        } else if (showingDeviceDetail || showingAbout || showingSpeedTest) {
            showScanner();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (activeScanHandler != null) {
            activeScanHandler.removeCallbacksAndMessages(null);
        }
        if (fabRotationAnim != null && fabRotationAnim.isRunning()) {
            fabRotationAnim.cancel();
        }
        if (emptyPulseAnim != null && emptyPulseAnim.isRunning()) {
            emptyPulseAnim.cancel();
        }
        super.onDestroy();
    }
}
