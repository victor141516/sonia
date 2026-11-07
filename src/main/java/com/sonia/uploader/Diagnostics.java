package com.sonia.uploader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Crash-safe files on the memory card. Every write opens, appends, fsyncs and closes: on this
 * camera's exFAT card an open file's size is only committed on close, and the whole Android side
 * can go down with the app, so anything less loses the lines that matter.
 */
final class Diagnostics {
    private static final long MAX_LOG_BYTES = 1024 * 1024;

    private Diagnostics() {}

    static void installCrashHandler(final File dir) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                StringWriter trace = new StringWriter();
                e.printStackTrace(new PrintWriter(trace));
                append(new File(dir, "CRASH.TXT"),
                        "---- " + System.currentTimeMillis() + " thread " + thread.getName() + "\r\n" + trace + "\r\n");
                if (previous != null) {
                    previous.uncaughtException(thread, e);
                }
            }
        });
    }

    /** Starts a fresh LOG.TXT when the old one is over 1 MB (keeps one previous file as LOG1.TXT). */
    static void rotate(File log) {
        if (log.length() > MAX_LOG_BYTES) {
            File old = new File(log.getParentFile(), "LOG1.TXT");
            old.delete();
            log.renameTo(old);
        }
    }

    /** Appends, fsyncs and closes, so the text survives a crash of the whole camera. */
    static void append(File file, String text) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, true);
            out.write(text.getBytes("UTF-8"));
            out.getFD().sync();
        } catch (IOException ignored) {
            // Nowhere left to report to.
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Nothing useful to do.
                }
            }
        }
    }
}
