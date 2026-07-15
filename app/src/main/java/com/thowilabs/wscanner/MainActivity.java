package com.thowilabs.wscanner;

import android.animation.ObjectAnimator;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;

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
    private SwipeRefreshLayout swipeRefresh;

    // Premium: empty state & placeholders
    private View layoutEmpty;
    private View layoutPlaceholders;
    private TextView txtEmptyHint;
    private boolean hasScannedBefore = false;

    // Acerca de view (embedded, no separate activity)
    private View layoutAbout;
    private View layoutScannerContent;
    private boolean showingAbout = false;

    // Premium: FAB rotation animation
    private ObjectAnimator fabRotationAnim;

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
        swipeRefresh = findViewById(R.id.swipeRefresh);

        // Premium: empty state & placeholders
        layoutEmpty = findViewById(R.id.layoutEmpty);
        layoutPlaceholders = findViewById(R.id.layoutPlaceholders);
        txtEmptyHint = findViewById(R.id.txtEmptyHint);

        // Acerca de view
        layoutAbout = findViewById(R.id.layoutAbout);
        layoutScannerContent = findViewById(R.id.layoutScannerContent);

        // RecyclerView
        RecyclerView rv = findViewById(R.id.listDevices);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DeviceAdapter(devices);
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

        // Swipe to refresh
        swipeRefresh.setOnRefreshListener(this::startScan);
        swipeRefresh.setColorSchemeColors(0xFF00E5FF, 0xFF00B8D4, 0xFF3FB950);

        // Premium: setup FAB rotation animation
        setupFabRotation();
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

    private void showScanner() {
        if (!showingAbout) return;
        showingAbout = false;
        layoutScannerContent.setVisibility(View.VISIBLE);
        btnScan.setVisibility(View.VISIBLE);
        layoutAbout.setVisibility(View.GONE);
        navView.setCheckedItem(R.id.nav_scanner);
    }

    private void showAbout() {
        if (showingAbout) return;
        showingAbout = true;
        layoutScannerContent.setVisibility(View.GONE);
        btnScan.setVisibility(View.GONE);
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
                        // Actualizar dispositivo existente si hay mejor información
                        Device existing = devices.get(existingIdx);
                        // Solo reemplazar si la nueva info es de una fuente mejor
                        // (mDNS/SSDP > DNS > Heurística)
                        if (isBetterSource(device.discoveryMethod, existing.discoveryMethod)) {
                            devices.set(existingIdx, device);
                            adapter.notifyItemChanged(existingIdx);
                            Log.d(TAG, "🔄 Actualizado: " + device.ip
                                    + " → \"" + device.name + "\" (" + device.discoveryMethod + ")");
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
        swipeRefresh.setRefreshing(scanning);

        // Premium: FAB rotation animation
        if (scanning) {
            progressScan.setProgress(0);
            txtStatus.setText("Iniciando escaneo…");
            txtDeviceCount.setText("…");

            // Premium: show placeholders during scan
            if (devices.isEmpty()) {
                layoutPlaceholders.setVisibility(View.VISIBLE);
            }

            // Start FAB rotation
            if (fabRotationAnim != null && !fabRotationAnim.isRunning()) {
                fabRotationAnim.start();
            }
        } else {
            // Stop FAB rotation with smooth finish
            if (fabRotationAnim != null && fabRotationAnim.isRunning()) {
                fabRotationAnim.cancel();
            }
            btnScan.animate().rotation(0f).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            // Hide placeholders
            layoutPlaceholders.setVisibility(View.GONE);
        }

        updateEmptyState();
    }

    // ── Premium: Empty State ───────────────────────────────────────

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
        } else if (showingAbout) {
            showScanner();
        } else {
            super.onBackPressed();
        }
    }
}
