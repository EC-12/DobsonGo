package com.terroroo.dobsongomenu;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * <p>General-purpose helpers shared by the whole DobsonGo menu.</p>
 *
 * <p>This class is never instantiated. It groups small static utilities:</p>
 * <ul>
 *   <li><b>Logging</b> to Logcat under a single tag ({@link #Logcat(String, int)});</li>
 *   <li><b>UI helpers</b>: toasts, unit conversions between dp, px and WebView CSS pixels;</li>
 *   <li><b>Resource lookup</b> by name, without access to the generated <code>R</code> class;</li>
 *   <li><b>Bluetooth Low Energy helpers</b>: UUID expansion and decoding of characteristic values;</li>
 *   <li><b>Cryptography</b>: the HMAC-SHA256 used to answer the telescope's authentication challenge;</li>
 *   <li><b>Text comparison</b> with the Levenshtein distance, used to match object names in JPL Horizons.</li>
 * </ul>
 */
public abstract class Utils {
    /** Cryptographically secure random generator shared by the app. */
    public static final SecureRandom RANDOM = new SecureRandom();
    /** Maximum number of objects that the observation queue can contain. */
    public static final int QUEUE_MAX_SIZE = 200;
    /** Toast currently displayed, kept so that a new toast can replace it immediately. */
    private static Toast currentToast;
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    private static final String CHARS_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    /** Tag used for every Logcat message of the app, to filter them easily. */
    private static final String LOGCAT_NAME = "TERROROO_LOG";

    /**
     * <p>Minimal observer used to be notified when a value changes.</p>
     *
     * @param <T> type of the observed value
     */
    public interface Observer<T> {
        /**
         * <p>Called every time the observed value changes.</p>
         *
         * @param value the new value
         */
        void onChanged(T value);
    }

    /**
     * <p>Exception used throughout DobsonGo to carry two messages at once:</p>
     * <ul>
     *   <li>a <b>technical message</b> (or cause), written to Logcat for debugging;</li>
     *   <li>a <b>user message</b> ({@link #ui_msg}), short and readable, shown in the menu.</li>
     * </ul>
     * <p>The {@link #code} works like an HTTP status code: for example <code>404</code> when an object
     * cannot be found in JPL Horizons, and <code>500</code> for an unexpected error.</p>
     */
    public static class DobsonGoException extends RuntimeException {
        /** Message displayed to the user in the menu. */
        public final String ui_msg;
        /** Error code, following the HTTP conventions (404 = not found, 500 = unexpected error). */
        public int code;

        /**
         * <p>Creates an exception that wraps another exception.</p>
         *
         * @param code     error code (see {@link #code})
         * @param user_msg message shown to the user
         * @param cause    original exception, kept for the stack trace
         */
        public DobsonGoException(int code, String user_msg, Throwable cause) {
            super(cause);
            this.code = code;
            this.ui_msg = user_msg;
        }

        /**
         * <p>Creates an exception with a technical message.</p>
         *
         * @param code     error code (see {@link #code})
         * @param user_msg message shown to the user
         * @param msg      technical message, written to Logcat
         */
        public DobsonGoException(int code, String user_msg, String msg) {
            super(msg);
            this.code = code;
            this.ui_msg = user_msg;
        }

        /**
         * <p>Creates an exception that only carries a user message, without code or technical details.</p>
         *
         * @param user_msg message shown to the user
         */
        public DobsonGoException(String user_msg) {
            super();
            this.ui_msg = user_msg;
        }
    }

    /**
     * <p>Generates a random string made of uppercase and lowercase Latin letters.</p>
     *
     * @param length number of characters to generate
     * @return the random string
     */
    public static String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = RANDOM.nextInt(CHARS_LETTERS.length());
            sb.append(CHARS_LETTERS.charAt(index));
        }
        return sb.toString();
    }

    /**
     * <p>Converts a byte array into its uppercase hexadecimal representation.</p>
     *
     * <p>Each byte becomes two characters, for example <code>{0x0A, 0xFF}</code> becomes <code>"0AFF"</code>.
     * Useful to log binary values such as HMAC signatures.</p>
     *
     * @param bytes the bytes to convert
     * @return the hexadecimal string, twice as long as the array
     */
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * <p>Writes a message to Logcat under the app's tag ({@value #LOGCAT_NAME}).</p>
     *
     * <p>The level is chosen with an integer:</p>
     * <ul>
     *   <li><code>0</code>: information ({@link android.util.Log#i});</li>
     *   <li><code>1</code>: warning ({@link android.util.Log#w});</li>
     *   <li><code>2</code>: error ({@link android.util.Log#e});</li>
     *   <li>any other value: debug ({@link android.util.Log#d}).</li>
     * </ul>
     *
     * @param msg  the message to log
     * @param type the log level, from 0 to 2
     */
    public static void Logcat(String msg, int type) {
        switch (type) {
            case 0: // Information: regular log
                Log.i(LOGCAT_NAME, msg);
                break;
            case 1: // Warning: something abnormal, but not fatal
                Log.w(LOGCAT_NAME, msg);
                break;
            case 2: // Error: something went wrong
                Log.e(LOGCAT_NAME, msg);
                break;
            default: // Unknown level: logged as debug
                Log.d(LOGCAT_NAME, msg);
                break;
        }
    }

    /**
     * <p>Writes a debug message to Logcat.</p>
     *
     * <p><b>For quick debugging only:</b> do not leave calls to this method in finished code,
     * use {@link #Logcat(String, int)} with an explicit level instead.</p>
     *
     * @param msg the message to log
     */
    public static void Logcat(String msg) { Log.d(LOGCAT_NAME, msg); }

    /*public static int spToPx(Context ctx, int sp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sp,
            ctx.getResources().getDisplayMetrics()
        );
    }*/

    /**
     * <p>Reads a file from the app's <code>assets</code> folder and encodes it in Base64.</p>
     *
     * <p>Used to send images to the WebView menu, which can display them directly as
     * <code>data:</code> URIs.</p>
     *
     * @param context  context used to open the assets
     * @param fileName path of the file inside the <code>assets</code> folder
     * @return the Base64 content (without line breaks), or <code>null</code> if the file cannot be read
     */
    public static String encodeAssetToBase64(Context context, String fileName) {
        try {
            InputStream is = context.getAssets().open(fileName);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int n;

            while ((n = is.read(data)) != -1) {
                buffer.write(data, 0, n);
            }
            byte[] imageBytes = buffer.toByteArray();
            is.close();

            return android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            Logcat(Log.getStackTraceString(e), 1);
            return null;
        }
    }

    /**
     * <p>Converts a size in density-independent pixels (dp) into physical pixels for this screen.</p>
     *
     * @param ctx context used to read the screen density
     * @param dp  size in dp
     * @return the size in pixels
     */
    public static int dpToPx(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            ctx.getResources().getDisplayMetrics()
        );
    }

    /**
     * <p>Converts a size in physical pixels into density-independent pixels (dp).</p>
     *
     * @param context context used to read the screen density
     * @param px      size in pixels
     * @return the size in dp, rounded to the nearest integer
     */
    public static int pxToDp(Context context, int px) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(px / density);
    }

    /**
     * <p>Converts a size measured in the WebView (CSS pixels) into real screen pixels.</p>
     *
     * <p>The WebView works in CSS pixels, which correspond to dp: this method is used to resize the
     * overlay window so that it exactly fits the HTML menu.</p>
     *
     * @param context context used to read the screen density
     * @param cssPx   size in CSS pixels
     * @return the size in real screen pixels
     */
    public static int realScreenPx(Context context, int cssPx) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(cssPx * density);
    }

    /**
     * <p>Displays a long toast message.</p>
     *
     * <p>If a toast is already visible, it is cancelled first, so that messages never pile up.</p>
     *
     * @param ctx     context used to create the toast
     * @param message the text to display
     */
    public static void showToast(Context ctx, String message) {
        if (currentToast != null) {
            currentToast.cancel();
        }
        currentToast = Toast.makeText(ctx, message, Toast.LENGTH_LONG);
        currentToast.show();
    }

    /**
     * <p>Finds the identifier of a resource of the app from its name, without using the generated
     * <code>R</code> class.</p>
     *
     * <p>This is needed because the menu is injected into another app: the resources are looked up by
     * name at runtime.</p>
     *
     * @param ctx  context of the app that owns the resource
     * @param name name of the resource, for example <code>"terroroo_main_layout"</code>
     * @param type type of the resource, for example <code>"id"</code>, <code>"drawable"</code> or <code>"attr"</code>
     * @return the resource identifier, or <code>0</code> if it does not exist
     */
    public static int getRessourceIdWithoutR(Context ctx, String name, String type) {
        return ctx.getResources().getIdentifier(name, type, ctx.getPackageName());
    }

    /**
     * <p>Finds the identifier of a resource from its name, in a given package.</p>
     *
     * <p>Used for example to load icons from the Android system package (<code>"android"</code>).</p>
     *
     * @param ctx  context used to access the resources
     * @param name name of the resource
     * @param type type of the resource, for example <code>"drawable"</code>
     * @param pkg  package that contains the resource
     * @return the resource identifier, or <code>0</code> if it does not exist
     */
    public static int getRessourceIdWithoutR(Context ctx, String name, String type, String pkg) {
        return ctx.getResources().getIdentifier(name, type, pkg);
    }

    /**
     * <p>Computes the HMAC-SHA256 signature of a text with a secret key.</p>
     *
     * <p>This is how the app proves to the telescope that it knows the connection key, without ever
     * sending it: the telescope sends a random challenge, and the app answers with
     * <code>HMAC-SHA256(key, challenge)</code>. See {@link BLEManager.BLEServer}.</p>
     *
     * @param key  the secret key (the connection key entered by the user)
     * @param data the text to sign (the challenge sent by the telescope)
     * @return the 32-byte signature
     * @throws Exception if the HMAC-SHA256 algorithm is not available
     */
    public static byte[] hmac_sha256(String key, String data) throws Exception {
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256_HMAC.init(secret_key);
        return sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * <p>Converts a Bluetooth UUID written as text into a {@link UUID}.</p>
     *
     * <p>Bluetooth standard services and characteristics are often identified by a short 16-bit UUID,
     * such as <code>"181A"</code> (Environmental Sensing). Those are expanded into a full 128-bit UUID
     * with the official Bluetooth base <code>0000XXXX-0000-1000-8000-00805F9B34FB</code>.
     * Full UUIDs are parsed as they are.</p>
     *
     * @param uuid a 4-character short UUID, or a full 36-character UUID
     * @return the corresponding 128-bit UUID
     * @throws IllegalArgumentException if the text is not a valid UUID
     */
    public static UUID stringToUUID(String uuid) {
        if (uuid.length() == 4) {
            /*
             * Official Bluetooth base used to expand a short 16-bit UUID into a 128-bit UUID:
             * 0000XXXX-0000-1000-8000-00805F9B34FB, where XXXX is the 16-bit UUID.
             */
            return UUID.fromString(String.format("0000%s-0000-1000-8000-00805F9B34FB", uuid));
        }
        return UUID.fromString(uuid);
    }

    /**
     * <p>Decodes a boolean sent by the telescope over BLE.</p>
     *
     * @param data the value of the characteristic, exactly 1 byte
     * @return <code>false</code> if the byte is <code>0</code>, <code>true</code> otherwise
     * @throws IllegalArgumentException if the array is <code>null</code> or is not exactly 1 byte long
     */
    public static boolean bytesToBoolean(byte[] data) {
        if (data == null || data.length != 1) {
            throw new IllegalArgumentException("type boolean requires exactly 1 byte");
        }
        return data[0] != 0;
    }

    /**
     * <p>Decodes an unsigned 32-bit integer stored in big-endian order (most significant byte first).</p>
     *
     * <p>The result is returned as a <code>long</code>, because a Java <code>int</code> cannot hold values above
     * 2<sup>31</sup> − 1.</p>
     *
     * @param data exactly 4 bytes
     * @return the decoded value, between 0 and 2<sup>32</sup> − 1
     * @throws IllegalArgumentException if the array is <code>null</code> or is not exactly 4 bytes long
     */
    public static long uint32FromBigEndian(byte[] data) {
        if (data == null || data.length != 4) {
            throw new IllegalArgumentException("type uint32 requires exactly 4 bytes");
        }
        return ((long) (data[0] & 0xFF) << 24)
                | ((long) (data[1] & 0xFF) << 16)
                | ((long) (data[2] & 0xFF) << 8)
                | ((long) (data[3] & 0xFF));
    }

    /**
     * <p>Decodes a UTF-8 text of a known length, for example the authentication challenge sent by the
     * telescope.</p>
     *
     * @param data        the bytes to decode
     * @param expectedLen the exact number of bytes expected
     * @return the decoded text
     * @throws IllegalArgumentException if the array is <code>null</code> or does not have the expected length
     */
    public static String bytesToUTF8String(byte[] data, int expectedLen) {
        if (data == null || data.length != expectedLen) {
            throw new IllegalArgumentException("Invalid length or NULL data for bytesToUTF8String: expected " + expectedLen + " bytes");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * <p>Computes the <b>Levenshtein distance</b> between two strings: the minimum number of single-character
     * insertions, deletions or substitutions needed to turn one into the other.</p>
     *
     * <p>For example, the distance between <code>"Mars"</code> and <code>"Mars Barycenter"</code> is 11.
     * It is used by {@link HorizonsParser} to choose, among several results returned by JPL Horizons,
     * the one whose name is closest to the selected object.</p>
     *
     * <p>The classic dynamic programming algorithm is used, in <code>O(a.length() × b.length())</code>.</p>
     *
     * @param a the first string
     * @param b the second string
     * @return the distance, from 0 (identical strings) to the length of the longest string
     */
    public static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[a.length()][b.length()];
    }
}
