package com.sonia.uploader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.sonia.uploader.core.Log;
import com.sonia.uploader.core.MiniHttp;

/**
 * One upload session: every file that the options select (JPG always; ARW and videos if enabled)
 * and that is not in the index, oldest first. Bytes are uploaded one file at a time; library
 * items are created in batches and only then recorded in the index, so an interrupted session
 * never skips a file (at worst an upload token expires unused and the file is sent again next time).
 */
final class UploadJob {
    /** Items per mediaItems:batchCreate: small enough that an interruption loses little work. */
    static final int BATCH = 10;
    static final int MIN_BATTERY_PERCENT = 15;
    private static final long[] RETRY_DELAYS_MS = {5000, 15000, 30000};
    /** Progress callbacks at most this often (the camera's UI thread is slow). */
    private static final long PROGRESS_INTERVAL_MS = 500;

    /**
     * Where the a6000 records videos, relative to the card: MP4 in MP_ROOT/100ANV01, AVCHD in
     * PRIVATE/AVCHD/BDMV/STREAM (later Sony bodies: PRIVATE/M4ROOT/CLIP).
     */
    private static final String[] VIDEO_FOLDERS = {"PRIVATE/AVCHD/BDMV/STREAM", "PRIVATE/M4ROOT/CLIP"};
    private static final String VIDEO_PARENT = "MP_ROOT";

    enum Outcome { DONE, QUOTA, AUTH, BATTERY, NETWORK, STOPPED }

    interface Listener {
        /**
         * @param number         1-based number of the file being uploaded
         * @param video          whether that file is a video
         * @param bytesDone      bytes of this session already sent, including the current file
         * @param bytesPerSecond average upload speed of the session so far (0 until known)
         */
        void uploading(int number, int files, boolean video, long bytesDone, long bytesTotal, double bytesPerSecond);

        void retrying(String reason);
    }

    interface Battery {
        /** Percent, or -1 if unknown. */
        int level();
    }

    /** What was found to upload, for the screens' wording. */
    static final class Counts {
        int photos;
        int videos;

        int total() {
            return photos + videos;
        }
    }

    private final File card;
    private final File dcim;
    private final Settings settings;
    private final UploadedIndex index;
    private final GooglePhotos photos;
    private final Battery battery;
    private final Log log;
    private final Listener listener;
    private volatile boolean stopRequested;

    private final List<File> batchFiles = new ArrayList<File>();
    private final List<String> batchTokens = new ArrayList<String>();
    private List<File> pending = new ArrayList<File>();
    private final Counts pendingCounts = new Counts();
    private final Counts createdCounts = new Counts();
    private long bytesTotal;
    private long bytesDone;
    private long uploadMs;
    private int failed;
    private String lastError = "";
    private String albumProblem;

    UploadJob(File card, Settings settings, UploadedIndex index, GooglePhotos photos, Battery battery, Log log,
            Listener listener) {
        this.card = card;
        this.dcim = new File(card, "DCIM");
        this.settings = settings;
        this.index = index;
        this.photos = photos;
        this.battery = battery;
        this.log = log;
        this.listener = listener;
    }

    void stop() {
        stopRequested = true;
    }

    /** Lists the files to upload; call before {@link #run()} to know whether there is anything to do. */
    int scan() {
        boolean raw = settings.uploadRaw();
        boolean videos = settings.uploadVideos();
        pending = pendingFiles(raw, videos);
        bytesTotal = 0;
        pendingCounts.photos = 0;
        pendingCounts.videos = 0;
        for (File file : pending) {
            bytesTotal += file.length();
            if (isVideo(file)) {
                pendingCounts.videos++;
            } else {
                pendingCounts.photos++;
            }
        }
        log.log("pending: " + pendingCounts.photos + " photos, " + pendingCounts.videos + " videos, "
                + megabytes(bytesTotal) + " MB (RAW " + (raw ? "on" : "off") + ", videos " + (videos ? "on" : "off")
                + "; already uploaded: " + index.size() + ")");
        return pending.size();
    }

    Outcome run() {
        try {
            for (int i = 0; i < pending.size(); i++) {
                if (stopRequested) {
                    flush();
                    return Outcome.STOPPED;
                }
                int level = battery.level();
                if (level >= 0 && level < MIN_BATTERY_PERCENT) {
                    flush();
                    lastError = "battery at " + level + "%";
                    return Outcome.BATTERY;
                }
                File file = pending.get(i);
                String token = uploadWithRetries(file, i + 1);
                if (token == null) {
                    continue;
                }
                batchFiles.add(file);
                batchTokens.add(token);
                if (batchFiles.size() >= BATCH) {
                    flush();
                }
            }
            flush();
            return Outcome.DONE;
        } catch (GooglePhotos.QuotaException e) {
            return fail(Outcome.QUOTA, e);
        } catch (GooglePhotos.AuthException e) {
            return fail(Outcome.AUTH, e);
        } catch (IOException e) {
            return fail(Outcome.NETWORK, e);
        }
    }

    private Outcome fail(Outcome outcome, IOException e) {
        lastError = e.getMessage();
        log.log("stopped (" + outcome + "): " + e);
        return outcome;
    }

    /** Returns the upload token, or null if Google rejected this particular file (skipped). */
    private String uploadWithRetries(final File file, final int number) throws IOException {
        final long size = file.length();
        final boolean video = isVideo(file);
        for (int attempt = 0; ; attempt++) {
            final long start = System.currentTimeMillis();
            final long doneBefore = bytesDone;
            listener.uploading(number, pending.size(), video, doneBefore, bytesTotal, speed(0, 0));
            try {
                String token = photos.upload(file, mimeType(file), key(file), new MiniHttp.Progress() {
                    private long lastReport = start;

                    @Override
                    public void onSent(long bytes) {
                        long now = System.currentTimeMillis();
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now;
                            listener.uploading(number, pending.size(), video, doneBefore + bytes, bytesTotal,
                                    speed(bytes, now - start));
                        }
                    }
                });
                long ms = System.currentTimeMillis() - start;
                bytesDone += size;
                uploadMs += ms;
                listener.uploading(number, pending.size(), video, bytesDone, bytesTotal, speed(0, 0));
                log.log(String.format(Locale.US, "uploaded %s, %s MB in %.1f s", file.getName(), megabytes(size),
                        ms / 1000.0));
                return token;
            } catch (GooglePhotos.HttpException e) {
                if (!e.retryable()) {
                    failed++;
                    bytesDone += size;
                    lastError = e.getMessage();
                    log.log("skipped " + file.getName() + ": " + e.getMessage());
                    return null;
                }
                retryOrThrow(attempt, file.getName(), e);
            } catch (GooglePhotos.QuotaException e) {
                flush();
                throw e;
            } catch (GooglePhotos.AuthException e) {
                throw e;
            } catch (IOException e) {
                retryOrThrow(attempt, file.getName(), e);
            }
        }
    }

    /** Session average in bytes/s, counting the file in flight. */
    private double speed(long currentBytes, long currentMs) {
        long ms = uploadMs + currentMs;
        return ms < 1000 ? 0 : (bytesDone + currentBytes) * 1000.0 / ms;
    }

    private void retryOrThrow(int attempt, String what, IOException e) throws IOException {
        if (attempt >= RETRY_DELAYS_MS.length) {
            flush();
            throw e;
        }
        log.log("retry " + (attempt + 1) + " of " + what + " after: " + e);
        listener.retrying(e.getMessage());
        sleep(RETRY_DELAYS_MS[attempt]);
    }

    /**
     * Creates the batched items in the library (and the chosen album), then records the successful
     * ones in the index. If Google refuses the album (deleted in Google Photos since it was chosen),
     * the album is unselected and the items go to the library only.
     */
    private void flush() throws IOException {
        if (batchFiles.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<String>();
        for (File f : batchFiles) {
            names.add(f.getName());
        }
        boolean[] ok = null;
        for (int attempt = 0; ok == null; attempt++) {
            String albumId = settings.albumId();
            try {
                ok = photos.createMediaItems(batchTokens, names, albumId);
            } catch (GooglePhotos.HttpException e) {
                if (!e.retryable() && albumId.length() > 0 && e.status >= 400 && e.status < 500) {
                    albumProblem = settings.albumTitle();
                    log.log("album \"" + albumProblem + "\" refused (" + e.getMessage() + "); uploading without album");
                    settings.setOptions(settings.uploadRaw(), settings.uploadVideos(), "");
                    continue;
                }
                if (!e.retryable() || attempt >= RETRY_DELAYS_MS.length) {
                    throw e;
                }
                log.log("retry batchCreate after: " + e.getMessage());
                sleep(RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)]);
            }
        }
        List<String> keys = new ArrayList<String>();
        for (int i = 0; i < ok.length; i++) {
            File file = batchFiles.get(i);
            if (ok[i]) {
                keys.add(key(file));
                if (isVideo(file)) {
                    createdCounts.videos++;
                } else {
                    createdCounts.photos++;
                }
            } else {
                failed++;
                log.log("Google did not create " + names.get(i));
            }
        }
        index.addAll(keys);
        log.log("added to Google Photos: " + keys.size() + " of " + ok.length + " (session total "
                + createdCounts.total() + ")");
        batchFiles.clear();
        batchTokens.clear();
    }

    List<File> pendingFiles(boolean raw, boolean videos) {
        List<File> files = new ArrayList<File>();
        File[] folders = dcim.listFiles();
        for (int i = 0; folders != null && i < folders.length; i++) {
            addPending(files, folders[i], raw ? new String[] {".JPG", ".ARW"} : new String[] {".JPG"});
        }
        if (videos) {
            File[] mp4Folders = new File(card, VIDEO_PARENT).listFiles();
            for (int i = 0; mp4Folders != null && i < mp4Folders.length; i++) {
                addPending(files, mp4Folders[i], new String[] {".MP4"});
            }
            for (String folder : VIDEO_FOLDERS) {
                addPending(files, new File(card, folder), new String[] {".MTS", ".MP4"});
            }
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long diff = a.lastModified() - b.lastModified();
                return diff != 0 ? (diff < 0 ? -1 : 1) : a.getPath().compareTo(b.getPath());
            }
        });
        return files;
    }

    private void addPending(List<File> into, File folder, String[] extensions) {
        File[] files = folder.isDirectory() ? folder.listFiles() : null;
        for (int i = 0; files != null && i < files.length; i++) {
            File file = files[i];
            if (file.isFile() && !file.getName().startsWith(".") && hasExtension(file, extensions)
                    && !index.contains(key(file))) {
                into.add(file);
            }
        }
    }

    private static boolean hasExtension(File file, String[] extensions) {
        String name = file.getName().toUpperCase(Locale.US);
        for (String extension : extensions) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /** Index key: photos relative to DCIM (as in version 1.2), videos relative to the card. */
    private String key(File file) {
        return file.getPath().startsWith(dcim.getPath() + File.separator) ? UploadedIndex.key(dcim, file)
                : UploadedIndex.key(card, file);
    }

    static boolean isVideo(File file) {
        return hasExtension(file, new String[] {".MP4", ".MTS"});
    }

    static String mimeType(File file) {
        String name = file.getName().toUpperCase(Locale.US);
        if (name.endsWith(".ARW")) {
            return "image/x-sony-arw";
        }
        if (name.endsWith(".MP4")) {
            return "video/mp4";
        }
        if (name.endsWith(".MTS")) {
            return "video/mp2t";
        }
        return "image/jpeg";
    }

    Counts created() {
        return createdCounts;
    }

    Counts pendingCounts() {
        return pendingCounts;
    }

    int failed() {
        return failed;
    }

    /** Title of the chosen album if Google refused it during this session, else null. */
    String albumProblem() {
        return albumProblem;
    }

    String lastError() {
        return lastError;
    }

    static String megabytes(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / 1048576.0);
    }

    private void sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!stopRequested && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
