package com.thowilabs.wscanner;

import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
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

    // Scan state
    private boolean isScanning = false;

    // Active scan mode (monitoreo continuo)
    private NetworkScanner networkScanner;
    private boolean activeScanMode = false;
    private Handler activeScanHandler;
    private final ScanCycleState scanCycleState = new ScanCycleState();
    private String inventoryCidr = null;

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

        // El drawer debe comenzar debajo de la barra de estado. NavigationView
        // normalmente puede dibujar su header detrás de ella; aplicamos el inset
        // superior como padding para mantener el banner completamente visible.
        navView.setOnApplyWindowInsetsListener((view, insets) -> {
            int statusBarInset = insets.getSystemWindowInsetTop();
            view.setPadding(
                    view.getPaddingLeft(),
                    statusBarInset,
                    view.getPaddingRight(),
                    view.getPaddingBottom());
            return insets;
        });
        navView.requestApplyInsets();

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
        // El monitor actualiza una misma fila varias veces en pocos milisegundos
        // (TCP, mDNS, SSDP, etc.). Las animaciones predictivas y de cambio del
        // RecyclerView pueden intentar reusar/adjuntar el mismo ViewHolder mientras
        // otro layout sigue pendiente, provocando "child which is not detached".
        // La lista ya anima el estado visual dentro del adapter, por lo que estas
        // animaciones internas no aportan nada y se desactivan de forma segura.
        LinearLayoutManager deviceLayoutManager = new LinearLayoutManager(this) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        };
        rv.setLayoutManager(deviceLayoutManager);
        rv.setItemAnimator(null);
        adapter = new DeviceAdapter(devices);
        adapter.setOnDeviceClickListener(this::showDeviceDetail);
        rv.setAdapter(adapter);

        // Motor de escaneo y monitor continuo
        networkScanner = new NetworkScanner();
        activeScanHandler = new Handler(Looper.getMainLooper());

        // Mostrar info de red actual usando el mismo rango real que escanea el motor
        updateNetworkInfo();

        // FAB click: stop if scanning, start if idle
        btnScan.setOnClickListener(v -> {
            HapticUtil.performConfirm(btnScan);
            if (isScanning) {
                if (activeScanMode) stopActiveScan();
                networkScanner.cancel();
                setScanning(false);
            } else {
                startScan();
            }
        });

        // FAB long-press to toggle active scan (monitor mode)
        btnScan.setOnLongClickListener(v -> {
            if (isScanning) return true;  // already scanning, ignore long-press
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

    }

    // ── Info de red ────────────────────────────────────────────────

    private void updateNetworkInfo() {
        try {
            NetworkScanner.NetworkInfo info = networkScanner.getNetworkInfo(this);
            if (info != null) {
                txtSubnetValue.setText(info.cidr);
                txtGatewayValue.setText(info.gateway != null ? info.gateway : "—");

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
        } catch (Exception e) {
            Log.w(TAG, "No se pudo leer info de red: " + e.getMessage());
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
        // Al salir del Speed Test invalidamos la ejecución activa. Esto evita que
        // un resultado tardío vuelva a tocar una vista que ya está oculta.
        if (showingSpeedTest && speedTestTool != null) {
            speedTestTool.cancel();
        }

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
        if (isScanning) return;
        // El Handler se usa exclusivamente para programar el siguiente ciclo del
        // monitor. Si el usuario inicia uno manualmente durante la pausa, cancelar
        // el callback pendiente evita dos escaneos solapados sobre el mismo inventario.
        if (activeScanMode) activeScanHandler.removeCallbacksAndMessages(null);

        NetworkScanner.NetworkInfo networkInfo = networkScanner.getNetworkInfo(this);
        String nextCidr = networkInfo != null ? networkInfo.cidr : null;
        boolean networkChanged = inventoryCidr != null && nextCidr != null
                && !inventoryCidr.equals(nextCidr);

        if (networkChanged) {
            Log.i(TAG, "🌐 Cambio de red " + inventoryCidr + " → " + nextCidr
                    + "; se reinicia el inventario para no mezclar subredes");
            devices.clear();
            ipIndex.clear();
            adapter.notifyDataSetChanged();
        } else if (!devices.isEmpty()) {
            Log.i(TAG, "🔄 Nuevo ciclo — " + devices.size()
                    + " dispositivos conservan su último estado hasta finalizar el barrido");
        }
        if (nextCidr != null) inventoryCidr = nextCidr;
        hasScannedBefore = true;

        // No se marca ningún equipo offline al comenzar. El ciclo registra lo visto
        // y solo al finalizar degrada a gris los dispositivos realmente ausentes.
        scanCycleState.beginCycle();

        updateNetworkInfo();
        setScanning(true);
        Log.i(TAG, "🚀 Iniciando NetworkScanner...");

        networkScanner.scan(this, new NetworkScanner.Callback() {
            @Override
            public void onDeviceFound(Device device) {
                runOnUiThread(() -> {
                    scanCycleState.markSeen(device.ip);
                    Integer existingIdx = ipIndex.get(device.ip);

                    if (existingIdx != null) {
                        Device existing = devices.get(existingIdx);
                        device.online = true;
                        device.lastSeen = System.currentTimeMillis();
                        DeviceIdentity.mergeInto(existing, device);
                        existing.online = true;
                        existing.lastSeen = System.currentTimeMillis();

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
                    // La ausencia se decide únicamente cuando termina el ciclo completo.
                    // Esto evita falsos "offline" mientras el nuevo barrido todavía avanza.
                    int onlineNow = scanCycleState.finishCycle(devices);
                    adapter.notifyDataSetChanged();

                    if (isScanning) {  // don't overwrite UI if user already stopped
                        setScanning(false);
                        if (activeScanMode) {
                            txtStatus.setText("Monitor — " + onlineNow + " activos · "
                                    + devices.size() + " conocidos");
                        } else {
                            txtStatus.setText("Escaneo completo — " + onlineNow + " activos · "
                                    + devices.size() + " conocidos");
                        }
                        txtDeviceCount.setText(String.valueOf(devices.size()));
                        HapticUtil.performHeavy(btnScan);
                    }

                    if (activeScanMode) {
                        activeScanHandler.postDelayed(() -> {
                            if (activeScanMode) {
                                startScan();
                            }
                        }, 5000);
                        updateMonitorChipStyle();
                    }
                });
            }
        });
    }

    // ── Active Scan Mode ─────────────────────────────────────────

    private void toggleActiveScan() {
        if (activeScanMode) {
            stopActiveScan();
        } else {
            activeScanMode = true;
            updateMonitorChipStyle();
            startScan();
        }
    }

    private void stopActiveScan() {
        activeScanMode = false;
        activeScanHandler.removeCallbacksAndMessages(null);
        // Conservar el último estado verificado. Detener el monitor no debe revivir
        // artificialmente dispositivos que ya habían quedado offline.
        adapter.notifyDataSetChanged();
        updateMonitorChipStyle();
    }

    private void updateMonitorChipStyle() {
        if (activeScanMode) {
            btnScan.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF85149));
        } else {
            btnScan.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF00E5FF));
        }
    }

    private void setScanning(boolean scanning) {
        isScanning = scanning;
        progressScan.setVisibility(scanning ? View.VISIBLE : View.GONE);

        if (scanning) {
            // Transform FAB into red stop button
            btnScan.setImageResource(R.drawable.ic_stop);
            btnScan.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF85149));

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
            // Restore normal radar icon + appropriate tint
            btnScan.setImageResource(R.drawable.ic_radar);
            updateMonitorChipStyle();

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

        // Fabricante / modelo / vendor disponibles por metadatos locales
        View vendorRow = findViewById(R.id.detailVendorRow);
        TextView vendorView = findViewById(R.id.detailVendor);
        StringBuilder identityMeta = new StringBuilder();
        if (d.manufacturer != null && !d.manufacturer.isEmpty()) identityMeta.append(d.manufacturer);
        if (d.model != null && !d.model.isEmpty()) {
            if (identityMeta.length() > 0) identityMeta.append(" · ");
            identityMeta.append(d.model);
        }
        if (identityMeta.length() == 0 && d.vendor != null
                && !d.vendor.equals("Desconocido") && !d.vendor.equals(d.name)) {
            identityMeta.append(d.vendor);
        }
        if (identityMeta.length() > 0) {
            vendorView.setText(identityMeta.toString());
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
        StringBuilder extra = new StringBuilder();
        if (d.deviceType != null && !d.deviceType.isEmpty()) extra.append("Tipo: ").append(d.deviceType);
        if (d.osHint != null && !d.osHint.isEmpty()) {
            if (extra.length() > 0) extra.append(" · ");
            extra.append("SO/servicio: ").append(d.osHint);
        }
        if (d.discoveryDetail != null && !d.discoveryDetail.isEmpty()) {
            if (extra.length() > 0) extra.append("\n");
            extra.append(d.discoveryDetail);
        }
        if (extra.length() > 0) {
            extraView.setText(extra.toString());
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
        boolean scanning = isScanning;

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
        View primaryScreen = layoutSpeedTest.findViewById(R.id.speedtestPrimaryScreen);
        View realScreen = layoutSpeedTest.findViewById(R.id.speedtestRealScreen);
        View btnStart = layoutSpeedTest.findViewById(R.id.btnStartSpeedtest);
        ProgressBar progress = layoutSpeedTest.findViewById(R.id.speedtestProgress);
        TextView txtPhase = layoutSpeedTest.findViewById(R.id.txtSpeedtestPhase);
        View preflight = layoutSpeedTest.findViewById(R.id.speedtestPreflight);
        TextView txtPreflightPhase = layoutSpeedTest.findViewById(R.id.txtSpeedtestPreflightPhase);
        View results = layoutSpeedTest.findViewById(R.id.speedtestResults);
        SpeedometerGauge gauge = layoutSpeedTest.findViewById(R.id.gaugeDownload);
        TextView txtUpload = layoutSpeedTest.findViewById(R.id.txtUploadSpeed);
        TextView txtPing = layoutSpeedTest.findViewById(R.id.txtPingResult);
        TextView txtJitter = layoutSpeedTest.findViewById(R.id.txtJitterResult);

        ProgressBar realProgress = layoutSpeedTest.findViewById(R.id.speedtestRealProgress);
        TextView txtRealPhase = layoutSpeedTest.findViewById(R.id.txtRealDownloadPhase);
        SpeedometerGauge realGauge = layoutSpeedTest.findViewById(R.id.gaugeRealDownload);
        TextView txtRealSpeed = layoutSpeedTest.findViewById(R.id.txtRealDownloadSpeed);
        TextView txtNormalDownSummary = layoutSpeedTest.findViewById(R.id.txtNormalDownloadSummary);
        TextView txtNormalUpSummary = layoutSpeedTest.findViewById(R.id.txtNormalUploadSummary);
        View btnRestart = layoutSpeedTest.findViewById(R.id.btnRestartSpeedtest);
        View btnShare = layoutSpeedTest.findViewById(R.id.btnShareSpeedtest);

        final double[] finalPing = {0};
        final double[] finalJitter = {0};
        final double[] finalDown = {0};
        final double[] finalUp = {0};
        final double[] finalRealDown = {0};

        Runnable resetUi = () -> {
            primaryScreen.setVisibility(View.VISIBLE);
            realScreen.setVisibility(View.GONE);
            btnStart.setVisibility(View.VISIBLE);
            progress.setVisibility(View.GONE);
            progress.setProgress(0);
            txtPhase.setVisibility(View.GONE);
            preflight.setVisibility(View.GONE);
            results.setVisibility(View.GONE);
            gauge.setSpeed(0);
            txtUpload.setText("—");
            txtPing.setText("—");
            txtJitter.setText("—");

            realProgress.setVisibility(View.VISIBLE);
            txtRealPhase.setText(R.string.speedtest_real_starting);
            realGauge.setSpeed(0);
            txtRealSpeed.setText("—");
            txtNormalDownSummary.setText("—");
            txtNormalUpSummary.setText("—");
            btnRestart.setVisibility(View.GONE);
            btnShare.setVisibility(View.GONE);

            finalPing[0] = 0;
            finalJitter[0] = 0;
            finalDown[0] = 0;
            finalUp[0] = 0;
            finalRealDown[0] = 0;
        };

        final Runnable[] startTest = new Runnable[1];
        startTest[0] = () -> {
            HapticUtil.performConfirm(btnStart);
            speedTestTool.cancel();

            primaryScreen.setVisibility(View.VISIBLE);
            realScreen.setVisibility(View.GONE);
            btnStart.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(0);
            txtPhase.setVisibility(View.VISIBLE);
            txtPhase.setText("Preparando test de velocidad…");
            txtPreflightPhase.setText("Preparando conexión…");
            preflight.setVisibility(View.VISIBLE);
            results.setVisibility(View.GONE);
            gauge.setSpeed(0);
            txtUpload.setText("—");
            txtPing.setText("—");
            txtJitter.setText("—");
            realGauge.setSpeed(0);
            txtRealSpeed.setText("—");
            btnRestart.setVisibility(View.GONE);
            btnShare.setVisibility(View.GONE);

            speedTestTool.runTest(new SpeedTestTool.Callback() {
                @Override public void onPhase(String phase) {
                    runOnUiThread(() -> {
                        txtPhase.setText(phase);
                        if (preflight.getVisibility() == View.VISIBLE) {
                            txtPreflightPhase.setText(phase);
                        }
                    });
                }

                @Override public void onLatencyStage() {
                    runOnUiThread(() -> {
                        if (primaryScreen.getVisibility() != View.VISIBLE) return;
                        ViewGroup primaryContainer = (ViewGroup) primaryScreen;
                        TransitionSet transition = new TransitionSet()
                                .addTransition(new Fade())
                                .setDuration(180);
                        TransitionManager.beginDelayedTransition(primaryContainer, transition);
                        results.setVisibility(View.GONE);
                        preflight.setVisibility(View.VISIBLE);
                        gauge.setSpeed(0);
                    });
                }

                @Override public void onTransferStage() {
                    runOnUiThread(() -> {
                        if (primaryScreen.getVisibility() != View.VISIBLE) return;
                        ViewGroup primaryContainer = (ViewGroup) primaryScreen;
                        TransitionSet transition = new TransitionSet()
                                .addTransition(new Fade())
                                .addTransition(new Slide(android.view.Gravity.BOTTOM))
                                .setDuration(320);
                        TransitionManager.beginDelayedTransition(primaryContainer, transition);
                        preflight.setVisibility(View.GONE);
                        results.setVisibility(View.VISIBLE);
                        gauge.setSpeed(0);
                    });
                }

                @Override public void onUploadStage() {
                    runOnUiThread(() -> {
                        gauge.setSpeed(0);
                        progress.setIndeterminate(true);
                    });
                }

                @Override public void onProgress(int percent) {
                    runOnUiThread(() -> {
                        if (percent >= 70 && progress.isIndeterminate()) {
                            progress.setIndeterminate(false);
                        }
                        if (!progress.isIndeterminate()) progress.setProgress(percent);
                    });
                }

                @Override public void onPingResult(double pingMs, double jitterMs) {
                    finalPing[0] = pingMs;
                    finalJitter[0] = jitterMs;
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
                    runOnUiThread(() -> gauge.setSpeed(mbps));
                }

                @Override public void onUploadSpeedUpdate(double currentMbps) {
                    runOnUiThread(() -> {
                        gauge.setSpeed(currentMbps);
                        txtUpload.setText(currentMbps > 0
                                ? String.format("%.1f", currentMbps) : "—");
                    });
                }

                @Override public void onUploadResult(double mbps) {
                    finalUp[0] = mbps;
                    runOnUiThread(() -> {
                        progress.setIndeterminate(false);
                        progress.setProgress(70);
                        gauge.setSpeed(mbps);
                        txtUpload.setText(mbps > 0 ? String.format("%.1f", mbps) : "—");
                    });
                }

                @Override public void onPrimaryFinished(double pingMs, double jitterMs,
                                                        double downMbps, double upMbps) {
                    finalPing[0] = pingMs;
                    finalJitter[0] = jitterMs;
                    finalDown[0] = downMbps;
                    finalUp[0] = upMbps;
                    runOnUiThread(() -> {
                        gauge.setSpeed(downMbps);
                        txtNormalDownSummary.setText(String.format("%.1f Mbps", downMbps));
                        txtNormalUpSummary.setText(
                                upMbps > 0 ? String.format("%.1f Mbps", upMbps) : "No disponible");
                    });
                }

                @Override public void onRealDownloadStart() {
                    runOnUiThread(() -> {
                        ViewGroup root = findViewById(R.id.rootConstraint);
                        TransitionSet transition = new TransitionSet()
                                .addTransition(new Fade())
                                .addTransition(new Slide(android.view.Gravity.END))
                                .setDuration(280);
                        TransitionManager.beginDelayedTransition(root, transition);

                        primaryScreen.setVisibility(View.GONE);
                        realScreen.setVisibility(View.VISIBLE);
                        realProgress.setVisibility(View.VISIBLE);
                        txtRealPhase.setText(R.string.speedtest_real_starting);
                        txtNormalDownSummary.setText(String.format("%.1f Mbps", finalDown[0]));
                        txtNormalUpSummary.setText(finalUp[0] > 0
                                ? String.format("%.1f Mbps", finalUp[0]) : "No disponible");
                    });
                }

                @Override public void onRealDownloadSpeedUpdate(double currentMbps) {
                    runOnUiThread(() -> {
                        txtRealPhase.setText(R.string.speedtest_real_running);
                        realGauge.setSpeed(currentMbps);
                        txtRealSpeed.setText(String.format("%.1f", currentMbps));
                    });
                }

                @Override public void onRealDownloadResult(double mbps) {
                    finalRealDown[0] = mbps;
                    runOnUiThread(() -> {
                        if (mbps > 0) {
                            realGauge.setSpeed(mbps);
                            txtRealSpeed.setText(String.format("%.1f", mbps));
                        } else {
                            txtRealSpeed.setText("—");
                        }
                    });
                }

                @Override public void onFinished(double pingMs, double jitterMs,
                                                 double downMbps, double upMbps,
                                                 double realDownloadMbps) {
                    finalPing[0] = pingMs;
                    finalJitter[0] = jitterMs;
                    finalDown[0] = downMbps;
                    finalUp[0] = upMbps;
                    finalRealDown[0] = realDownloadMbps;

                    runOnUiThread(() -> {
                        progress.setProgress(100);
                        realProgress.setVisibility(View.GONE);
                        txtRealPhase.setText(realDownloadMbps > 0
                                ? R.string.speedtest_real_done
                                : R.string.speedtest_real_unavailable);
                        btnRestart.setVisibility(View.VISIBLE);
                        btnShare.setVisibility(View.VISIBLE);
                        HapticUtil.performHeavy(btnRestart);
                    });
                }

                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        primaryScreen.setVisibility(View.VISIBLE);
                        realScreen.setVisibility(View.GONE);
                        preflight.setVisibility(View.GONE);
                        results.setVisibility(View.GONE);
                        progress.setIndeterminate(false);
                        progress.setVisibility(View.GONE);
                        txtPhase.setText(message);
                        txtPhase.setVisibility(View.VISIBLE);
                        btnStart.setVisibility(View.VISIBLE);
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    });
                }
            });
        };

        resetUi.run();
        btnStart.setOnClickListener(v -> startTest[0].run());
        btnRestart.setOnClickListener(v -> startTest[0].run());

        btnShare.setOnClickListener(v -> {
            String realValue = finalRealDown[0] > 0
                    ? String.format("%.1f Mbps", finalRealDown[0]) : "No disponible";
            String uploadValue = finalUp[0] > 0
                    ? String.format("%.1f Mbps", finalUp[0]) : "No disponible";
            String text = String.format(
                    "WScanner Speed Test\n" +
                    "Descarga: %.1f Mbps\n" +
                    "Subida: %s\n" +
                    "Ping: %.0f ms\n" +
                    "Jitter: %.1f ms\n" +
                    "Descarga real: %s",
                    finalDown[0], uploadValue, finalPing[0], finalJitter[0], realValue);
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
        if (speedTestTool != null) {
            speedTestTool.cancel();
        }
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
