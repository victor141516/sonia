package com.sonia.uploader.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** One open connection (plain TCP or TLS) carrying a single HTTP exchange. */
public interface Connection {
    InputStream in() throws IOException;

    OutputStream out() throws IOException;

    /** Protocol / cipher description for the log. */
    String describe();

    long handshakeMs();

    void close();

    /** Opens connections to a host using one transport (bundled TLS, Android TLS or plain HTTP). */
    interface Connector {
        Connection open(String host) throws IOException;
    }
}
