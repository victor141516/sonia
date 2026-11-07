package com.sonia.uploader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * OAuth client credentials, the refresh token, the upload options and the cached list of albums,
 * kept in the app's private storage (not on the memory card, which leaves the camera with every
 * card swap). Thread-safe: written by the web page and the camera's options screen, read by the
 * upload worker.
 */
final class Settings {
    /** An album this app created in Google Photos (the only kind the API lets it add to). */
    static final class Album {
        final String id;
        final String title;

        Album(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private static final String CLIENT_ID = "client_id";
    private static final String CLIENT_SECRET = "client_secret";
    private static final String REDIRECT_URI = "redirect_uri";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String UPLOAD_RAW = "upload_raw";
    private static final String UPLOAD_VIDEOS = "upload_videos";
    private static final String ALBUM_ID = "album_id";
    private static final String ALBUM_TITLE = "album_title";
    private static final String ALBUMS = "albums";
    private static final Comparator<Album> BY_TITLE = new Comparator<Album>() {
        @Override
        public int compare(Album a, Album b) {
            return a.title.compareToIgnoreCase(b.title);
        }
    };

    private final File file;
    private final Properties props = new Properties();

    Settings(File file) throws IOException {
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

    synchronized String clientId() {
        return props.getProperty(CLIENT_ID, "");
    }

    synchronized String redirectUri() {
        return props.getProperty(REDIRECT_URI, "");
    }

    synchronized String clientSecret() {
        return props.getProperty(CLIENT_SECRET, "");
    }

    synchronized String refreshToken() {
        return props.getProperty(REFRESH_TOKEN, "");
    }

    synchronized boolean hasClient() {
        return clientId().length() > 0 && clientSecret().length() > 0 && redirectUri().length() > 0;
    }

    synchronized boolean isLoggedIn() {
        return refreshToken().length() > 0;
    }

    /** New client credentials invalidate the refresh token, which belongs to the old client. */
    synchronized void setClient(String id, String secret, String redirectUri) throws IOException {
        props.setProperty(CLIENT_ID, id.trim());
        props.setProperty(CLIENT_SECRET, secret.trim());
        props.setProperty(REDIRECT_URI, redirectUri.trim());
        props.remove(REFRESH_TOKEN);
        save();
    }

    /** The refresh token stays valid: it is bound to the client, not to the redirect address. */
    synchronized void setRedirectUri(String redirectUri) throws IOException {
        props.setProperty(REDIRECT_URI, redirectUri.trim());
        save();
    }

    synchronized void setRefreshToken(String token) throws IOException {
        props.setProperty(REFRESH_TOKEN, token);
        save();
    }

    synchronized void clearRefreshToken() throws IOException {
        props.remove(REFRESH_TOKEN);
        save();
    }

    synchronized boolean uploadRaw() {
        return Boolean.parseBoolean(props.getProperty(UPLOAD_RAW, "false"));
    }

    synchronized boolean uploadVideos() {
        return Boolean.parseBoolean(props.getProperty(UPLOAD_VIDEOS, "false"));
    }

    /** Album that new items go into; empty = none (library only). */
    synchronized String albumId() {
        return props.getProperty(ALBUM_ID, "");
    }

    synchronized String albumTitle() {
        return props.getProperty(ALBUM_TITLE, "");
    }

    /** Saves the three upload options at once; {@code albumId} must be empty or one of {@link #albums()}. */
    synchronized void setOptions(boolean raw, boolean videos, String albumId) throws IOException {
        props.setProperty(UPLOAD_RAW, Boolean.toString(raw));
        props.setProperty(UPLOAD_VIDEOS, Boolean.toString(videos));
        selectAlbum(albumId);
        save();
    }

    /** Albums created by this app, as last listed by Google (or created from the web page). */
    synchronized List<Album> albums() {
        List<Album> albums = new ArrayList<Album>();
        try {
            JSONArray array = new JSONArray(props.getProperty(ALBUMS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject album = array.getJSONObject(i);
                albums.add(new Album(album.getString("id"), album.getString("title")));
            }
        } catch (JSONException e) {
            // A damaged cache is replaced by the next listing.
        }
        return albums;
    }

    /**
     * Replaces the cached list with Google's current one and refreshes the selected album's title
     * (renamed in Google Photos). The selection is dropped if Google no longer lists the album
     * (deleted there); returns its old title then, else null.
     */
    synchronized String setAlbums(List<Album> albums) throws IOException {
        List<Album> sorted = new ArrayList<Album>(albums);
        Collections.sort(sorted, BY_TITLE);
        storeAlbums(sorted);
        String before = albumTitle();
        boolean hadAlbum = albumId().length() > 0;
        selectAlbum(albumId());
        save();
        return hadAlbum && albumId().length() == 0 ? before : null;
    }

    /** Adds a newly created album to the cache and makes it the upload destination. */
    synchronized void addAndSelectAlbum(Album album) throws IOException {
        List<Album> albums = albums();
        albums.add(album);
        Collections.sort(albums, BY_TITLE);
        storeAlbums(albums);
        selectAlbum(album.id);
        save();
    }

    private void selectAlbum(String id) {
        Album album = id.length() == 0 ? null : find(albums(), id);
        if (album == null) {
            props.remove(ALBUM_ID);
            props.remove(ALBUM_TITLE);
        } else {
            props.setProperty(ALBUM_ID, album.id);
            props.setProperty(ALBUM_TITLE, album.title);
        }
    }

    private void storeAlbums(List<Album> albums) {
        JSONArray array = new JSONArray();
        try {
            for (Album album : albums) {
                array.put(new JSONObject().put("id", album.id).put("title", album.title));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        props.setProperty(ALBUMS, array.toString());
    }

    private static Album find(List<Album> albums, String id) {
        for (Album album : albums) {
            if (album.id.equals(id)) {
                return album;
            }
        }
        return null;
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
