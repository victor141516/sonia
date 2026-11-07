package com.sonia.uploader.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Just enough HTTP/1.1 (one request per connection, streamed body, chunked responses). */
public final class MiniHttp {
    private static final int CHUNK = 16 * 1024;

    private MiniHttp() {}

    public interface Progress {
        void onSent(long bytes);
    }

    public static final class Response {
        public int status;
        public String statusLine;
        public final Map<String, String> headers = new HashMap<String, String>();
        /** First bytes of the body, up to the requested limit. */
        public byte[] body;
        /** Total body length actually received. */
        public long bodyLength;

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        public String bodyText() {
            try {
                return new String(body, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Sends one request and reads the response. {@code extraHeaders} is name/value pairs.
     * {@code body} may be null; otherwise exactly {@code bodyLength} bytes are streamed from it.
     */
    public static Response exchange(InputStream rawIn, OutputStream rawOut, String method, String host, String path,
            String[] extraHeaders, InputStream body, long bodyLength, Progress progress, int keepBodyBytes)
            throws IOException {
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(host).append("\r\n");
        head.append("User-Agent: sonia-uploader/1\r\n");
        head.append("Connection: close\r\n");
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            head.append(extraHeaders[i]).append(": ").append(extraHeaders[i + 1]).append("\r\n");
        }
        if (body != null) {
            head.append("Content-Length: ").append(bodyLength).append("\r\n");
        }
        head.append("\r\n");

        OutputStream out = new BufferedOutputStream(rawOut, CHUNK);
        out.write(head.toString().getBytes("US-ASCII"));
        if (body != null) {
            byte[] buffer = new byte[CHUNK];
            long sent = 0;
            while (sent < bodyLength) {
                int n = body.read(buffer, 0, (int) Math.min(buffer.length, bodyLength - sent));
                if (n < 0) {
                    throw new EOFException("request body ended after " + sent + " of " + bodyLength + " bytes");
                }
                out.write(buffer, 0, n);
                sent += n;
                if (progress != null) {
                    progress.onSent(sent);
                }
            }
        }
        out.flush();

        InputStream in = new BufferedInputStream(rawIn, CHUNK);
        Response response = new Response();
        response.statusLine = readLine(in);
        String[] parts = response.statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("bad status line: " + response.statusLine);
        }
        response.status = Integer.parseInt(parts[1]);
        String line;
        while ((line = readLine(in)).length() > 0) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                response.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }

        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        long total = 0;
        boolean noBody = "HEAD".equals(method) || response.status == 204 || response.status == 304
                || response.status / 100 == 1;
        if (!noBody) {
            String transfer = response.header("transfer-encoding");
            String length = response.header("content-length");
            if (transfer != null && transfer.toLowerCase(Locale.US).contains("chunked")) {
                while (true) {
                    String sizeLine = readLine(in);
                    int semi = sizeLine.indexOf(';');
                    long size = Long.parseLong((semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim(), 16);
                    if (size == 0) {
                        while (readLine(in).length() > 0) {
                            // Skip trailers.
                        }
                        break;
                    }
                    total += copy(in, kept, size, keepBodyBytes, total);
                    readLine(in);
                }
            } else if (length != null) {
                total = copy(in, kept, Long.parseLong(length), keepBodyBytes, 0);
            } else {
                total = copy(in, kept, Long.MAX_VALUE, keepBodyBytes, 0);
            }
        }
        response.body = kept.toByteArray();
        response.bodyLength = total;
        return response;
    }

    private static long copy(InputStream in, ByteArrayOutputStream kept, long limit, int keep, long alreadyKept)
            throws IOException {
        byte[] buffer = new byte[CHUNK];
        long copied = 0;
        while (copied < limit) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, limit - copied));
            if (n < 0) {
                if (limit == Long.MAX_VALUE) {
                    break;
                }
                throw new EOFException("response body truncated");
            }
            long room = keep - (alreadyKept + copied);
            if (room > 0) {
                kept.write(buffer, 0, (int) Math.min(room, n));
            }
            copied += n;
        }
        return copied;
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            if (sb.length() > 8192) {
                throw new IOException("header line too long");
            }
            sb.append((char) c);
        }
        if (sb.length() == 0) {
            throw new EOFException("connection closed");
        }
        return sb.toString();
    }
}
