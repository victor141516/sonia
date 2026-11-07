package com.sonia.uploader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.sonia.uploader.core.Clock;
import com.sonia.uploader.core.Connection;

/**
 * TLS 1.2/1.3 in native code (mbedTLS, libsoniatls.so). On the a6000, Java crypto is ~50x slower
 * and the JIT crashes inside it; the camera's own OpenSSL is TLS 1.0 only and also crashed.
 * Certificates are verified natively against the bundled roots, with the time taken from
 * {@link Clock} (Sony TimeUtil: the Android clock on the camera reads 1970).
 * Only ChaCha20-Poly1305 is offered: 6.3 MB/s on the camera's CPU versus 2.4 MB/s for AES-GCM.
 */
final class NativeTls implements Connection {
    private static final int TIMEOUT_MS = 30000;

    static {
        System.loadLibrary("soniatls");
    }

    /** Initialises PSA crypto; returns a version line for the log. */
    static native String nativeInit();

    private static native void setTime(long epochSeconds);

    private static native long loadCa(byte[] nulTerminatedPem) throws IOException;

    /** C: Java_com_sonia_uploader_NativeTls_open(address, host, port, ca, timeoutMs). */
    private static native long open(String address, String host, int port, long ca, int timeoutMs)
            throws IOException;

    private static native int read(long handle, byte[] buffer, int off, int len) throws IOException;

    private static native void write(long handle, byte[] buffer, int off, int len) throws IOException;

    private static native String describe(long handle);

    private static native long handshakeMs(long handle);

    private static native void close(long handle);

    /** Reads the PEM bundle and appends the NUL terminator mbedTLS needs to detect PEM. */
    static long loadCa(InputStream pem) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = pem.read(buffer)) > 0) {
            bytes.write(buffer, 0, n);
        }
        bytes.write(0);
        return loadCa(bytes.toByteArray());
    }

    static Connector connector(final long ca, final Clock clock) {
        return new Connector() {
            @Override
            public Connection open(String host) throws IOException {
                setTime(clock.currentTimeMillis() / 1000);
                return new NativeTls(NativeTls.open(host, host, 443, ca, TIMEOUT_MS));
            }
        };
    }

    private long handle;
    private final InputStream in = new InputStream() {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            return len == 0 ? 0 : NativeTls.read(live(), buffer, off, len);
        }
    };
    private final OutputStream out = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int off, int len) throws IOException {
            NativeTls.write(live(), buffer, off, len);
        }
    };

    private NativeTls(long handle) {
        this.handle = handle;
    }

    private long live() throws IOException {
        if (handle == 0) {
            throw new IOException("connection closed");
        }
        return handle;
    }

    @Override
    public InputStream in() {
        return in;
    }

    @Override
    public OutputStream out() {
        return out;
    }

    @Override
    public String describe() {
        return handle == 0 ? "closed" : describe(handle);
    }

    @Override
    public long handshakeMs() {
        return handle == 0 ? 0 : handshakeMs(handle);
    }

    @Override
    public void close() {
        if (handle != 0) {
            close(handle);
            handle = 0;
        }
    }
}
