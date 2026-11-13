package io.github.codeyumx.picofacetracking;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Asks GitHub for the latest release (pre-releases are never "latest") and compares it with the installed version. */
final class UpdateCheck {
    static final String LATEST_RELEASE_PAGE = "https://github.com/codeyumx/PicoFaceTracking/releases/latest";
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/codeyumx/PicoFaceTracking/releases/latest";
    private static final int TIMEOUT_MS = 10_000;
    /** The release JSON is a few kilobytes; anything far larger is not GitHub's answer. */
    private static final int MAX_BYTES = 1 << 20;

    private UpdateCheck() {
    }

    /** Blocks on the network; call it off the main thread. */
    static Text check(String installed) {
        try {
            String latest = latestVersion();
            return compare(latest, installed) > 0
                    ? new Text(R.string.update_available, latest, installed)
                    : new Text(R.string.update_current, installed);
        } catch (IOException | JSONException | NumberFormatException e) {
            return new Text(R.string.update_failed, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static String latestVersion() throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            // GitHub's API rejects requests without a User-Agent.
            connection.setRequestProperty("User-Agent", "PicoFaceTracking");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK)
                throw new IOException("GitHub answered HTTP " + code);
            String tag = new JSONObject(read(connection.getInputStream())).getString("tag_name");
            return tag.startsWith("v") ? tag.substring(1) : tag;
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = stream.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BYTES)
                    throw new IOException("GitHub's answer is too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Compares dotted versions number by number; a suffix (1.9.3-rc1) is ignored. Negative, 0 or positive. */
    static int compare(String a, String b) {
        String[] left = a.split("-", 2)[0].split("\\.");
        String[] right = b.split("-", 2)[0].split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? Integer.parseInt(left[i]) : 0;
            int r = i < right.length ? Integer.parseInt(right[i]) : 0;
            if (l != r)
                return Integer.compare(l, r);
        }
        return 0;
    }
}
