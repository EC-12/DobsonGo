package com.terroroo.dobsongomenu;

import static com.terroroo.dobsongomenu.DobsonGoMod.mainHandler;
import static com.terroroo.dobsongomenu.Utils.DobsonGoException;
import static com.terroroo.dobsongomenu.Utils.Logcat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * <p>Client of the <a href="https://ssd.jpl.nasa.gov/horizons/">JPL Horizons</a> API, the NASA service that
 * computes the orbits of the bodies of the solar system.</p>
 *
 * <p>It is used to:</p>
 * <ul>
 *   <li>search objects by name ({@link #fetchOrbitalObj});</li>
 *   <li>download the Keplerian elements of an object, needed to compute its position ({@link #fetchKElem}).</li>
 * </ul>
 *
 * <p>The API answers in JSON, but the ephemeris itself is plain text, in the <code>result</code> field: this class
 * reads it with regular expressions. The requests run one after the other on a background thread, and the results
 * are returned on the main thread through a {@link HorizonsAPICallback}.</p>
 */
public abstract class HorizonsParser {
    /** Background thread of the requests: they run one after the other, never on the main thread. */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    /**
     * <p>Trust manager used for the HTTPS connection to Horizons.</p>
     *
     * <p>It does not check the certificate chain itself; the connection is restricted to the
     * <code>ssd.jpl.nasa.gov</code> host name in {@link #APIRequester(String)}.</p>
     */
    private static final TrustManager[] trustManagers = new TrustManager[] {
        new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }
    };

    /**
     * <p>Result of a Horizons request. The methods are called on the main thread.</p>
     *
     * <p>Each request only uses one of the two <code>onSuccess</code> methods; the other one does nothing by
     * default.</p>
     */
    public interface HorizonsAPICallback {
        /**
         * <p>Called by {@link #fetchOrbitalObj} with the objects found.</p>
         *
         * @param objects JSON array of objects <code>{"hz_id": ..., "name": ...}</code>: the Horizons identifier and the
         *                name to display
         */
        default void onSuccess(JSONArray objects) {};
        /** Called by {@link #fetchKElem} once the Keplerian elements have been added to the object. */
        default void onSuccess() {};
        /**
         * <p>Called when the request fails.</p>
         *
         * @param e the error: code <code>404</code> if nothing was found, <code>500</code> for an unexpected error, with a
         *          message for the user
         */
        void onError(DobsonGoException e);
    }

    /**
     * <p>Sends a request to the Horizons API and returns the text of its answer.</p>
     *
     * <p>Only the <code>ssd.jpl.nasa.gov</code> host name is accepted for the HTTPS connection.</p>
     *
     * @param horizonCmd the full URL of the request
     * @return the content of the <code>result</code> field of the JSON answer
     * @throws Exception if the connection fails or the answer is not valid JSON
     */
    private static String APIRequester(String horizonCmd) throws Exception {
        HttpsURLConnection urlConnection = null;
        BufferedReader reader = null;
        JSONObject jsonObject;

        try {
            Logcat(horizonCmd, 0);

            URL url = new URL(horizonCmd);
            urlConnection = (HttpsURLConnection) url.openConnection();

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, new SecureRandom());

            urlConnection.setSSLSocketFactory(sslContext.getSocketFactory());
            urlConnection.setHostnameVerifier((hostname, session) -> hostname.equals("ssd.jpl.nasa.gov"));

            InputStream in = new BufferedInputStream(urlConnection.getInputStream());
            reader = new BufferedReader(new InputStreamReader(in));

            StringBuilder responseBuilder = new StringBuilder();
            String serverInLine;

            while ((serverInLine = reader.readLine()) != null) {
                responseBuilder.append(serverInLine).append("\n");
            }

            String response = responseBuilder.toString();
            jsonObject = new JSONObject(response);

        } finally {
            try {
                if (reader != null) reader.close();
                if (urlConnection != null) urlConnection.disconnect();
            } catch (Exception ignored) {}
        }

        return jsonObject.getString("result");
    }

    /**
     * <p>Picks the best match when Horizons returns a list of objects instead of an ephemeris.</p>
     *
     * <p>Each line of the text that entirely matches <code>pattern</code> receives a score from <code>scorer</code>.
     * The Horizons identifier (group 1 of the pattern) of the line with the highest score is returned.</p>
     *
     * @param text    the answer of Horizons
     * @param pattern regular expression of a line of the list; group 1 must be the Horizons identifier
     * @param scorer  gives a score to a matching line: the higher, the better
     * @return the identifier with the best score, or <code>null</code> if no line matches
     */
    private static String horizonsRawFetcher(
        String text,
        Pattern pattern,
        Function<Matcher, Double> scorer
    ) {
        String bestHzsId = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        String[] lines = text.split("\\R");

        for (String line : lines) {
            Matcher m = pattern.matcher(line);
            if (!m.matches()) continue;

            String HzsId = m.group(1);
            double score = scorer.apply(m);

            if (score > bestScore) {
                bestScore = score;
                bestHzsId = HzsId;
            }
        }

        return bestHzsId;
    }

    /**
     * <p>Builds the time range of the ephemeris: from the start of the current hour to 5 hours later.</p>
     *
     * <p>The hour is rounded in the local time zone of the phone, then converted to UTC, the time scale expected by
     * Horizons.</p>
     *
     * @return <code>{start, stop}</code> in UTC, formatted as <code>yyyy-MM-dd'T'HH:mm</code>
     */
    private static String[] timeBuilder() {
        ZoneId localZone = ZoneId.systemDefault();
        ZonedDateTime nowLocal = ZonedDateTime.now(localZone);

        ZonedDateTime startLocal = nowLocal.truncatedTo(ChronoUnit.HOURS);
        ZonedDateTime stopLocal = startLocal.plusHours(5);

        ZonedDateTime startUtc = startLocal.withZoneSameInstant(ZoneOffset.UTC);
        ZonedDateTime stopUtc  = stopLocal.withZoneSameInstant(ZoneOffset.UTC);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

        return new String[]{formatter.format(startUtc), formatter.format(stopUtc)};
    }

    /**
     * <p>Extracts from the name of an asteroid, as given by the host app, the part that Horizons can search.</p>
     *
     * <p>An asteroid can be written with its number, its name and/or its provisional designation (see
     * {@link #parseId} for the meaning of these designations). The rules are applied in this order:</p>
     * <ol>
     *   <li>only a designation between parentheses: the parentheses are removed.<br>
     *       <code>(2023 AB1)</code> → <code>2023 AB1</code></li>
     *   <li>parentheses followed by a name: the parentheses and their content are removed.<br>
     *       <code>(433) Eros</code> → <code>Eros</code></li>
     *   <li>a number that is not a year: it is the number of the asteroid, which is removed with any parentheses after
     *       the name.<br>
     *       <code>433 Eros (1898 DQ)</code> → <code>Eros</code></li>
     *   <li>a year (between 1950 and 2070): it is a provisional designation, kept as is.<br>
     *       <code>2023 AB1</code> → <code>2023 AB1</code></li>
     *   <li>anything else is kept as is.<br>
     *       <code>Eros</code> → <code>Eros</code></li>
     * </ol>
     *
     * @param rawName the name of the asteroid in the host app
     * @return the name to search in Horizons, or <code>null</code> if <code>rawName</code> is <code>null</code>
     */
    private static String normalizeAsteroidName(String rawName) {
        if (rawName == null) return null;
        String name = rawName.trim();

        // Rule 1: only a designation between parentheses -> remove the parentheses
        if (name.matches("^\\([^()]+\\)$")) {
            return name.substring(1, name.length() - 1).trim();
        }

        // Rule 2: starts with parentheses followed by a name -> remove the parentheses and their content
        if (name.matches("^\\([^()]+\\)\\s+.+")) {
            return name.replaceFirst("^\\([^()]+\\)\\s*", "").trim();
        }

        // Rules 3 and 4: starts with a number
        if (name.matches("^\\d+.*")) {
            // Extract the first number
            String[] parts = name.split("\\s+", 2);
            String firstToken = parts[0];

            int value = Integer.parseInt(firstToken);
            boolean isDate = value >= 1950 && value <= 2070;

            if (!isDate) {
                // Rule 3: asteroid number -> remove the number
                String remainder = parts.length > 1 ? parts[1] : "";

                // Also remove any parentheses after the name
                remainder = remainder.replaceFirst("\\s*\\([^()]+\\)", "");

                return remainder.trim();
            }
            // Rule 4: otherwise, it is a year (provisional designation) -> keep as is
        }

        // Rule 5: anything else is kept as is
        return name;
    }

    /**
     * <p>Builds the identifier used to search an object of the host app in Horizons (<code>COMMAND</code>
     * parameter).</p>
     *
     * <table class="striped">
     *   <caption>Identifier sent to Horizons for each type</caption>
     *   <tr><th>Type</th><th>Identifier</th><th>Example</th></tr>
     *   <tr><td>Planet, moon</td><td>the name, as is. The Moon (identifier <code>31</code> in the host app) uses its
     *       Horizons identifier, <code>301</code></td><td><code>Mars</code></td></tr>
     *   <tr><td>Dwarf planet</td><td><code>'name;'</code></td><td><code>'Ceres;'</code></td></tr>
     *   <tr><td>Asteroid</td><td><code>'name;'</code>, after {@link #normalizeAsteroidName}</td>
     *       <td><code>(433) Eros</code> → <code>'Eros;'</code></td></tr>
     *   <tr><td>Comet</td><td><code>'designation;'</code>, without the name of the comet</td>
     *       <td><code>88P/Howell</code> → <code>'88P;'</code><br>
     *           <code>C/2020 F3 (NEOWISE)</code> → <code>'C/2020 F3;'</code></td></tr>
     * </table>
     *
     * <p>In Horizons, a final <code>;</code> restricts the search to small bodies (asteroids, comets, dwarf planets),
     * so that a moon or a spacecraft with the same name is not returned.</p>
     *
     * <p><b>Comet designations.</b> The prefix gives the dynamical status of the comet:</p>
     * <ul>
     *   <li><code>P/</code>: periodic comet (period under 200 years, or seen at several passages). Once confirmed, it
     *       receives a number: <code>1P/Halley</code> is the first periodic comet;</li>
     *   <li><code>C/</code>: non-periodic comet (very long or unknown period), with no number. <code>C/2020 F3</code>:
     *       discovered in 2020, during the second half of March (<code>F</code>), third comet of that half-month
     *       (<code>3</code>);</li>
     *   <li><code>D/</code>: comet that disappeared or was destroyed: <code>D/1993 F2</code> (Shoemaker-Levy 9);</li>
     *   <li><code>X/</code>: comet whose orbit could not be computed, often from old observations;</li>
     *   <li><code>A/</code>: object first taken for a comet, but with no activity: <code>A/2017 U1</code>;</li>
     *   <li><code>nI/</code>: interstellar object: <code>1I/ʻOumuamua</code> (formerly <code>A/2017 U1</code>),
     *       <code>2I/Borisov</code>, <code>3I/ATLAS</code>.</li>
     * </ul>
     * <p>The status can change: a <code>C/</code> comet becomes <code>P/</code> if it comes back, a <code>P/</code> comet
     * becomes <code>D/</code> if it disappears. The regular expression keeps the number and letter (<code>88P</code>), or
     * the prefix with the year and the half-month code (<code>C/2020 F3</code>).</p>
     *
     * <p><b>Asteroid designations.</b> An asteroid first receives a <b>provisional designation</b>: year, letter of
     * the half-month, then letter and number giving its order of discovery (<code>2023 AB1</code>). Once its orbit is
     * well known, it receives a <b>permanent number</b>, written in parentheses: <code>(433)</code> is simply the 433rd
     * numbered asteroid. It can then receive a <b>name</b>: <code>(433) Eros</code>. Comets and asteroids are numbered
     * separately: <code>67P</code> and <code>(67)</code> are two different objects.</p>
     *
     * @param skType type of the object; only planets, dwarf planets, asteroids and comets have a Horizons identifier
     * @param name   name of the object in the host app
     * @param id     identifier of the object in the host app, used to recognise the Moon
     * @return the Horizons identifier of the object
     */
    public static String parseId(RealAstronomy.SkyObjectType skType, String name, int id) {
        String horizonId;
        switch (skType) {
            case Planet:
                if (id == 31) horizonId = "301";
                else horizonId = name;
                break;

            case Dwarf:
                horizonId = String.format("'%s;'", name);
                break;

            case Asteroid:
                horizonId = String.format("'%s;'", normalizeAsteroidName(name));
                break;

            case Comet:
                Pattern p = Pattern.compile("^([A-Z0-9]+(?:/[0-9]{4}\\s+[A-Z][0-9]+)?)");
                Matcher m = p.matcher(name);
                horizonId = m.find() ? String.format("'%s;'", m.group(1)) : null;
                break;

            default:
                horizonId = null;
                break;
        }
        assert horizonId != null;
        return horizonId;
    }

    /**
     * <p>Searches objects in Horizons by name, for the search screen of the menu.</p>
     *
     * <p>The text is sent upper-case and between quotes. Horizons can answer in four ways:</p>
     * <ol>
     *   <li><b>no match</b>: error 404;</li>
     *   <li><b>several major bodies</b> (planets, moons, spacecraft...): each line of the list becomes a result, with
     *       the name cleaned of its parentheses;</li>
     *   <li><b>several small bodies</b>: each line becomes a result, named <code>name - designation (epoch
     *       year)</code>;</li>
     *   <li><b>a single object</b>: Horizons directly returns its ephemeris. Its name and identifier are read from
     *       the header; if the search ended with <code>;</code>, it is added to the identifier again, to keep the
     *       small-body search.</li>
     * </ol>
     *
     * <p>The results are passed to {@link HorizonsAPICallback#onSuccess(JSONArray)}, or the error to
     * {@link HorizonsAPICallback#onError}: <code>404</code> if nothing was found, <code>500</code> for an unexpected
     * error.</p>
     *
     * @param search   the text typed by the user; a final <code>;</code> restricts the search to small bodies
     * @param callback receives the results on the main thread
     */
    public static void fetchOrbitalObj(String search, HorizonsAPICallback callback) {
        executor.execute(() -> {
            JSONArray objects = new JSONArray();

            try {
                String _horizonCmd = "https://ssd.jpl.nasa.gov/api/horizons.api" +
                    "?format=json" +
                    "&CENTER=500@10" + // Heliocentric reference
                    "&EPHEM_TYPE=ELEMENTS" +
                    "&COMMAND=" + URLEncoder.encode(String.format("'%s'", search.toUpperCase()), StandardCharsets.UTF_8.toString());
                String horizonsText = APIRequester(_horizonCmd);

                if (horizonsText.contains("No matches found")) {
                    throw new DobsonGoException(404,
                        "Aucun résultat trouvé.",
                        String.format("Requested object '%s' not found in Horizons database", search)
                    );
                } else if (horizonsText.contains("Multiple major-bodies match string")) {
                    Pattern linePattern = Pattern.compile("^\\s*([-]?\\d+)\\s+(.+?)\\s{2,}.*$");
                    String[] lines = horizonsText.split("\\R");

                    for (String line : lines) {
                        Matcher m = linePattern.matcher(line);
                        if (!m.matches()) continue;

                        String hzsId = m.group(1);
                        String rawName = m.group(2).trim();
                        String cleanName = rawName.replaceAll("\\s*\\([^)]*\\)", "").trim();

                        JSONObject json = new JSONObject();
                        json.put("hz_id", hzsId);
                        json.put("name", cleanName);
                        objects.put(json);
                    }
                } else if (horizonsText.contains("Small-body Index Search Results")) {
                    Pattern rowPattern = Pattern.compile("^\\s*(\\d+)\\s+(\\d{4})\\s{2,}(\\S+).*\\s{2,}(\\S+)$");
                    String[] lines = horizonsText.split("\\R");

                    for (String line : lines) {
                        Matcher m = rowPattern.matcher(line);
                        if (!m.matches()) continue;

                        String hzsId = m.group(1);
                        String epoch = m.group(2);
                        String match_desig = m.group(3);
                        String rawName = m.group(4).trim();
                        String cleanName = rawName.replaceAll("\\s*\\([^)]*\\)", "").trim();

                        JSONObject json = new JSONObject();
                        json.put("hz_id", hzsId);
                        json.put("name", String.format("%s - %s (%s)", cleanName, match_desig, epoch));
                        objects.put(json);
                    }
                } else {
                    int start = horizonsText.indexOf("$$SOE");
                    int end = horizonsText.indexOf("$$EOE");

                    if (start == -1 || end == -1 || end <= start) {
                        throw new DobsonGoException(404,
                            "Horizons a renvoyé une page inconnue.",
                            String.format("Unable to find $$SOE for '%s' while the object appears to be alone", search)
                        );
                    } else {
                        Pattern revisedPattern = Pattern.compile("^\\h*Revised:(?:\\s\\w+\\S)+\\s{2,}(.+?)\\s{2,}(\\d+)$", Pattern.MULTILINE);
                        Pattern jplPattern = Pattern.compile("^\\h*JPL/HORIZONS\\s{2,}(.+?)\\s{2,}.+\\RRec\\D+(\\d+)\\s.+$", Pattern.MULTILINE);
                        Matcher m_revised = revisedPattern.matcher(horizonsText);
                        Matcher m_jpl = jplPattern.matcher(horizonsText);

                        String title = null;
                        String hzsId = null;

                        if (m_revised.find()) {
                            title = m_revised.group(1);
                            hzsId = m_revised.group(2);
                        } else if (m_jpl.find()) {
                            title = m_jpl.group(1);
                            hzsId = m_jpl.group(2);
                        }

                        if (title != null && hzsId != null) {
                            JSONObject json = new JSONObject();
                            if (search.contains(";")) hzsId += ";";
                            json.put("hz_id", hzsId);
                            json.put("name", title);
                            objects.put(json);
                        }
                    }
                }

                if (objects.length() == 0) {
                    throw new DobsonGoException(404,
                        "Aucun résultat trouvé.",
                        String.format("Requested object list is empty following search for object '%s'", search)
                    );
                } else mainHandler.post(() -> callback.onSuccess(objects));
            } catch (DobsonGoException custom_e) {
                mainHandler.post(() -> callback.onError(custom_e));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    DobsonGoException error = new DobsonGoException(500, "Une erreur inconnue est survenue (voir logcat)", e);
                    callback.onError(error);
                });
            }
        });
    }

    /**
     * <p>Downloads the Keplerian elements of an object for the next 5 hours, one set every 30 minutes, and adds them to
     * {@link RealAstronomy.OrbitalObject#K_Elements}.</p>
     *
     * <p>The elements are osculating elements relative to the centre of the Sun (<code>CENTER=500@10</code>). If
     * Horizons returns a list instead of the elements, the best match is chosen, saved in
     * {@link RealAstronomy.OrbitalObject#horizons}, and the request is sent again with it:</p>
     * <ul>
     *   <li><b>several major bodies</b>: the name closest to the name of the object (Levenshtein distance), barycentres
     *       excluded;</li>
     *   <li><b>several small bodies</b>: the record with the most recent epoch, usually the latest orbit
     *       solution.</li>
     * </ul>
     *
     * <p>The angles (<code>IN</code>, <code>OM</code>, <code>W</code>, <code>MA</code>) are converted from degrees to
     * radians; <code>A</code> stays in km. On success, {@link HorizonsAPICallback#onSuccess()} is called; otherwise
     * {@link HorizonsAPICallback#onError}: <code>404</code> if the object or its elements were not found,
     * <code>500</code> for an unexpected error.</p>
     *
     * @param object   the orbital object; its <code>horizons</code> identifier may be replaced by a more precise one
     * @param callback receives the result on the main thread
     */
    public static void fetchKElem(RealAstronomy.OrbitalObject object, HorizonsAPICallback callback) {
        executor.execute(() -> {
            try {
                String[] utcTime = timeBuilder();
                String _horizonCmd = "https://ssd.jpl.nasa.gov/api/horizons.api" +
                    "?format=json" +
                    "&CENTER=500@10" + // Heliocentric reference
                    "&EPHEM_TYPE=ELEMENTS" +
                    "&COMMAND=" + URLEncoder.encode(object.horizons, StandardCharsets.UTF_8.toString()) +
                    "&START_TIME=" + utcTime[0] +
                    "&STOP_TIME=" + utcTime[1] +
                    "&STEP_SIZE=30m";

                String horizonsText = APIRequester(_horizonCmd);

                if (horizonsText.contains("No matches found")) {
                    throw new DobsonGoException(404,
                        String.format("L'objet '%s' n'a pas été trouvé dans la base de donnée Horizons", object.horizons),
                        String.format("Requested object '%s' not found in Horizons database", object.horizons)
                    );
                } else if (horizonsText.contains("Multiple major-bodies match string")) {
                    Pattern linePattern = Pattern.compile("^\\s*([-]?\\d+)\\s+(.+?)\\s{2,}.*$");
                    String bestId = horizonsRawFetcher(
                        horizonsText,
                        linePattern,
                        (matcher) -> {
                            String rawName = matcher.group(2).trim();
                            if (rawName.toLowerCase().contains("barycenter")) return Double.NEGATIVE_INFINITY;

                            String cleanName = rawName.replaceAll("\\s*\\([^)]*\\)", "").trim();
                            int distance = Utils.levenshtein(object.name, cleanName);

                            int maxLen = Math.max(object.name.length(), cleanName.length());
                            //if (maxLen == 0) return 1.0;
                            assert maxLen != 0;

                            double score = 1.0 - (double) distance / maxLen;
                            if (cleanName.equals(object.name)) score = 1.0;
                            return score;
                        }
                    );

                    if (bestId == null) {
                        throw new DobsonGoException(404,
                            String.format("L'objet '%s' n'a pas été trouvé dans la base de donnée Horizons", object.horizons),
                            String.format("Requested object '%s' not found in Horizons database", object.horizons)
                        );
                    } else {
                        object.horizons = bestId;
                        fetchKElem(object, callback);
                    }
                } else if (horizonsText.contains("Small-body Index Search Results")) {
                    Pattern rowPattern = Pattern.compile("^\\s*(\\d+)\\s+(\\d{4})\\s{2,}.*$");
                    String bestId = horizonsRawFetcher(
                        horizonsText,
                        rowPattern,
                        (matcher) -> {
                            int epochYear = Integer.parseInt(matcher.group(2));
                            return (double) epochYear;
                        }
                    );

                    if (bestId == null) {
                        throw new DobsonGoException(404,
                            String.format("L'objet '%s' n'a pas été trouvé dans la base de donnée Horizons", object.horizons),
                            String.format("Requested object '%s' not found in Horizons database", object.horizons)
                        );
                    }
                    else {
                        object.horizons = bestId;
                        fetchKElem(object, callback);
                    }
                } else {
                    int start = horizonsText.indexOf("$$SOE");
                    int end = horizonsText.indexOf("$$EOE");

                    if (start == -1 || end == -1 || end <= start) {
                        throw new DobsonGoException(404,
                            String.format("Les éléments képlériens de l'objet '%s' n'ont pas été retournés par Horizons", object.name),
                            String.format("Unable to find orbital elements for '%s' in Horizons database", object.name)
                        );
                    } else {
                        Logcat(horizonsText, 0);
                        List<RealAstronomy.KeplerianSnapshot> result = new ArrayList<>();
                        horizonsText = horizonsText.substring(start + 5, end).trim();
                        String[] SOELines = horizonsText.split("\\R");

                        Double jd = null;
                        Double a = null, e = null, i = null, om = null, w = null, ma = null;
                        Pattern jdPattern = Pattern.compile("^\\s*(\\d+\\.\\d+)\\s*=.*$");
                        Pattern valuePattern = Pattern.compile("(EC|IN|OM|W|MA|A)\\s*=\\s*([-+]?\\d*\\.?\\d+(?:E[-+]?\\d+)?)");

                        for (String soeLine : SOELines) {
                            Matcher jdMatcher = jdPattern.matcher(soeLine);
                            if (jdMatcher.matches()) {
                                if (jd != null && a != null && e != null && i != null && om != null && w != null && ma != null) {
                                    result.add(new RealAstronomy.KeplerianSnapshot(jd, a, e, i, om, w, ma));
                                }
                                jd = Double.parseDouble(jdMatcher.group(1));
                                a = e = i = om = w = ma = null;
                                continue;
                            }

                            Matcher m = valuePattern.matcher(soeLine);
                            while (m.find()) {
                                String key = m.group(1);
                                double value = Double.parseDouble(m.group(2));
                                switch (key) {
                                    case "A":  a = value; break;
                                    case "EC": e = value; break;
                                    case "IN": i = Math.toRadians(value); break;
                                    case "OM": om = Math.toRadians(value); break;
                                    case "W":  w = Math.toRadians(value); break;
                                    case "MA": ma = Math.toRadians(value); break;
                                }
                            }
                        }

                        if (jd != null && a != null && e != null && i != null && om != null && w != null && ma != null) {
                            result.add(new RealAstronomy.KeplerianSnapshot(jd, a, e, i, om, w, ma));
                        }

                        mainHandler.post(() -> {
                            assert object.K_Elements.addAll(result);
                            callback.onSuccess();
                        });
                    }
                }
            } catch (DobsonGoException custom_e) {
                mainHandler.post(() -> callback.onError(custom_e));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    DobsonGoException error = new DobsonGoException(500, "Une erreur inconnue est survenue (voir logcat)", e);
                    callback.onError(error);
                });
            }
        });
    }
}
