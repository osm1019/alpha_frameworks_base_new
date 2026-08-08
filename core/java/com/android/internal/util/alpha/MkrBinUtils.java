/*
 * Copyright (C) 2018 Potato Open Sauce Project
 * Copyright (C) 2021 Jyotiraditya Panda <jyotiraditya@aospa.co>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.alpha;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Helper for uploading crash/report text and returning a shareable URL.
 *
 * Tries, in order:
 * <ol>
 *   <li>paste.rs — POST raw text → 201 + URL body. Heavily rate limited.</li>
 *   <li>dpaste.com — POST form → 201 + URL body. Deliberately anonymous: an API token
 *       would only make us the owner, which would attribute every user's crash to one
 *       account and expose them all through dpaste's public user_items endpoint.</li>
 *   <li>MkrBin (https://bin.mkr.pw/) — POST → 302 + Location. Kept last: it has been
 *       returning 504s and read timeouts, which is what made this chain necessary.</li>
 * </ol>
 *
 * None of them needs a credential, which is the point — anything shipped in the image is
 * readable by anyone holding the image.
 *
 * <p>When a host is down, {@code debug.alpha.paste_service} moves one of them to the front
 * without a rebuild:
 *
 * <pre>adb shell setprop debug.alpha.paste_service 20</pre>
 *
 * <p>Values are spaced by ten so they cannot be mistaken for a boolean: {@code 0} or unset keeps
 * the default order, {@code 10} dpaste, {@code 20} paste.rs, {@code 30} MkrBin. Anything else is
 * ignored with a warning, and the remaining hosts still follow as fallbacks, so a stale value
 * cannot stop an upload. {@code debug.} properties are settable over adb without root and are
 * cleared by a reboot.
 *
 * <p>Prefix the value with {@code only:} to drop the fallbacks and use exactly one host, which is
 * how to test whether a host still works:
 *
 * <pre>adb shell setprop debug.alpha.paste_service only:10</pre>
 *
 * <p>The log line {@code Uploaded via <host>} names whichever host served the URL.
 */
public final class MkrBinUtils {

    private static final String TAG = "MkrBinUtils";

    /** Moves one host to the front of the chain; see the class docs. */
    private static final String PROP_PASTE_SERVICE = "debug.alpha.paste_service";

    /** Prefix on {@link #PROP_PASTE_SERVICE} meaning "this host only, no fallbacks". */
    private static final String ONLY_PREFIX = "only:";

    /** Explicitly "use the default order"; same as leaving the property unset. */
    private static final int SERVICE_DEFAULT = 0;

    // Spaced by ten so a value is never confused with a 0/1 boolean.
    private static final int SERVICE_DPASTE = 10;
    private static final int SERVICE_PASTE_RS = 20;
    private static final int SERVICE_MKRBIN = 30;

    private static final int[] DEFAULT_ORDER = {
        SERVICE_PASTE_RS, SERVICE_DPASTE, SERVICE_MKRBIN,
    };

    private static final String DPASTE_URL = "https://dpaste.com/api/v2/";
    private static final String PASTE_RS_URL = "https://paste.rs";
    private static final String MKRBIN_URL = "https://bin.mkr.pw";

    /** dpaste accepts 1-365. Crash links get handed over days later, so ask for the max. */
    private static final int DPASTE_EXPIRY_DAYS = 365;

    /** dpaste's terms require automated callers to identify themselves. */
    private static final String USER_AGENT = "AlphaDroid-CrashReporter/1.0";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static Handler mHandler;

    private MkrBinUtils() {}

    /**
     * Uploads {@code content} and invokes the callback with a public URL on success.
     *
     * @param content  the content to upload
     * @param callback the callback to call on success / failure
     */
    public static void upload(String content, UploadResultCallback callback) {
        getHandler().post(() -> {
            try {
                String url = null;
                for (int service : serviceOrder()) {
                    url = uploadTo(service, content);
                    if (url != null && !url.isEmpty()) {
                        Log.i(TAG, "Uploaded via " + nameOf(service));
                        break;
                    }
                }
                if (url != null && !url.isEmpty()) {
                    callback.onSuccess(url.trim());
                } else {
                    String msg = "Failed to upload paste: no URL retrieved";
                    callback.onFail(msg, new Exception(msg));
                }
            } catch (Exception e) {
                callback.onFail("Failed to upload paste", e);
            }
        });
    }

    /**
     * The hosts to try, in order: whichever {@link #PROP_PASTE_SERVICE} names first, then the
     * rest, so a stale property cannot make uploading impossible. With an {@code only:} prefix
     * the named host is used alone — the form to use when testing whether a host still works.
     */
    private static List<Integer> serviceOrder() {
        String value =
                SystemProperties.get(PROP_PASTE_SERVICE, "").trim().toLowerCase(Locale.ROOT);

        final boolean pinned = value.startsWith(ONLY_PREFIX);
        if (pinned) {
            value = value.substring(ONLY_PREFIX.length()).trim();
        }

        int preferred = SERVICE_DEFAULT;
        if (!value.isEmpty()) {
            try {
                preferred = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                preferred = -1;
            }
        }

        if (preferred == SERVICE_DEFAULT) {
            return defaultOrder();
        }
        if (!isKnownService(preferred)) {
            Log.w(TAG, "Ignoring unknown " + PROP_PASTE_SERVICE + "=\"" + value + "\"");
            return defaultOrder();
        }
        if (pinned) {
            Log.i(TAG, "Pinned to " + nameOf(preferred) + " by " + PROP_PASTE_SERVICE
                    + "; no fallbacks");
            return List.of(preferred);
        }

        List<Integer> order = new ArrayList<>(DEFAULT_ORDER.length);
        order.add(preferred);
        for (int service : DEFAULT_ORDER) {
            if (!order.contains(service)) {
                order.add(service);
            }
        }
        return order;
    }

    private static List<Integer> defaultOrder() {
        List<Integer> order = new ArrayList<>(DEFAULT_ORDER.length);
        for (int service : DEFAULT_ORDER) {
            order.add(service);
        }
        return order;
    }

    private static boolean isKnownService(int service) {
        for (int known : DEFAULT_ORDER) {
            if (known == service) {
                return true;
            }
        }
        return false;
    }

    /** Host name for logs — the numbers are for typing, not for reading a logcat. */
    private static String nameOf(int service) {
        switch (service) {
            case SERVICE_DPASTE:
                return "dpaste";
            case SERVICE_PASTE_RS:
                return "paste.rs";
            case SERVICE_MKRBIN:
                return "MkrBin";
            default:
                return "service " + service;
        }
    }

    private static String uploadTo(int service, String content) {
        switch (service) {
            case SERVICE_DPASTE:
                return uploadToDpaste(content);
            case SERVICE_PASTE_RS:
                return uploadToPasteRs(content);
            case SERVICE_MKRBIN:
                return uploadToMkrBin(content);
            default:
                return null;
        }
    }

    /**
     * dpaste: POST a form → 201 Created, body is the URL (also in the Location header). The form
     * is url-encoded; the server reads it the same as the multipart its docs show.
     */
    private static String uploadToDpaste(String content) {
        HttpURLConnection conn = null;
        byte[] body = null;
        try {
            String form = "content=" + URLEncoder.encode(content, StandardCharsets.UTF_8.name())
                    + "&expiry_days=" + DPASTE_EXPIRY_DAYS;
            body = form.getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(DPASTE_URL).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);
            conn.setFixedLengthStreamingMode(body.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream stream = isOk(code) ? conn.getInputStream() : conn.getErrorStream();
            String response = readFully(stream);

            if (isOk(code)) {
                String url = asUrl(response);
                if (url == null) {
                    // Body was not a URL; the Location header carries it too.
                    url = asUrl(conn.getHeaderField("Location"));
                }
                if (url != null) {
                    return url;
                }
            }
            Log.w(TAG, "dpaste upload failed: HTTP " + code + " sent=" + body.length
                    + "B body=" + truncate(response));
        } catch (Exception e) {
            Log.w(TAG, "dpaste upload failed (sent=" + (body == null ? 0 : body.length) + "B)", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    /** paste.rs: POST body as text/plain → 201 Created, body is the URL. */
    private static String uploadToPasteRs(String content) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(PASTE_RS_URL).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);

            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream stream = isOk(code) ? conn.getInputStream() : conn.getErrorStream();
            String response = readFully(stream);

            if (isOk(code)) {
                String url = asUrl(response);
                if (url != null) {
                    if (code == HttpURLConnection.HTTP_PARTIAL) {
                        // 206 means the paste exceeded paste.rs's size cap and only part of it
                        // was stored. The link works, but it is a truncated stacktrace.
                        Log.w(TAG, "paste.rs stored only part of the paste: sent "
                                + body.length + "B → " + url);
                    }
                    return url;
                }
            }
            Log.w(TAG, "paste.rs upload failed: HTTP " + code + " sent=" + body.length
                    + "B body=" + truncate(response));
        } catch (Exception e) {
            Log.w(TAG, "paste.rs upload failed", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    /**
     * Legacy MkrBin: POST → expect 302 with Location header (or absolute redirect).
     * Kept as fallback only.
     */
    private static String uploadToMkrBin(String content) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(MKRBIN_URL).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);

            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int code = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            if (code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                if (location != null && !location.isEmpty()) {
                    if (location.startsWith("http://") || location.startsWith("https://")) {
                        return location;
                    }
                    return MKRBIN_URL + location;
                }
            }
            Log.w(TAG, "MkrBin upload failed: HTTP " + code + " sent=" + body.length
                    + "B Location=" + location);
        } catch (Exception e) {
            Log.w(TAG, "MkrBin upload failed", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private static boolean isOk(int code) {
        return code >= 200 && code < 300;
    }

    /** The trimmed response as a URL, or null when it is not one. */
    private static String asUrl(String response) {
        if (response == null) {
            return null;
        }
        String url = response.trim();
        return (url.startsWith("http://") || url.startsWith("https://")) ? url : null;
    }

    private static String readFully(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "Failed to read response body", e);
            return "";
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static Handler getHandler() {
        if (mHandler == null) {
            HandlerThread thread = new HandlerThread("PasteUploadThread");
            thread.start();
            mHandler = new Handler(thread.getLooper());
        }
        return mHandler;
    }

    public interface UploadResultCallback {
        void onSuccess(String url);

        void onFail(String message, Exception e);
    }
}
