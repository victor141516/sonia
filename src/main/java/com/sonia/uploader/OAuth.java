package com.sonia.uploader;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import android.util.Base64;

/**
 * Google OAuth for an installed ("Desktop") client with a loopback redirect and PKCE.
 * The browser that signs in runs on a computer or phone, not on the camera, so the redirect
 * (the address the user configured, e.g. http://localhost) fails there and the user pastes the
 * resulting address into the camera's web page.
 */
final class OAuth {
    /** Upload, create albums (appendonly) and list the albums this app created (readonly.appcreateddata). */
    static final String SCOPE_APPEND = "https://www.googleapis.com/auth/photoslibrary.appendonly";
    static final String SCOPE_READ_APP_CREATED = "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata";
    static final String SCOPE = SCOPE_APPEND + " " + SCOPE_READ_APP_CREATED;

    /** One sign-in attempt: the PKCE verifier and the anti-forgery state. */
    static final class Attempt {
        final String verifier;
        final String state;

        private Attempt(String verifier, String state) {
            this.verifier = verifier;
            this.state = state;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private OAuth() {}

    static Attempt newAttempt() {
        return new Attempt(randomToken(32), randomToken(16));
    }

    static String authUrl(String clientId, String redirectUri, Attempt attempt) {
        return "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + "&scope=" + encode(SCOPE)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + encode(attempt.state)
                + "&code_challenge=" + challenge(attempt.verifier)
                + "&code_challenge_method=S256";
    }

    /**
     * Extracts the authorization code from what the user pasted: the whole redirect address
     * (state is then checked) or just the code.
     */
    static String codeFrom(String pasted, Attempt attempt) {
        String text = pasted.trim();
        int query = text.indexOf('?');
        if (query < 0 && !text.contains("code=")) {
            if (text.length() == 0) {
                throw new IllegalArgumentException("nothing was pasted");
            }
            return text;
        }
        String code = null;
        String state = null;
        String error = null;
        for (String pair : text.substring(query + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq);
            String value = decode(pair.substring(eq + 1));
            if (name.equals("code")) {
                code = value;
            } else if (name.equals("state")) {
                state = value;
            } else if (name.equals("error")) {
                error = value;
            }
        }
        if (error != null) {
            throw new IllegalArgumentException("Google returned an error: " + error);
        }
        if (code == null) {
            throw new IllegalArgumentException("the address does not contain code=");
        }
        if (state != null && (attempt == null || !state.equals(attempt.state))) {
            throw new IllegalArgumentException("this address belongs to a different sign-in attempt; please start again");
        }
        return code;
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"));
            return base64Url(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomToken(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return base64Url(data);
    }

    private static String base64Url(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
