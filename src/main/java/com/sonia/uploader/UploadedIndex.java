package com.sonia.uploader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Files already in Google Photos, one key per line in an append-only file. A key is the path
 * below a base folder (DCIM for photos, the card for videos) plus size and modification time, so a
 * new file that reuses an old name (the camera's counter wraps at 9999) is still uploaded.
 */
final class UploadedIndex {
    private final File file;
    private final Set<String> keys = new HashSet<String>();

    UploadedIndex(File file) throws IOException {
        this.file = file;
        if (file.exists()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 0) {
                        keys.add(line);
                    }
                }
            } finally {
                reader.close();
            }
        }
    }

    static String key(File dcim, File photo) {
        String relative = photo.getPath().substring(dcim.getPath().length() + 1);
        return relative + "|" + photo.length() + "|" + photo.lastModified();
    }

    synchronized boolean contains(String key) {
        return keys.contains(key);
    }

    synchronized int size() {
        return keys.size();
    }

    /** Appends and fsyncs before returning: a recorded photo must never be uploaded twice. */
    synchronized void addAll(List<String> newKeys) throws IOException {
        if (newKeys.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (String key : newKeys) {
            text.append(key).append('\n');
        }
        FileOutputStream out = new FileOutputStream(file, true);
        try {
            out.write(text.toString().getBytes("UTF-8"));
            out.getFD().sync();
        } finally {
            out.close();
        }
        keys.addAll(newKeys);
    }
}
