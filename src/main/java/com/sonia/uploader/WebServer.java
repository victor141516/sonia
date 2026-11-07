package com.sonia.uploader;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.sonia.uploader.core.Log;

/**
 * Configuration page served by the camera on the local network: status, Google OAuth client
 * (pasted JSON) and redirect address, Google sign-in (open Google, then paste back the
 * redirect address) and sign-out, and the upload options (RAW, videos, album; new albums).
 * Secrets are never sent back to the browser.
 */
final class WebServer extends Thread {
    /** Implemented by the activity. */
    interface Controller {
        String headline();

        String detail();

        Settings settings();

        /** Starts a sign-in attempt; returns the Google URL to open. */
        String beginSignIn();

        /** Completes sign-in with what the user pasted; returns a message for the page. */
        String finishSignIn(String pasted);

        /** Signed in, but before version 1.3, without the permission to list albums. */
        boolean albumsNeedSignIn();

        /** Creates an album in Google Photos and selects it; returns a message for the page. */
        String createAlbum(String title);

        /** Called after any settings change, to wake the upload worker and refresh the camera screen. */
        void settingsChanged();
    }

    private static final int MAX_BODY = 16 * 1024;

    private final ServerSocket serverSocket;
    private final Controller controller;
    private final Log log;
    private volatile boolean closed;
    private volatile String flash = "";

    WebServer(int port, Controller controller, Log log) throws IOException {
        super("web");
        this.serverSocket = new ServerSocket(port);
        this.controller = controller;
        this.log = log;
        setDaemon(true);
    }

    void shutdown() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    @Override
    public void run() {
        while (!closed) {
            final Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (!closed) {
                    log.log("web: accept " + e);
                }
                return;
            }
            // One thread per connection: browsers open speculative connections that never send a
            // request, and serving serially left the page frozen until they timed out (15 s).
            // Changes are serialised in post().
            Thread handler = new Thread("web-client") {
                @Override
                public void run() {
                    handle(client);
                }
            };
            handler.setDaemon(true);
            handler.start();
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            String[] request = readLine(in).split(" ");
            int contentLength = 0;
            String line;
            while ((line = readLine(in)).length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            String method = request.length > 0 ? request[0] : "";
            String path = request.length > 1 ? request[1] : "/";
            OutputStream out = client.getOutputStream();
            if (method.equals("POST")) {
                if (contentLength > MAX_BODY) {
                    respond(out, "413 Payload Too Large", "text/plain", "too large");
                    return;
                }
                Map<String, String> form = parseForm(readBody(in, contentLength));
                post(path, form);
                redirect(out, "/");
            } else if (path.equals("/login")) {
                redirect(out, controller.beginSignIn());
            } else if (path.equals("/")) {
                respond(out, "200 OK", "text/html; charset=utf-8", page());
            } else {
                respond(out, "404 Not Found", "text/plain", "not found");
            }
        } catch (java.net.SocketTimeoutException e) {
            // A speculative browser connection that never sent a request: nothing to report.
        } catch (Exception e) {
            log.log("web: " + e);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // Nothing useful to do.
            }
        }
    }

    private synchronized void post(String path, Map<String, String> form) throws IOException {
        Settings settings = controller.settings();
        if (path.equals("/client")) {
            saveClient(settings, value(form, "client_json"), value(form, "redirect_uri"));
        } else if (path.equals("/redirect")) {
            String redirect = value(form, "redirect_uri");
            if (!validRedirect(redirect)) {
                flash = "The address must start with http:// or https://";
                return;
            }
            settings.setRedirectUri(redirect);
            flash = "Redirect address saved.";
            log.log("web: redirect address changed");
        } else if (path.equals("/code")) {
            flash = controller.finishSignIn(value(form, "pasted"));
        } else if (path.equals("/logout")) {
            settings.clearRefreshToken();
            flash = "Signed out on this camera.";
            log.log("web: signed out");
        } else if (path.equals("/options")) {
            settings.setOptions(form.containsKey("raw"), form.containsKey("videos"), value(form, "album"));
            flash = "Options saved.";
            log.log("web: options raw=" + settings.uploadRaw() + " videos=" + settings.uploadVideos() + " album="
                    + (settings.albumId().length() > 0 ? "set" : "none"));
        } else if (path.equals("/album")) {
            flash = controller.createAlbum(value(form, "title"));
        }
        controller.settingsChanged();
    }

    /** Accepts the JSON downloaded from Google Cloud ("installed" or "web" client). */
    private void saveClient(Settings settings, String json, String redirect) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject client = root.has("installed") ? root.getJSONObject("installed") : root.optJSONObject("web");
            if (client == null) {
                client = root;
            }
            String id = client.optString("client_id", "");
            String secret = client.optString("client_secret", "");
            if (redirect.length() == 0) {
                JSONArray uris = client.optJSONArray("redirect_uris");
                redirect = uris != null && uris.length() > 0 ? uris.optString(0, "") : "";
            }
            if (id.length() == 0 || secret.length() == 0) {
                flash = "That JSON does not contain client_id and client_secret.";
                return;
            }
            if (!validRedirect(redirect)) {
                flash = "A valid redirect address is missing (the JSON has none): enter one, "
                        + "e.g. http://localhost";
                return;
            }
            settings.setClient(id, secret, redirect);
            flash = "Client saved. Now sign in to Google (step 2).";
            log.log("web: OAuth client updated");
        } catch (JSONException e) {
            flash = "That is not valid JSON: " + e.getMessage();
        }
    }

    private static boolean validRedirect(String uri) {
        return uri.startsWith("http://") || uri.startsWith("https://");
    }

    private String page() {
        Settings settings = controller.settings();
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=en><head><meta charset=utf-8>")
                .append("<meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>Upload to Google Photos</title><style>")
                .append("body{font-family:sans-serif;max-width:40em;margin:1em auto;padding:0 1em;line-height:1.4}")
                .append("input,textarea,select{width:100%;box-sizing:border-box;font-size:1em;padding:.4em}")
                .append("input[type=checkbox]{width:auto;margin-right:.5em}label.check{display:block;margin:.4em 0}")
                .append("button,.button{font-size:1em;padding:.5em 1em;margin-top:.5em}")
                .append("section{border:1px solid #ccc;border-radius:8px;padding:.5em 1em;margin:1em 0}")
                .append(".ok{color:#070}.flash{background:#ffd;padding:.5em;border-radius:6px}")
                .append("</style></head><body><h1>Camera &rarr; Google Photos</h1>");
        html.append("<section><h2>Status</h2><p><b>").append(escape(controller.headline())).append("</b><br>")
                .append(escape(controller.detail())).append("</p><a href='/'>Refresh</a></section>");
        if (flash.length() > 0) {
            html.append("<p class=flash>").append(escape(flash)).append("</p>");
            flash = "";
        }

        html.append("<section><h2>1. Google OAuth client</h2>");
        if (settings.hasClient()) {
            html.append("<p class=ok>Saved: client ID <code>").append(escape(shortId(settings.clientId())))
                    .append("</code>, redirect address <code>").append(escape(settings.redirectUri())).append("</code>.</p>")
                    .append("<form method=post action=/redirect><label>Change the redirect address")
                    .append("<input name=redirect_uri value='").append(escape(settings.redirectUri()))
                    .append("'></label><button>Save address</button></form>")
                    .append("<details><summary>Replace the client (paste another JSON)</summary>");
        }
        html.append("<form method=post action=/client>")
                .append("<label>Contents of the client_secret_....json file downloaded from Google Cloud")
                .append("<textarea name=client_json rows=6 autocomplete=off spellcheck=false></textarea></label>")
                .append("<label>Redirect address (empty = the first one in the JSON, e.g. http://localhost)")
                .append("<input name=redirect_uri autocomplete=off></label>")
                .append("<button>Save</button></form>");
        if (settings.hasClient()) {
            html.append("</details>");
        }
        html.append("<p><small>Stored only inside the camera (the app's private storage); ")
                .append("this page never shows the secret.</small></p></section>");

        html.append("<section><h2>2. Sign in to Google</h2>");
        if (!settings.hasClient()) {
            html.append("<p>Save the OAuth client first.</p>");
        } else if (settings.isLoggedIn() && controller.albumsNeedSignIn()) {
            html.append("<p class=ok>Signed in. The camera uploads new photos by itself.</p>")
                    .append("<p>To use albums, Google needs one more permission: sign in again.</p>");
            signInSteps(html, settings);
            html.append("<form method=post action=/logout><button>Sign out</button></form>");
        } else if (settings.isLoggedIn()) {
            html.append("<p class=ok>Signed in. The camera uploads new photos by itself.</p>")
                    .append("<form method=post action=/logout><button>Sign out</button></form>");
        } else {
            signInSteps(html, settings);
        }
        html.append("</section>");

        options(html, settings);
        html.append("</body></html>");
        return html.toString();
    }

    private static void signInSteps(StringBuilder html, Settings settings) {
        html.append("<ol><li><a class=button href='/login' target=_blank rel=noopener>Open Google</a> ")
                .append("and allow access. If Google warns that the app is not verified, choose Advanced &rarr; Go to the app.</li>")
                .append("<li>At the end the browser tries to open <code>")
                .append(escape(settings.redirectUri()))
                .append("...</code> and says it cannot connect. That is expected.</li>")
                .append("<li>Copy that whole address from the browser's address bar and paste it here:</li></ol>")
                .append("<form method=post action=/code><textarea name=pasted rows=4></textarea>")
                .append("<button>Finish signing in</button></form>");
    }

    /** Section 3: what to upload and where; also changeable on the camera (OK button). */
    private void options(StringBuilder html, Settings settings) {
        html.append("<section><h2>3. Upload options</h2><form method=post action=/options>")
                .append("<label class=check><input type=checkbox name=raw").append(settings.uploadRaw() ? " checked" : "")
                .append(">Also upload RAW files (.ARW)</label>")
                .append("<p><small>Google Photos keeps the original RAW file: you can download it later and ")
                .append("develop it with your own software. Each one takes about 25 MB of your Google storage.</small></p>")
                .append("<label class=check><input type=checkbox name=videos")
                .append(settings.uploadVideos() ? " checked" : "").append(">Also upload videos (MP4 and AVCHD)</label>")
                .append("<p><small>Long videos take a while; if the connection drops they continue where they ")
                .append("stopped.</small></p>");
        boolean albumsUsable = settings.isLoggedIn() && !controller.albumsNeedSignIn();
        List<Settings.Album> albums = settings.albums();
        html.append("<label>Album<select name=album").append(albumsUsable ? "" : " disabled").append(">")
                .append("<option value=''>No album (just the library)</option>");
        for (Settings.Album album : albums) {
            html.append("<option value='").append(escape(album.id)).append("'")
                    .append(album.id.equals(settings.albumId()) ? " selected" : "").append(">")
                    .append(escape(album.title)).append("</option>");
        }
        html.append("</select></label>");
        if (!albumsUsable) {
            // A disabled select is not submitted: keep the current album.
            html.append("<input type=hidden name=album value='").append(escape(settings.albumId())).append("'>");
        }
        html.append("<button>Save options</button></form>");

        if (!settings.isLoggedIn()) {
            html.append("<p><small>Sign in to Google (step 2) to use albums.</small></p>");
        } else if (!albumsUsable) {
            html.append("<p><small>To use albums, sign in again (step 2).</small></p>");
        } else {
            html.append("<p><small>Google only lets this app add photos to albums it created, so the list shows ")
                    .append("those. Create a new one here:</small></p>")
                    .append("<form method=post action=/album><label>New album name")
                    .append("<input name=title maxlength=500 autocomplete=off></label>")
                    .append("<button>Create album</button></form>");
        }
        html.append("<p><small>These options can also be changed on the camera: press OK.</small></p></section>");
    }

    private static String shortId(String clientId) {
        int dash = clientId.indexOf('-');
        return dash > 0 ? clientId.substring(0, dash) + "-..." : clientId;
    }

    private static String value(Map<String, String> form, String name) {
        String v = form.get(name);
        return v == null ? "" : v.trim();
    }

    private static Map<String, String> parseForm(String body) throws IOException {
        Map<String, String> form = new HashMap<String, String>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            }
        }
        return form;
    }

    private static String readBody(InputStream in, int length) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (body.size() < length) {
            int n = in.read(buffer, 0, Math.min(buffer.length, length - body.size()));
            if (n < 0) {
                break;
            }
            body.write(buffer, 0, n);
        }
        return body.toString("UTF-8");
    }

    private static void redirect(OutputStream out, String location) throws IOException {
        out.write(("HTTP/1.0 303 See Other\r\nLocation: " + location + "\r\nContent-Length: 0\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.flush();
    }

    private static void respond(OutputStream out, String status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        out.write(("HTTP/1.0 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + bytes.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
        out.write(bytes);
        out.flush();
    }

    static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (c != '\r') {
                sb.append((char) c);
            }
            if (sb.length() > 8192) {
                throw new IOException("line too long");
            }
        }
        return sb.toString();
    }
}
