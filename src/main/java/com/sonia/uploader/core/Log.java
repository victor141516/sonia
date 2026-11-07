package com.sonia.uploader.core;

/** Sink for human-readable probe output. Implementations must be thread-safe. */
public interface Log {
    void log(String line);
}
