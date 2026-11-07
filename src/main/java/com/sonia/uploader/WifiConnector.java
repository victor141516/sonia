package com.sonia.uploader;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;

import com.sonia.uploader.core.Log;

/**
 * Joins one of the networks saved in the camera's own Wi-Fi settings (the standard Android
 * WifiManager sees them on the a6000; measured: connected with an IP in about 10 s) and reports
 * each stage for the status screen.
 */
final class WifiConnector {
    interface Stages {
        void turningOn();

        void searching();

        void found(String ssid);

        void connecting(String ssid);
    }

    private final WifiManager wifi;
    private final ConnectivityManager connectivity;
    private final Log log;
    private WifiManager.WifiLock lock;

    WifiConnector(Context context, Log log) {
        this.wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        this.connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.log = log;
    }

    /** Turns Wi-Fi on and waits for a saved network; returns the camera's IP, or null on timeout. */
    String connect(long timeoutMs, Stages stages) {
        if (!wifi.isWifiEnabled()) {
            stages.turningOn();
            wifi.setWifiEnabled(true);
        }
        long enableDeadline = System.currentTimeMillis() + 20000;
        while (wifi.getWifiState() != WifiManager.WIFI_STATE_ENABLED
                && System.currentTimeMillis() < enableDeadline) {
            sleep(250);
        }
        if (lock == null) {
            lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL, "sonia-uploader");
            lock.acquire();
        }
        stages.searching();
        wifi.startScan();

        long deadline = System.currentTimeMillis() + timeoutMs;
        long nudgeAt = System.currentTimeMillis() + timeoutMs / 2;
        boolean nudged = false;
        String reported = null;
        while (System.currentTimeMillis() < deadline) {
            String ip = ipIfConnected();
            if (ip != null) {
                WifiInfo info = wifi.getConnectionInfo();
                log.log("wifi: connected to " + info.getSSID() + ", IP " + ip + ", " + info.getRssi() + " dBm");
                return ip;
            }
            String ssid = savedNetworkInRange();
            if (ssid != null) {
                boolean associating = isAssociating();
                String stage = (associating ? "connecting:" : "found:") + ssid;
                if (!stage.equals(reported)) {
                    reported = stage;
                    if (associating) {
                        stages.connecting(ssid);
                    } else {
                        stages.found(ssid);
                    }
                }
            }
            if (!nudged && System.currentTimeMillis() > nudgeAt) {
                // The supplicant did not pick a network by itself: nudge the saved ones in range.
                nudged = true;
                nudgeSavedNetworks();
            }
            sleep(500);
        }
        return null;
    }

    /** First saved network that the last scan saw, without quotes; null if none. */
    private String savedNetworkInRange() {
        List<ScanResult> scan = wifi.getScanResults();
        List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
        if (scan == null || saved == null) {
            return null;
        }
        Set<String> visible = new HashSet<String>();
        for (ScanResult r : scan) {
            visible.add("\"" + r.SSID + "\"");
        }
        for (WifiConfiguration config : saved) {
            if (config.SSID != null && visible.contains(config.SSID)) {
                return config.SSID.replace("\"", "");
            }
        }
        return null;
    }

    private boolean isAssociating() {
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null) {
            return false;
        }
        SupplicantState state = info.getSupplicantState();
        return state == SupplicantState.ASSOCIATING || state == SupplicantState.ASSOCIATED
                || state == SupplicantState.FOUR_WAY_HANDSHAKE || state == SupplicantState.GROUP_HANDSHAKE
                || state == SupplicantState.COMPLETED;
    }

    private void nudgeSavedNetworks() {
        List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
        String inRange = savedNetworkInRange();
        log.log("wifi: saved " + (saved == null ? 0 : saved.size()) + ", in range: " + inRange);
        if (saved != null) {
            for (WifiConfiguration config : saved) {
                wifi.enableNetwork(config.networkId, false);
            }
        }
        wifi.reconnect();
        wifi.startScan();
    }

    private String ipIfConnected() {
        NetworkInfo network = connectivity.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        WifiInfo info = wifi.getConnectionInfo();
        if (network != null && network.isConnected() && info != null && info.getIpAddress() != 0) {
            return Formatter.formatIpAddress(info.getIpAddress());
        }
        return null;
    }

    /** Name of the network the camera is connected to, without quotes. */
    String ssid() {
        WifiInfo info = wifi.getConnectionInfo();
        return info == null || info.getSSID() == null ? "" : info.getSSID().replace("\"", "");
    }

    void ensureEnabled() {
        if (!wifi.isWifiEnabled()) {
            wifi.setWifiEnabled(true);
        }
    }

    void shutdown() {
        if (lock != null && lock.isHeld()) {
            lock.release();
        }
        wifi.setWifiEnabled(false);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
