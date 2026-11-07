package com.sonia.uploader;

import java.util.Locale;

import com.sonia.uploader.StatusView.Status;

/** Every status the camera screen can show, in one place (also mirrored on the web page). */
final class Screens {
    static final String HINT = "OK  Options        MENU  Exit";

    private Screens() {}

    static Status starting() {
        return Status.message(Status.NORMAL, "Starting...", "", HINT);
    }

    static Status startupError(String reason) {
        return Status.message(Status.ERROR, "The app could not start", reason, "MENU  Exit");
    }

    static Status turningOnWifi() {
        return Status.message(Status.NORMAL, "Turning on Wi-Fi...", "", HINT);
    }

    static Status searching() {
        return Status.message(Status.NORMAL, "Searching for Wi-Fi networks...", "", HINT);
    }

    static Status networkFound(String ssid) {
        return Status.message(Status.NORMAL, "Network found", ssid, HINT);
    }

    static Status connecting(String ssid) {
        return Status.message(Status.NORMAL, "Connecting to " + ssid + "...", "", HINT);
    }

    static Status connected(String ssid) {
        return Status.message(Status.OK, "Connected to " + ssid, "", HINT);
    }

    static Status noNetwork() {
        return Status.message(Status.WARNING, "No known Wi-Fi network found",
                "Make sure you are near a network saved in the camera. To add one, press OK and choose "
                        + "Camera Wi-Fi settings (or MENU > Wireless > Access Point Set. on the camera). "
                        + "Still searching...",
                HINT);
    }

    static Status signingIn() {
        return Status.message(Status.NORMAL, "Signing in to Google...", "", HINT);
    }

    static Status notSignedIn(String pageUrl) {
        return Status.withAddress(Status.WARNING, "Not signed in to Google yet",
                "On a computer or phone connected to the same Wi-Fi, open this address in a web browser:",
                pageUrl, "Follow the steps there. Uploading starts by itself afterwards.", HINT);
    }

    static Status signInExpired(String pageUrl) {
        return Status.withAddress(Status.WARNING, "Please sign in to Google again",
                "Google needs you to confirm access again. On a computer or phone on the same Wi-Fi, open:",
                pageUrl, "Uploading continues by itself afterwards.", HINT);
    }

    static Status googleUnreachable() {
        return Status.message(Status.WARNING, "Can't reach Google",
                "Check that this Wi-Fi network has Internet access. Trying again shortly...", HINT);
    }

    static Status scanning(boolean videos) {
        return Status.message(Status.NORMAL, videos ? "Looking for new photos and videos..." : "Looking for new photos...",
                "", HINT);
    }

    static Status uploading(int number, int files, boolean video, long bytesDone, long bytesTotal,
            double bytesPerSecond, String album) {
        int permille = bytesTotal <= 0 ? 0 : (int) Math.min(1000, bytesDone * 1000 / bytesTotal);
        String speed;
        if (bytesPerSecond <= 0) {
            speed = "Measuring speed...";
        } else {
            speed = String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1048576.0) + "  \u00b7  "
                    + timeLeft((bytesTotal - bytesDone) / bytesPerSecond);
        }
        String detail = "Keep the camera on until it finishes.";
        if (album.length() > 0) {
            detail = "Into the album \"" + album + "\". " + detail;
        }
        return Status.progress("Uploading", detail, permille,
                (video ? "Video " : "Photo ") + number + " of " + files, (permille / 10) + "%", speed, HINT);
    }

    static Status retrying() {
        return Status.message(Status.WARNING, "Connection problem, retrying...",
                "The upload continues automatically when the connection recovers.", HINT);
    }

    static Status nothingNew(boolean videos) {
        return Status.message(Status.OK, videos ? "Everything is already uploaded" : "All photos are already uploaded",
                "There is nothing new on this card.", HINT);
    }

    /**
     * @param album        album the items went into ("" = library only)
     * @param albumProblem album Google refused during the session (deleted?), or null
     */
    static Status done(UploadJob.Counts created, int failed, String album, String albumProblem) {
        String detail = items(created.photos, created.videos) + " added to Google Photos"
                + (album.length() > 0 ? ", album \"" + album + "\"." : ".");
        if (albumProblem != null) {
            detail += "\nThe album \"" + albumProblem + "\" no longer exists in Google Photos, so they went to your "
                    + "library only. Choose another album in Options.";
        }
        if (failed > 0) {
            detail += "\n" + (failed == 1 ? "1 file" : failed + " files")
                    + " could not be uploaded and will be tried again next time.";
        }
        return Status.message(failed > 0 || albumProblem != null ? Status.WARNING : Status.OK, "All done", detail,
                HINT);
    }

    static Status quota(UploadJob.Counts created, UploadJob.Counts pending) {
        return Status.message(Status.WARNING, "Google's daily upload limit reached",
                ofPending(created, pending) + " uploaded. The rest will upload the next time you open the app.", HINT);
    }

    static Status lowBattery(UploadJob.Counts created, UploadJob.Counts pending) {
        return Status.message(Status.WARNING, "Battery low",
                ofPending(created, pending) + " uploaded. Charge the camera and open the app again to "
                        + "upload the rest.",
                HINT);
    }

    static Status interrupted(UploadJob.Counts created, UploadJob.Counts pending) {
        return Status.message(Status.ERROR, "Upload interrupted",
                "The connection was lost. " + ofPending(created, pending)
                        + " uploaded; the rest will upload the next time you open the app (large videos "
                        + "continue where they stopped).",
                HINT);
    }

    /** Same screen with the automatic-close countdown in the hint line. */
    static Status closingIn(Status status, int seconds) {
        return status.withHint("Closing in " + seconds + " s        OK  Options        MENU  Exit now");
    }

    /** "3 photos", "1 video", "3 photos and 1 video". */
    static String items(int photos, int videos) {
        String p = photos == 1 ? "1 photo" : photos + " photos";
        String v = videos == 1 ? "1 video" : videos + " videos";
        if (videos == 0) {
            return p;
        }
        return photos == 0 ? v : p + " and " + v;
    }

    /** "4 of 10 photos", "4 of 10 files" when photos and videos are mixed. */
    private static String ofPending(UploadJob.Counts created, UploadJob.Counts pending) {
        String noun = pending.videos == 0 ? "photos" : pending.photos == 0 ? "videos" : "files";
        return created.total() + " of " + pending.total() + " " + noun;
    }

    static String timeLeft(double seconds) {
        if (seconds < 60) {
            return "less than a minute left";
        }
        long minutes = Math.round(seconds / 60);
        if (minutes < 90) {
            return "about " + minutes + " min left";
        }
        return "about " + (minutes / 60) + " h " + (minutes % 60) + " min left";
    }
}
