package com.sonia.uploader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.sonia.uploader.core.Connection;
import com.sonia.uploader.core.Log;
import com.sonia.uploader.core.MiniHttp;

/**
 * Google OAuth token endpoint and Photos Library API: upload bytes (simple, or resumable for
 * large files), mediaItems:batchCreate, and the albums this app created (list, create).
 * Never logs tokens or secrets.
 */
final class GooglePhotos {
    static final String TOKEN_HOST = "oauth2.googleapis.com";
    static final String PHOTOS_HOST = "photoslibrary.googleapis.com";
    /** Google's limit per batchCreate call. */
    static final int MAX_BATCH = 50;
    /** Larger files use a resumable session, so a lost connection does not restart them from zero. */
    static final long SIMPLE_UPLOAD_MAX = 32L * 1024 * 1024;
    /** Bytes per request of a resumable upload (rounded down to Google's granularity). */
    static final long CHUNK_BYTES = 16L * 1024 * 1024;

    /** Sign-in needed: no refresh token, or Google revoked it. */
    static final class AuthException extends IOException {
        AuthException(String message) {
            super(message);
        }
    }

    /** HTTP 429: daily request quota of the Google Cloud project used up. */
    static final class QuotaException extends IOException {
        QuotaException(String message) {
            super(message);
        }
    }

    /** Any other HTTP error; {@link #retryable} for 408 and 5xx. */
    static final class HttpException extends IOException {
        final int status;

        HttpException(String what, MiniHttp.Response response) {
            super(what + ": HTTP " + response.status + " " + snippet(response));
            this.status = response.status;
        }

        boolean retryable() {
            return status == 408 || status >= 500;
        }
    }

    /** A request body that can be sent again (after a 401 the request is repeated). */
    private interface Body {
        InputStream open() throws IOException;
    }

    private static final Body EMPTY = new Body() {
        public InputStream open() {
            return new ByteArrayInputStream(new byte[0]);
        }
    };

    private final Connection.Connector tls;
    private final Settings settings;
    private final UploadSessions sessions;
    private final Log log;
    private String accessToken;
    private long accessExpiresAtMs;
    /** Scopes granted to the current access token; null until one was obtained. */
    private String grantedScope;

    GooglePhotos(Connection.Connector tls, Settings settings, UploadSessions sessions, Log log) {
        this.tls = tls;
        this.settings = settings;
        this.sessions = sessions;
        this.log = log;
    }

    /** Exchanges the pasted authorization code for a refresh token and stores it. */
    synchronized void signIn(String code, String verifier) throws IOException {
        JSONObject json = tokenRequest("grant_type=authorization_code"
                + "&code=" + OAuth.encode(code)
                + "&code_verifier=" + OAuth.encode(verifier)
                + "&redirect_uri=" + OAuth.encode(settings.redirectUri()));
        String refresh = json.optString("refresh_token", "");
        if (refresh.length() == 0) {
            throw new IOException("Google did not return a refresh token");
        }
        settings.setRefreshToken(refresh);
        rememberAccessToken(json);
        log.log("google: signed in");
    }

    /**
     * Uploads the file's bytes; returns the upload token for {@link #createMediaItems}.
     * {@code key} identifies the file across app runs, to resume an interrupted large upload.
     */
    String upload(File file, String mimeType, String key, MiniHttp.Progress progress) throws IOException {
        if (file.length() <= SIMPLE_UPLOAD_MAX) {
            return uploadSimple(file, mimeType, progress);
        }
        try {
            return uploadResumable(file, mimeType, key, progress);
        } catch (HttpException e) {
            if (!e.retryable()) {
                // The session is unusable (expired, or Google rejected an offset): start over next time.
                sessions.remove(key);
            }
            throw e;
        }
    }

    private String uploadSimple(final File file, String mimeType, MiniHttp.Progress progress) throws IOException {
        MiniHttp.Response r = authorized(PHOTOS_HOST, "POST", "/v1/uploads", new String[] {
            "Content-Type", "application/octet-stream",
            "X-Goog-Upload-Content-Type", mimeType,
            "X-Goog-Upload-Protocol", "raw",
        }, new Body() {
            public InputStream open() throws IOException {
                return new FileInputStream(file);
            }
        }, file.length(), progress, 4096);
        check("upload", r);
        return uploadToken(r);
    }

    private String uploadResumable(final File file, String mimeType, String key, final MiniHttp.Progress progress)
            throws IOException {
        final long size = file.length();
        UploadSessions.Session session = sessions.get(key);
        long offset = 0;
        if (session != null) {
            offset = received(session);
            if (offset < 0) {
                sessions.remove(key);
                session = null;
                offset = 0;
            } else {
                log.log("resuming " + file.getName() + " at " + UploadJob.megabytes(offset) + " MB");
            }
        }
        if (session == null) {
            session = startSession(mimeType, size);
            sessions.put(key, session);
        }
        String[] target = splitUrl(session.url);
        long chunk = Math.max(session.granularity, CHUNK_BYTES / session.granularity * session.granularity);
        while (true) {
            final long start = offset;
            final long length = Math.min(chunk, size - start);
            boolean last = start + length == size;
            MiniHttp.Response r = authorized(target[0], "POST", target[1], new String[] {
                "X-Goog-Upload-Command", last ? "upload, finalize" : "upload",
                "X-Goog-Upload-Offset", Long.toString(start),
            }, new Body() {
                public InputStream open() throws IOException {
                    FileInputStream in = new FileInputStream(file);
                    in.getChannel().position(start);
                    return in;
                }
            }, length, progress == null ? null : new MiniHttp.Progress() {
                public void onSent(long bytes) {
                    progress.onSent(start + bytes);
                }
            }, 4096);
            check("upload chunk", r);
            if (last) {
                sessions.remove(key);
                return uploadToken(r);
            }
            offset = start + length;
        }
    }

    private UploadSessions.Session startSession(String mimeType, long size) throws IOException {
        MiniHttp.Response r = authorized(PHOTOS_HOST, "POST", "/v1/uploads", new String[] {
            "X-Goog-Upload-Command", "start",
            "X-Goog-Upload-Content-Type", mimeType,
            "X-Goog-Upload-Protocol", "resumable",
            "X-Goog-Upload-Raw-Size", Long.toString(size),
        }, EMPTY, 0, null, 4096);
        check("upload start", r);
        String url = r.header("X-Goog-Upload-URL");
        if (url == null || !url.startsWith("https://")) {
            throw new IOException("upload start: no session URL");
        }
        long granularity = 256 * 1024;
        try {
            String g = r.header("X-Goog-Upload-Chunk-Granularity");
            if (g != null && Long.parseLong(g.trim()) > 0) {
                granularity = Long.parseLong(g.trim());
            }
        } catch (NumberFormatException e) {
            // Keep the documented default.
        }
        return new UploadSessions.Session(url, granularity);
    }

    /** Bytes Google already holds for this session, or -1 if the session can no longer be used. */
    private long received(UploadSessions.Session session) throws IOException {
        String[] target = splitUrl(session.url);
        MiniHttp.Response r = authorized(target[0], "POST", target[1],
                new String[] {"X-Goog-Upload-Command", "query"}, EMPTY, 0, null, 4096);
        if (r.status == 429) {
            check("upload query", r);
        }
        if (r.status >= 500) {
            throw new HttpException("upload query", r);
        }
        String status = r.header("X-Goog-Upload-Status");
        String bytes = r.header("X-Goog-Upload-Size-Received");
        if (r.status != 200 || !"active".equals(status) || bytes == null) {
            return -1;
        }
        try {
            return Long.parseLong(bytes.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** {"https://host/path?query"} to {host, "/path?query"}. */
    private static String[] splitUrl(String url) throws IOException {
        if (!url.startsWith("https://")) {
            throw new IOException("unexpected upload URL");
        }
        int slash = url.indexOf('/', 8);
        return slash < 0 ? new String[] {url.substring(8), "/"}
                : new String[] {url.substring(8, slash), url.substring(slash)};
    }

    private static String uploadToken(MiniHttp.Response r) throws IOException {
        String token = r.bodyText().trim();
        if (token.length() == 0) {
            throw new IOException("upload: empty response");
        }
        return token;
    }

    /**
     * Creates the library items for uploaded tokens (at most {@link #MAX_BATCH}), inside the album
     * if {@code albumId} is not empty. Returns, per input item, whether Google created it.
     */
    boolean[] createMediaItems(List<String> uploadTokens, List<String> fileNames, String albumId)
            throws IOException {
        try {
            JSONArray items = new JSONArray();
            for (int i = 0; i < uploadTokens.size(); i++) {
                items.put(new JSONObject().put("simpleMediaItem",
                        new JSONObject().put("uploadToken", uploadTokens.get(i)).put("fileName", fileNames.get(i))));
            }
            JSONObject request = new JSONObject().put("newMediaItems", items);
            if (albumId.length() > 0) {
                request.put("albumId", albumId);
            }
            MiniHttp.Response r = json("POST", "/v1/mediaItems:batchCreate", request, 1024 * 1024);
            check("batchCreate", r);
            return results(new JSONObject(r.bodyText()), uploadTokens);
        } catch (JSONException e) {
            throw new IOException("batchCreate: JSON " + e.getMessage());
        }
    }

    private static boolean[] results(JSONObject response, List<String> uploadTokens) throws JSONException {
        boolean[] created = new boolean[uploadTokens.size()];
        JSONArray results = response.optJSONArray("newMediaItemResults");
        if (results == null) {
            return created;
        }
        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.getJSONObject(i);
            JSONObject status = result.optJSONObject("status");
            boolean ok = result.has("mediaItem") && (status == null || status.optInt("code", 0) == 0);
            int index = uploadTokens.indexOf(result.optString("uploadToken", ""));
            if (index >= 0) {
                created[index] = ok;
            }
        }
        return created;
    }

    /**
     * Whether the sign-in allows listing albums. Sign-ins made before version 1.3 only granted
     * uploading; known after {@link #checkSignIn}.
     */
    synchronized boolean canListAlbums() {
        return grantedScope != null && (" " + grantedScope + " ").contains(" " + OAuth.SCOPE_READ_APP_CREATED + " ");
    }

    /** Whether {@link #canListAlbums} is known yet (after the first token request). */
    synchronized boolean scopeKnown() {
        return grantedScope != null;
    }

    /** All albums this app created (the only ones Google lets it see and add to). */
    List<Settings.Album> listAlbums() throws IOException {
        List<Settings.Album> albums = new ArrayList<Settings.Album>();
        String pageToken = "";
        try {
            do {
                String path = "/v1/albums?pageSize=50&excludeNonAppCreatedData=true"
                        + (pageToken.length() > 0 ? "&pageToken=" + OAuth.encode(pageToken) : "");
                MiniHttp.Response r = authorized(PHOTOS_HOST, "GET", path, new String[0], null, 0, null,
                        1024 * 1024);
                check("albums", r);
                JSONObject page = new JSONObject(r.bodyText());
                JSONArray list = page.optJSONArray("albums");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject album = list.getJSONObject(i);
                    if (album.optString("id", "").length() > 0) {
                        albums.add(new Settings.Album(album.getString("id"), album.optString("title", "")));
                    }
                }
                pageToken = page.optString("nextPageToken", "");
            } while (pageToken.length() > 0);
        } catch (JSONException e) {
            throw new IOException("albums: JSON " + e.getMessage());
        }
        return albums;
    }

    Settings.Album createAlbum(String title) throws IOException {
        try {
            JSONObject request = new JSONObject().put("album", new JSONObject().put("title", title));
            MiniHttp.Response r = json("POST", "/v1/albums", request, 64 * 1024);
            check("create album", r);
            JSONObject album = new JSONObject(r.bodyText());
            String id = album.optString("id", "");
            if (id.length() == 0) {
                throw new IOException("create album: no id in the response");
            }
            return new Settings.Album(id, album.optString("title", title));
        } catch (JSONException e) {
            throw new IOException("create album: JSON " + e.getMessage());
        }
    }

    /**
     * Confirms the stored sign-in still works by obtaining an access token (one round trip).
     * Throws {@link AuthException} if the user must sign in again.
     */
    void checkSignIn() throws IOException {
        accessToken();
    }

    private MiniHttp.Response json(String method, String path, JSONObject request, int keepBody)
            throws IOException {
        final byte[] body = request.toString().getBytes("UTF-8");
        return authorized(PHOTOS_HOST, method, path, new String[] {"Content-Type", "application/json"},
                new Body() {
                    public InputStream open() {
                        return new ByteArrayInputStream(body);
                    }
                }, body.length, null, keepBody);
    }

    /** Sends with the access token; on 401 gets a new token and sends once more. */
    private MiniHttp.Response authorized(String host, String method, String path, String[] headers, Body body,
            long length, MiniHttp.Progress progress, int keepBody) throws IOException {
        for (int attempt = 0; ; attempt++) {
            String[] all = new String[headers.length + 2];
            all[0] = "Authorization";
            all[1] = "Bearer " + accessToken();
            System.arraycopy(headers, 0, all, 2, headers.length);
            InputStream in = body == null ? null : body.open();
            MiniHttp.Response r;
            try {
                r = request(host, method, path, all, in, length, progress, keepBody);
            } finally {
                if (in != null) {
                    in.close();
                }
            }
            if (r.status == 401 && attempt == 0) {
                invalidateAccessToken();
                continue;
            }
            return r;
        }
    }

    private synchronized void invalidateAccessToken() {
        accessToken = null;
    }

    private synchronized String accessToken() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < accessExpiresAtMs) {
            return accessToken;
        }
        if (!settings.isLoggedIn()) {
            throw new AuthException("not signed in to Google");
        }
        rememberAccessToken(tokenRequest("grant_type=refresh_token&refresh_token="
                + OAuth.encode(settings.refreshToken())));
        return accessToken;
    }

    private void rememberAccessToken(JSONObject json) throws IOException {
        String token = json.optString("access_token", "");
        if (token.length() == 0) {
            throw new IOException("Google did not return an access token");
        }
        accessToken = token;
        grantedScope = json.optString("scope", "");
        // Local wall time is only used for this relative deadline, so the camera's 1970 clock is fine.
        accessExpiresAtMs = System.currentTimeMillis() + (json.optLong("expires_in", 3600) - 120) * 1000L;
    }

    private JSONObject tokenRequest(String grant) throws IOException {
        String form = "client_id=" + OAuth.encode(settings.clientId())
                + "&client_secret=" + OAuth.encode(settings.clientSecret()) + "&" + grant;
        byte[] body = form.getBytes("UTF-8");
        MiniHttp.Response r = request(TOKEN_HOST, "POST", "/token",
                new String[] {"Content-Type", "application/x-www-form-urlencoded"}, new ByteArrayInputStream(body),
                body.length, null, 16 * 1024);
        if (r.status == 400 || r.status == 401) {
            String text = r.bodyText();
            if (text.contains("invalid_grant")) {
                settings.clearRefreshToken();
                throw new AuthException("Google no longer accepts this sign-in (code expired or already used, or access "
                        + "revoked); please sign in again");
            }
            if (text.contains("invalid_client")) {
                throw new AuthException("wrong client ID or client secret");
            }
        }
        check("token", r);
        try {
            return new JSONObject(r.bodyText());
        } catch (JSONException e) {
            throw new IOException("token: JSON " + e.getMessage());
        }
    }

    private MiniHttp.Response request(String host, String method, String path, String[] headers, InputStream body,
            long length, MiniHttp.Progress progress, int keepBody) throws IOException {
        Connection connection = tls.open(host);
        try {
            return MiniHttp.exchange(connection.in(), connection.out(), method, host, path, headers, body, length,
                    progress, keepBody);
        } finally {
            connection.close();
        }
    }

    private static void check(String what, MiniHttp.Response r) throws IOException {
        if (r.status == 200) {
            return;
        }
        if (r.status == 429) {
            throw new QuotaException(what + ": Google daily quota used up (HTTP 429)");
        }
        throw new HttpException(what, r);
    }

    private static String snippet(MiniHttp.Response r) {
        String text = r.bodyText().replace('\n', ' ');
        return text.substring(0, Math.min(200, text.length()));
    }
}
