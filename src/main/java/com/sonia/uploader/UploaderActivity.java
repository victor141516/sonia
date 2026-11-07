package com.sonia.uploader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.sonia.uploader.StatusView.Status;
import com.sonia.uploader.core.Clock;
import com.sonia.uploader.core.Log;

/**
 * Opened from the camera's Application menu: joins a saved Wi-Fi network, uploads every new JPG
 * (and, if enabled, ARW raw files and videos) to Google Photos, optionally into an album, and
 * closes itself. Setup and Google sign-in happen on the web page the camera serves at
 * http://camera-ip:8080/; the upload options can be changed there or on the camera (OK opens
 * {@link OptionsView}). The screen shows one status at a time (see {@link Screens}); the detailed
 * log goes to UPLOADER/LOG.TXT on the card.
 */
public class UploaderActivity extends Activity implements Log, WebServer.Controller {
    // Sony scan codes (com.sony.scalar.sysutil.ScalarInput); camera keys often arrive with keyCode 0.
    private static final int SC_UP = 103;
    private static final int SC_LEFT = 105;
    private static final int SC_RIGHT = 106;
    private static final int SC_DOWN = 108;
    private static final int SC_ENTER = 232;
    private static final int SC_MENU = 514;
    private static final int SC_DIAL_CW = 525;
    private static final int SC_DIAL_CCW = 526;
    private static final int SC_WHEEL_CW = 528;
    private static final int SC_WHEEL_CCW = 529;
    private static final int SC_DELETE = 595;
    private static final int PORT = 8080;
    private static final int CLOSE_AFTER_SECONDS = 30;
    private static final long GOOGLE_RETRY_MS = 15000;

    private static final int ROW_RAW = 0;
    private static final int ROW_VIDEOS = 1;
    private static final int ROW_ALBUM = 2;
    private static final int ROW_WIFI = 3;
    private static final String[] ROWS = {"RAW files (ARW)", "Videos (MP4, AVCHD)", "Album", "Camera Wi-Fi settings"};

    private final Handler ui = new Handler();
    private final SimpleDateFormat stamp = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final Object settingsSignal = new Object();
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            view.show(status);
            if (optionsOpen) {
                renderOptions();
            }
        }
    };

    private StatusView view;
    private OptionsView optionsView;
    private File logFile;
    private volatile Settings settings;
    private UploadedIndex index;
    private volatile GooglePhotos photos;
    private WifiConnector wifi;
    private WebServer server;
    private volatile UploadJob job;
    private volatile Status status = Screens.starting();
    private volatile String pageUrl = "";
    private volatile boolean leaving;
    private volatile boolean optionsOpen;
    private int optionRow;
    private boolean cleanedUp;
    private int settingsVersion;
    private OAuth.Attempt signInAttempt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File cardDir = new File(Environment.getExternalStorageDirectory(), "UPLOADER");
        cardDir.mkdirs();
        logFile = new File(cardDir, "LOG.TXT");
        Diagnostics.rotate(logFile);
        Diagnostics.installCrashHandler(cardDir);
        view = new StatusView(this);
        optionsView = new OptionsView(this, ROWS);
        optionsView.setVisibility(View.GONE);
        FrameLayout root = new FrameLayout(this);
        root.addView(view);
        root.addView(optionsView);
        setContentView(root);
        view.show(status);
        wifi = new WifiConnector(this, this);

        Thread worker = new Thread("uploader") {
            @Override
            public void run() {
                // Background priority: the camera's own real-time processes share these four cores.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                work();
            }
        };
        worker.start();
    }

    // ------------------------------------------------------------------ worker

    private void work() {
        log("==== Upload to Google Photos " + versionName());
        long ca;
        try {
            settings = new Settings(new File(getFilesDir(), "settings.properties"));
            index = new UploadedIndex(new File(getFilesDir(), "uploaded.txt"));
            UploadSessions sessions = new UploadSessions(new File(getFilesDir(), "upload-sessions.properties"));
            log(NativeTls.nativeInit());
            InputStream pem = getAssets().open("roots.pem");
            try {
                ca = NativeTls.loadCa(pem);
            } finally {
                pem.close();
            }
            Clock clock = CameraClock.create(this);
            photos = new GooglePhotos(NativeTls.connector(ca, clock), settings, sessions, this);
        } catch (Throwable e) {
            show(Screens.startupError(e.toString()));
            log("startup: " + e);
            return;
        }

        String ip = wifi.connect(60000, new WifiConnector.Stages() {
            public void turningOn() {
                show(Screens.turningOnWifi());
            }

            public void searching() {
                show(Screens.searching());
            }

            public void found(String ssid) {
                show(Screens.networkFound(ssid));
            }

            public void connecting(String ssid) {
                show(Screens.connecting(ssid));
            }
        });
        while (ip == null && !leaving) {
            show(Screens.noNetwork());
            ip = wifi.connect(30000, new WifiConnector.Stages() {
                public void turningOn() {}

                public void searching() {}

                public void found(String ssid) {
                    show(Screens.networkFound(ssid));
                }

                public void connecting(String ssid) {
                    show(Screens.connecting(ssid));
                }
            });
        }
        if (leaving) {
            return;
        }
        show(Screens.connected(wifi.ssid()));
        String page = "http://" + ip + ":" + PORT;
        try {
            server = new WebServer(PORT, this, this);
            server.start();
            pageUrl = page;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    view.setPageUrl(pageUrl);
                    if (optionsOpen) {
                        renderOptions();
                    }
                }
            });
        } catch (IOException e) {
            log("web: cannot start " + e);
        }
        File card = Environment.getExternalStorageDirectory();

        while (!leaving) {
            if (!settings.hasClient() || !settings.isLoggedIn()) {
                show(Screens.notSignedIn(page));
                waitForSettingsChange(0);
                continue;
            }
            show(Screens.signingIn());
            try {
                photos.checkSignIn();
            } catch (GooglePhotos.AuthException e) {
                log("sign-in check: " + e.getMessage());
                show(Screens.signInExpired(page));
                waitForSettingsChange(0);
                continue;
            } catch (IOException e) {
                log("sign-in check: " + e);
                show(Screens.googleUnreachable());
                waitForSettingsChange(GOOGLE_RETRY_MS);
                continue;
            }
            refreshAlbums();

            // Changes made on the options screen apply to this session: wait until it is closed.
            while (optionsOpen && !leaving) {
                sleep(200);
            }
            if (leaving) {
                return;
            }
            String scannedOptions = uploadOptions();
            show(Screens.scanning(settings.uploadVideos()));
            UploadJob current = new UploadJob(card, settings, index, photos, new UploadJob.Battery() {
                public int level() {
                    return batteryPercent();
                }
            }, this, new UploadJob.Listener() {
                public void uploading(int number, int files, boolean video, long done, long bytesTotal,
                        double bytesPerSecond) {
                    show(Screens.uploading(number, files, video, done, bytesTotal, bytesPerSecond,
                            settings.albumTitle()));
                }

                public void retrying(String reason) {
                    show(Screens.retrying());
                }
            });
            job = current;
            Status result;
            if (current.scan() == 0) {
                job = null;
                result = Screens.nothingNew(settings.uploadVideos());
            } else {
                UploadJob.Outcome outcome = current.run();
                job = null;
                log("result: " + outcome + ", created " + current.created().total() + " of "
                        + current.pendingCounts().total() + ", failed " + current.failed());
                switch (outcome) {
                    case DONE:
                        if (!uploadOptions().equals(scannedOptions)) {
                            log("options changed during the upload: looking again");
                            continue;
                        }
                        result = Screens.done(current.created(), current.failed(), settings.albumTitle(),
                                current.albumProblem());
                        break;
                    case QUOTA:
                        result = Screens.quota(current.created(), current.pendingCounts());
                        break;
                    case BATTERY:
                        result = Screens.lowBattery(current.created(), current.pendingCounts());
                        break;
                    case NETWORK:
                        result = Screens.interrupted(current.created(), current.pendingCounts());
                        break;
                    case AUTH:
                        show(Screens.signInExpired(page));
                        waitForSettingsChange(0);
                        continue;
                    case STOPPED:
                    default:
                        return;
                }
            }
            if (!closeLater(result)) {
                return;
            }
            log("options changed: looking again");
        }
    }

    /** The options that decide which files are uploaded (the album does not). */
    private String uploadOptions() {
        return "raw=" + settings.uploadRaw() + " videos=" + settings.uploadVideos();
    }

    /**
     * Updates the cached album list from Google (the camera screen and the web page show it).
     * Keeps the cached list if Google cannot be asked.
     */
    private void refreshAlbums() {
        if (!photos.canListAlbums()) {
            log("albums: this sign-in cannot list albums; sign in again on the web page");
            return;
        }
        try {
            List<Settings.Album> albums = photos.listAlbums();
            String dropped = settings.setAlbums(albums);
            log("albums: " + albums.size() + " created by this app");
            if (dropped != null) {
                log("albums: \"" + dropped + "\" no longer exists; uploading without album");
            }
            settingsChanged();
        } catch (IOException e) {
            log("albums: " + e);
        }
    }

    /**
     * Shows the result with a countdown, then leaves so the camera's own power saving can turn it
     * off. The countdown pauses while the options screen is open. Returns true instead if the
     * upload options change meanwhile (on the camera or the web page), to look for files again.
     */
    private boolean closeLater(Status result) {
        String options = uploadOptions();
        int left = CLOSE_AFTER_SECONDS;
        while (left > 0 && !leaving) {
            if (!uploadOptions().equals(options)) {
                return true;
            }
            show(Screens.closingIn(result, left));
            sleep(1000);
            if (!optionsOpen) {
                left--;
            }
        }
        if (!leaving) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    exit();
                }
            });
        }
        return false;
    }

    /** Waits until the settings change, the app exits, or timeoutMs passes (0 = no timeout). */
    private void waitForSettingsChange(long timeoutMs) {
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        synchronized (settingsSignal) {
            int seen = settingsVersion;
            while (!leaving && settingsVersion == seen && System.currentTimeMillis() < deadline) {
                try {
                    settingsSignal.wait(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ WebServer.Controller

    @Override
    public String headline() {
        return status.headline;
    }

    @Override
    public String detail() {
        Status s = status;
        if (s.progress) {
            return s.progressLeft + "  \u00b7  " + s.progressRight + "  \u00b7  " + s.progressBottom;
        }
        return s.emphasis.length() == 0 ? s.detail : s.detail + " " + s.emphasis + ". " + s.footnote;
    }

    @Override
    public Settings settings() {
        return settings;
    }

    @Override
    public synchronized String beginSignIn() {
        signInAttempt = OAuth.newAttempt();
        return OAuth.authUrl(settings.clientId(), settings.redirectUri(), signInAttempt);
    }

    @Override
    public synchronized String finishSignIn(String pasted) {
        if (signInAttempt == null) {
            return "Press \"Open Google\" on this page first.";
        }
        try {
            String code = OAuth.codeFrom(pasted, signInAttempt);
            photos.signIn(code, signInAttempt.verifier);
            signInAttempt = null;
            refreshAlbums();
            return "Signed in. The camera is starting to upload your photos.";
        } catch (IllegalArgumentException e) {
            return "Could not read that address: " + e.getMessage();
        } catch (IOException e) {
            log("sign-in: " + e);
            return "Google rejected the sign-in: " + e.getMessage() + ". Press \"Open Google\" again.";
        }
    }

    @Override
    public boolean albumsNeedSignIn() {
        GooglePhotos p = photos;
        return settings.isLoggedIn() && p != null && p.scopeKnown() && !p.canListAlbums();
    }

    @Override
    public String createAlbum(String title) {
        if (title.length() == 0) {
            return "Type a name for the album.";
        }
        if (title.length() > 500) {
            return "Album names can have at most 500 characters.";
        }
        try {
            Settings.Album album = photos.createAlbum(title);
            settings.addAndSelectAlbum(album);
            log("web: album created");
            return "Album \"" + album.title + "\" created. New photos will go into it.";
        } catch (GooglePhotos.AuthException e) {
            return "Sign in to Google first: " + e.getMessage();
        } catch (IOException e) {
            log("create album: " + e);
            return "Google did not create the album: " + e.getMessage();
        }
    }

    @Override
    public void settingsChanged() {
        synchronized (settingsSignal) {
            settingsVersion++;
            settingsSignal.notifyAll();
        }
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }

    // ------------------------------------------------------------------ options screen

    private void openOptions() {
        optionsOpen = true;
        renderOptions();
        optionsView.setVisibility(View.VISIBLE);
        view.setVisibility(View.GONE);
    }

    private void closeOptions() {
        optionsOpen = false;
        view.setVisibility(View.VISIBLE);
        optionsView.setVisibility(View.GONE);
    }

    private void renderOptions() {
        Settings s = settings;
        String album = s.albumId().length() == 0 ? "No album" : s.albumTitle();
        String[] values = {s.uploadRaw() ? "On" : "Off", s.uploadVideos() ? "On" : "Off", album, "Open"};
        optionsView.show(values, optionRow, optionNote(s), pageUrl);
    }

    private String optionNote(Settings s) {
        switch (optionRow) {
            case ROW_RAW:
                return "Also upload the .ARW raw files. Google Photos keeps them untouched, so you can download "
                        + "them later and develop them with your own software.";
            case ROW_VIDEOS:
                return "Also upload MP4 and AVCHD videos. Long videos take a while; if the connection drops they "
                        + "continue where they stopped.";
            case ROW_ALBUM:
                if (!s.isLoggedIn()) {
                    return "Sign in to Google on the web page first.";
                }
                if (albumsNeedSignIn()) {
                    return "To use albums, sign in to Google again on the web page: Google needs one more "
                            + "permission.";
                }
                if (s.albums().isEmpty()) {
                    return "Create an album on the web page; it then appears here. Google only lets this app add "
                            + "to albums it created.";
                }
                return "Where new photos and videos go. To create another album, use the web page.";
            case ROW_WIFI:
            default:
                return "Add or change the Wi-Fi networks the camera can use. Press OK to open.";
        }
    }

    /** LEFT/RIGHT (or OK) on the selected row. */
    private void changeOption(int direction) {
        Settings s = settings;
        try {
            switch (optionRow) {
                case ROW_RAW:
                    s.setOptions(!s.uploadRaw(), s.uploadVideos(), s.albumId());
                    break;
                case ROW_VIDEOS:
                    s.setOptions(s.uploadRaw(), !s.uploadVideos(), s.albumId());
                    break;
                case ROW_ALBUM:
                    List<Settings.Album> albums = s.albums();
                    int current = -1;
                    for (int i = 0; i < albums.size(); i++) {
                        if (albums.get(i).id.equals(s.albumId())) {
                            current = i;
                        }
                    }
                    // Cycle through "No album" (-1) and every album.
                    int count = albums.size() + 1;
                    int next = ((current + 1 + direction) % count + count) % count - 1;
                    s.setOptions(s.uploadRaw(), s.uploadVideos(), next < 0 ? "" : albums.get(next).id);
                    break;
                case ROW_WIFI:
                default:
                    openWifiSettings();
                    return;
            }
        } catch (IOException e) {
            log("options: " + e);
        }
        log("options (camera): " + uploadOptions() + " album=" + (s.albumId().length() > 0 ? "set" : "none"));
        settingsChanged();
    }

    private void openWifiSettings() {
        wifi.ensureEnabled();
        try {
            startActivity(new Intent("com.sony.scalar.app.wifisettings.WifiSettings"));
        } catch (ActivityNotFoundException e) {
            log("Sony Wi-Fi settings unavailable: " + e);
        }
    }

    // ------------------------------------------------------------------ status and log

    private void show(Status next) {
        status = next;
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }

    @Override
    public void log(String line) {
        Diagnostics.append(logFile, stamp.format(new Date()) + " " + line + "\r\n");
        android.util.Log.i("SoniaUploader", line);
    }

    private int batteryPercent() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) {
            return -1;
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return level < 0 || scale <= 0 ? -1 : level * 100 / scale;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ lifecycle and keys

    @Override
    protected void onResume() {
        super.onResume();
        Intent info = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        info.putExtra("package_name", getComponentName().getPackageName());
        info.putExtra("class_name", getComponentName().getClassName());
        sendBroadcast(info);
        setAutoPowerOff(false);
    }

    @Override
    protected void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scan = event.getScanCode();
        boolean back = scan == SC_MENU || scan == SC_DELETE || keyCode == KeyEvent.KEYCODE_BACK;
        boolean enter = scan == SC_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
        if (optionsOpen) {
            if (back) {
                closeOptions();
            } else if (scan == SC_UP || scan == SC_WHEEL_CCW || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                optionRow = (optionRow + ROWS.length - 1) % ROWS.length;
                renderOptions();
            } else if (scan == SC_DOWN || scan == SC_WHEEL_CW || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                optionRow = (optionRow + 1) % ROWS.length;
                renderOptions();
            } else if (scan == SC_LEFT || scan == SC_DIAL_CCW || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                changeOption(-1);
            } else if (enter || scan == SC_RIGHT || scan == SC_DIAL_CW || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                changeOption(1);
            } else {
                return super.onKeyDown(keyCode, event);
            }
            return true;
        }
        if (back) {
            exit();
            return true;
        }
        if (enter) {
            // The options are read from the private storage by the worker; usable once it has loaded them.
            if (settings != null) {
                openOptions();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void setAutoPowerOff(boolean enable) {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.apo");
        intent.putExtra("apo_info", enable ? "APO/NORMAL" : "APO/NO");
        sendBroadcast(intent);
    }

    private void exit() {
        leaving = true;
        cleanup();
        finish();
    }

    private void cleanup() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        leaving = true;
        UploadJob current = job;
        if (current != null) {
            current.stop();
        }
        settingsChanged();
        if (server != null) {
            server.shutdown();
        }
        wifi.shutdown();
        setAutoPowerOff(true);
        log("exit");
    }
}
