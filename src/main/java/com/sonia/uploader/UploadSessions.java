package com.sonia.uploader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Open resumable upload sessions (Google keeps them 7 days), keyed like {@link UploadedIndex}, so
 * a large video interrupted by a lost connection or a closed app continues where it stopped.
 * Kept in the app's private storage next to the index.
 */
final class UploadSessions {
    /** A session URL and the chunk alignment Google asked for. */
    static final class Session {
        final String url;
        final long granularity;

        Session(String url, long granularity) {
            this.url = url;
            this.granularity = granularity;
        }
    }

    private final File file;
    private final Properties props = new Properties();

    UploadSessions(File file) throws IOException {
        this.file = file;
        if (file.exists()) {
            FileInputStream in = new FileInputStream(file);
            try {
                props.load(in);
            } finally {
                in.close();
            }
        }
    }

    synchronized Session get(String key) {
        String value = props.getProperty(key);
        int space = value == null ? -1 : value.indexOf(' ');
        if (space <= 0) {
            return null;
        }
        try {
            return new Session(value.substring(space + 1), Long.parseLong(value.substring(0, space)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    synchronized void put(String key, Session session) throws IOException {
        props.setProperty(key, session.granularity + " " + session.url);
        save();
    }

    synchronized void remove(String key) throws IOException {
        if (props.remove(key) != null) {
            save();
        }
    }

    /** Write-then-rename so a crash never leaves a half-written file. */
    private void save() throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            props.store(out, null);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("cannot replace " + file);
        }
    }
}
