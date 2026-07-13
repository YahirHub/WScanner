package com.thowilabs.wscanner;

import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private static final String TAG = "WScanner.UI";

    private DrawerLayout drawerLayout;
    private NavigationView navView;

    private final ArrayList<Device> devices = new ArrayList<>();
    private final Set<String> seenIps = new HashSet<>();
    private DeviceAdapter adapter;

    private Button btnScan;
    private ProgressBar progressScan;
    private TextView txtStatus;

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
        navView.setCheckedItem(R.id.nav_scanner); // Marcar como activo

        // RecyclerView
        btnScan = findViewById(R.id.btnScan);
        progressScan = findViewById(R.id.progressScan);
        txtStatus = findViewById(R.id.txtStatus);

        RecyclerView rv = findViewById(R.id.listDevices);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DeviceAdapter(devices);
        rv.setAdapter(adapter);

        btnScan.setOnClickListener(v -> startScan());
    }

    // ── Drawer clicks ──────────────────────────────────────────────

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_scanner) {
            // Ya estamos en la pantalla principal
        } else if (id == R.id.nav_history) {
            item.setChecked(false);
            navView.setCheckedItem(R.id.nav_scanner);
            Toast.makeText(this, "Historial — próximamente", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.nav_settings) {
            Toast.makeText(this, "Configuración — próximamente", Toast.LENGTH_SHORT).show();
        } else if (id == R.id.nav_about) {
            Toast.makeText(this, "WScanner v1.0 — Escáner de red local", Toast.LENGTH_SHORT).show();
        }

        drawerLayout.closeDrawer(navView);
        return true;
    }

    // ── Escaneo ────────────────────────────────────────────────────

    private void startScan() {
        Log.i(TAG, "🧹 Limpiando lista previa (" + devices.size() + " dispositivos)");
        devices.clear();
        seenIps.clear();
        adapter.notifyDataSetChanged();

        setScanning(true);
        Log.i(TAG, "🚀 Iniciando NetworkScanner...");

        new NetworkScanner().scan(this, new NetworkScanner.Callback() {
            @Override
            public void onDeviceFound(Device device) {
                runOnUiThread(() -> {
                    if (seenIps.add(device.ip)) {
                        devices.add(device);
                        adapter.notifyItemInserted(devices.size() - 1);
                        Log.d(TAG, "➕ Device #" + (devices.size()) + ": " + device.ip + " → \"" + device.name + "\"");
                    } else {
                        Log.w(TAG, "⏭️  IP duplicada ignorada: " + device.ip);
                    }
                });
            }

            @Override
            public void onProgress(int percent, int scanned, int total) {
                runOnUiThread(() -> {
                    progressScan.setProgress(percent);
                    txtStatus.setText("Escaneando... " + scanned + " de " + total
                            + "  (" + devices.size() + " encontrados)");
                });
            }

            @Override
            public void onFinished(int totalFound) {
                runOnUiThread(() -> {
                    setScanning(false);
                    txtStatus.setText("Escaneo completo: " + totalFound
                            + " dispositivo(s) encontrado(s)");
                    if (totalFound == 0) {
                        Toast.makeText(MainActivity.this,
                                "No se encontraron dispositivos",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void setScanning(boolean scanning) {
        btnScan.setEnabled(!scanning);
        btnScan.setText(scanning ? "Escaneando..." : "Escanear red");
        progressScan.setVisibility(scanning ? View.VISIBLE : View.GONE);
        if (scanning) {
            progressScan.setProgress(0);
            txtStatus.setText("Iniciando escaneo...");
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(navView)) {
            drawerLayout.closeDrawer(navView);
        } else {
            super.onBackPressed();
        }
    }
}
