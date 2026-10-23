package com.terroroo.dobsongomenu;

import static com.terroroo.dobsongomenu.Utils.Logcat;

import android.app.Activity;
import android.content.Context;

import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.json.JSONObject;
import org.orekit.data.DataContext;
import org.orekit.data.DataProvidersManager;
import org.orekit.data.ZipJarCrawler;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.frames.Transform;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * <p>Astronomy model of DobsonGo: the objects that can be observed and the conversions between coordinate
 * systems.</p>
 *
 * <ul>
 *   <li>{@link SkyObject} and its subclasses describe the objects selected in the host app or found in JPL
 *       Horizons: fixed objects ({@link StaticObject}), solar system bodies ({@link OrbitalObject}) and
 *       artificial satellites ({@link Satellite});</li>
 *   <li>{@link SkyObjectSet} is the observation list of the user;</li>
 *   <li>the other methods format coordinates for display, convert them with the Orekit library, and install the
 *       data Orekit needs.</li>
 * </ul>
 */
public abstract class RealAstronomy {
    /**
     * <p>Types of objects that can be selected in the host app or added to the observation list.</p>
     *
     * <p>The numeric values are those sent by the native code (see {@link AppMenuService#onObjectSelected}), except
     * {@link #Horizons}, which is used for objects added from a JPL Horizons search.</p>
     */
    public enum SkyObjectType {
        /** Unknown or undefined type. */
        None(0, "None", "Nul", "question_mark", true),
        /** Star. */
        Star(1, "Star", "Étoile", "star_shine", true),
        /** Planet, or natural satellite of a planet (including the Moon). */
        Planet(2, "Planet", "Planète", "planet", false),
        /** Constellation. */
        Constellation(3, "Constellation", "Constellation", "star_2", true),
        /** Deep-sky object: galaxy, nebula, star cluster... */
        DeepSpace(4, "Deep Space Object", "Espace Profond", "orbit", true),
        /** Meteor shower, located by its radiant. */
        MeteorShower(5, "Meteor Shower", "Pluie de Météorites", "snowing_heavy", true),
        /** Artificial satellite, located with its TLE. */
        Satellite(6, "Satellite", "Satellite", "satellite_alt", false),
        /** Asteroid. */
        Asteroid(7, "Asteroid", "Astéroïde", "fiber_smart_record", false),
        /** Dwarf planet. */
        Dwarf(8, "Dwarf", "Planète Naine", "planet", false),
        /** Comet. */
        Comet(9, "Comet", "Comète", "motion_blur", false),
        /** Object added from a JPL Horizons search. Internal type, not sent by the host app. */
        Horizons(42, "Horizons", "Base Horizons JPL", "database", false);
        //Mission(10, "Mission", "Mission", false),
        //UFO(11, "UFO", "OVNI", false),
        //SatelliteGroup(12, "Satellite Group", "Groupe de Satellites", false),
        //LastSkyObjectType(13, "LastSkyObjectType", "LastSkyObjectType", false),

        /** Numeric value of the type, as sent by the native code. */
        public final int value;
        /** Name of the type, in English (logs). */
        public final String name;
        /** Name of the type displayed in the menu. */
        public final String name_fr;
        /** Name of the Material Symbols icon displayed in the menu. */
        public final String material_icon;
        /**
         * <p><code>true</code> for objects with fixed coordinates in the sky (stars, constellations, deep-sky objects,
         * meteor showers), <code>false</code> for objects that move (planets, satellites, small bodies), whose position must
         * be computed.</p>
         */
        public final boolean is_static;
        /**
         * <p>Creates a type of object.</p>
         *
         * @param v   numeric value
         * @param n   name in English
         * @param nfr name displayed in the menu
         * @param mic name of the Material Symbols icon
         * @param s   <code>true</code> if the object has fixed coordinates
         */
        SkyObjectType(int v, String n, String nfr, String mic, boolean s) {
            value = v; name = n; name_fr = nfr; material_icon = mic; is_static = s;
        }

        /**
         * <p>Returns the type that matches a numeric value.</p>
         *
         * @param v the numeric value, for example sent by the native code
         * @return the matching type, or {@link #None} (with a warning in Logcat) if the value is unknown
         */
        public static SkyObjectType fromInt(int v) {
            for (SkyObjectType m : values()) {
                if (m.value == v) return m;
            }
            Logcat("SkyObjectType -> unknown value: " + v, 1);
            return None;
        }
    }

    /**
     * <p><b>EXTRACTED FROM HORIZONS - Symbol meaning:</b><br>
     * <b>JDTDB  → </b>Julian Day Number, Barycentric Dynamical Time<br>
     * <b>EC     → </b>Eccentricity, e<br>
     * <b>QR     → </b>Periapsis distance, q (km)<br>
     * <b>IN     → </b>Inclination w.r.t X-Y plane, i (degrees)<br>
     * <b>OM     → </b>Longitude of Ascending Node, OMEGA, (degrees)<br>
     * <b>W      → </b>Argument of Perifocus, w (degrees)<br>
     * <b>Tp     → </b>Time of periapsis (Julian Day Number)<br>
     * <b>N      → </b>Mean motion, n (degrees/sec)<br>
     * <b>MA     → </b>Mean anomaly, M (degrees)<br>
     * <b>TA     → </b>True anomaly, nu (degrees)<br>
     * <b>A      → </b>Semi-major axis, a (km)<br>
     * <b>AD     → </b>Apoapsis distance (km)<br>
     * <b>PR     → </b>Sidereal orbit period (sec)</p>
     *
     * <p><b>Important note, often misread in the Horizons notation:</b><br>
     * OM ≠ W → OMEGA (Ω, capital) is the longitude of the ascending node, not the argument of perihelion (ω, lowercase)<br>
     * RAAN is the modern term for Ω, it is not used by Horizons</p>
     *
     * <p><b>Values stored in this class</b> (see {@link HorizonsParser#fetchKElem}): <code>a</code> in km,
     * <code>e</code> without unit, the four angles in <b>radians</b> (converted from the degrees returned by
     * Horizons), and the date as a Julian Day (TDB).</p>
     */
    public static class KeplerianSnapshot {
        /** Reference date of the elements: Julian Day, TDB time scale. */
        public final double jdTdb;              // Horizons: JDTDB
        /** Semi-major axis, in km. */
        public final double a;      // Horizons: A
        /** Eccentricity, without unit. */
        public final double e;       // Horizons: EC
        /** Inclination, in radians. */
        public final double i;        // Horizons: IN
        /** Longitude of the ascending node (Ω), in radians. */
        public final double raan;   // Horizons: OM  -> Orekit: raan (capital Ω)
        /** Argument of perihelion (ω), in radians. */
        public final double pa;     // Horizons: W   -> Orekit: pa   (lowercase ω)
        /** Mean anomaly, in radians. */
        public final double anomaly;        // Horizons: MA

        /**
         * <p>Creates a set of Keplerian elements, valid at the date <code>JDTDB</code>. <code>A</code> is given in
         * km, and the four angles (<code>IN</code>, <code>OM</code>, <code>W</code>, <code>MA</code>) in radians.</p>
         *
         * @param JDTDB reference date (Julian Day, Barycentric Dynamical Time) at which the 6 other parameters are
         * valid. Without this date, the elements mean nothing: an orbit slowly "drifts" over time because of the
         * gravitational perturbations of the other bodies, so Horizons gives a set of elements valid at a precise
         * instant, not an eternal truth.
         * @param A Semi-major axis: the "mean radius" of the orbit, half of the major axis of the ellipse.
         * It gives the size of the orbit (and, through Kepler's third law, its period). <b>Shape of the ellipse</b>
         * @param EC Eccentricity: how flattened the ellipse is. 0 = perfect circle, close to 1 = very elongated
         * ellipse (like a comet). It never reaches 1 for a closed orbit (above 1, the trajectory is a hyperbola and
         * the object escapes). <b>Shape of the ellipse</b>
         * @param IN Inclination: the angle between the plane of the orbit and the reference plane (the ecliptic, or
         * the Earth's equator depending on the frame). 0° = orbit in the same plane as the reference.
         * <b>Orientation of the ellipse in 3D space</b>
         * @param OM Longitude of Ascending Node: the angle that places the point where the orbit crosses the
         * reference plane going up. Most textbooks write it with a capital Ω (Omega). Horizons calls it OM, not
         * RAAN. <b>Orientation of the ellipse in 3D space</b>
         * @param W Argument of Perifocus (= argument of perihelion): once the plane of the orbit is placed, this
         * angle gives where, IN this inclined plane, the point closest to the central body (the perihelion) is
         * located. Textbooks write it with a lowercase ω (omega). Careful: this is not OM (capital Ω), it is ω
         * (lowercase). <b>Orientation of the ellipse in 3D space</b>
         * @param MA Mean Anomaly: the position of the object on its orbit at the <code>JDTDB</code> date, given as a
         * fictitious angle that increases uniformly over time (unlike the real position, which moves faster near
         * the perihelion and slower far from it: Kepler's second law). It is a convenient mathematical tool to
         * compute where the object will be later, before converting it into the true anomaly (TA), which gives the
         * real geometric position.
         */
        public KeplerianSnapshot(
            double JDTDB,
            double A,
            double EC,
            double IN,
            double OM,
            double W,
            double MA
        ) {
            this.jdTdb = JDTDB;
            this.a = A;
            this.e = EC;
            this.i = IN;
            this.raan = OM;
            this.pa = W;
            this.anomaly = MA;
        }
    }

    /**
     * <p>Object of the sky that can be selected, added to the observation list and tracked by the telescope.</p>
     *
     * <p>This base class only holds the identity of the object. Its position is given by the subclasses:
     * {@link StaticObject}, {@link OrbitalObject} and {@link Satellite}.</p>
     */
    public static class SkyObject {
        /** Type of the object. */
        public final SkyObjectType type;
        /** Name displayed to the user. */
        public final String title;
        /** Internal name of the object, as given by the host app. */
        public final String name;
        /** Identifier of the object in the host app, or <code>-1</code> if it has none (objects added from Horizons). */
        public final int id;
        /**
         * <code>true</code> while the information card of this object is open in the menu.
         */
        public boolean is_viewed = false;
        /**
         * <code>true</code> while the telescope is tracking this object.
         */
        private boolean isTracking = false;
        /** Notified each time the tracking state changes, or <code>null</code>. */
        private Utils.Observer<Boolean> trackingStateObserver;

        /**
         * <p>Creates an object of the sky.</p>
         *
         * @param type  type of the object
         * @param title name displayed to the user
         * @param name  internal name of the object
         * @param id    identifier in the host app, or <code>-1</code> if it has none
         */
        public SkyObject(SkyObjectType type, String title, String name, int id) {
            this.type = type;
            this.title = title;
            this.name = name;
            this.id = id;
        }

        /**
         * <p>Two objects are equal as soon as <b>one</b> of these criteria matches, checked in this order:</p>
         * <ol>
         *   <li>same name;</li>
         *   <li>same title;</li>
         *   <li>same identifier, if both identifiers are valid (different from <code>-1</code>).</li>
         * </ol>
         *
         * <p>This prevents the same object from being added twice to the list, even if it comes from two different
         * sources.</p>
         *
         * @param o the object to compare with
         * @return <code>true</code> if the two objects represent the same object of the sky
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SkyObject)) return false;

            SkyObject other = (SkyObject) o;
            // Priority 1: same name
            if (Objects.equals(this.name, other.name)) return true;
            // Priority 2: same title
            if (Objects.equals(this.title, other.title)) return true;
            // Priority 3: same id (only if both are valid)
            if (this.id != -1 && other.id != -1) return this.id == other.id;
            return false;
        }

        /**
         * <p>Returns a constant hash code.</p>
         *
         * <p>Two equal objects must have the same hash code. As {@link #equals(Object)} matches on name <b>or</b> title
         * <b>or</b> identifier, two equal objects can have no field in common to compute it from: the only safe choice is a
         * constant. The downside is that searches in a {@link java.util.HashSet} become linear, which is fine for the small
         * size of the observation list.</p>
         *
         * @return always <code>42</code>
         */
        @Override
        public int hashCode() {
            return 42; // Constant: required by the multi-criteria equals() (slower, but fine for lists under 1000 objects)
        }

        /**
         * <p>Starts or stops the tracking of this object, and notifies the observer, if there is one.</p>
         *
         * @param state <code>true</code> to start tracking, <code>false</code> to stop
         */
        public void changeTrackingState(boolean state) {
            this.isTracking = state;
            if (this.trackingStateObserver != null) {
                this.trackingStateObserver.onChanged(this.isTracking);
            }
        }

        /**
         * <p>Indicates whether the telescope is tracking this object.</p>
         *
         * @return <code>true</code> if the object is being tracked
         */
        public boolean isTracking() { return isTracking; }

        /**
         * <p>Sets the observer notified each time the tracking state changes.</p>
         *
         * @param observer the observer, which receives the new state
         */
        public void setTrackingObserver(Utils.Observer<Boolean> observer) { this.trackingStateObserver = observer; }
    }

    /**
     * <p>Object with fixed coordinates in the sky: star, constellation, deep-sky object or meteor shower.</p>
     *
     * <p>Its position is given by its equatorial coordinates at the J2000 epoch. They must be corrected for
     * precession and nutation to get the coordinates of the current date (see <code>dso_j2000toOfDate</code>).</p>
     */
    public static class StaticObject extends SkyObject {
        /** Right ascension at the J2000 epoch, in radians. */
        public final double RA_j2000;
        /** Declination at the J2000 epoch, in radians. */
        public final double DEC_j2000;
        /** Apparent magnitude: the smaller the value, the brighter the object. */
        public final double mag;
        /**
         * <code>true</code> if this star has been used to calibrate the telescope.
         */
        public boolean isCalibPoint = false;

        /**
         * <p>Creates an object with fixed coordinates.</p>
         *
         * @param type     type of the object (must be a static type)
         * @param title    name displayed to the user
         * @param name     internal name of the object
         * @param id       identifier in the host app
         * @param RAj2000  right ascension at the J2000 epoch, in radians
         * @param DECj2000 declination at the J2000 epoch, in radians
         * @param mag      apparent magnitude
         */
        public StaticObject(
                SkyObjectType type, String title, String name, int id,
                double RAj2000, double DECj2000, double mag
        ) {
            super(type, title, name, id);
            assert !Double.isNaN(RAj2000) && !Double.isNaN(DECj2000);
            this.RA_j2000 = RAj2000;
            this.DEC_j2000 = DECj2000;
            this.mag = mag;
        }
    }

    /**
     * <p>Body of the solar system that moves along an orbit: planet, moon, dwarf planet, asteroid or comet, or any
     * object found in JPL Horizons.</p>
     *
     * <p>Its position is computed from its Keplerian elements, downloaded from Horizons with
     * {@link HorizonsParser#fetchKElem} before the tracking starts.</p>
     */
    public static class OrbitalObject extends SkyObject {
        /**
         * <p>Identifier used to query Horizons (see {@link HorizonsParser#parseId}). It can be replaced by a more precise
         * identifier when Horizons returns several matches.</p>
         */
        public String horizons;
        /**
         * <code>true</code> if the object is a natural satellite of a planet.
         */
        public final boolean isMoon;
        /** Keplerian elements downloaded from Horizons: one set every 30 minutes, over the next 5 hours. */
        public final List<KeplerianSnapshot> K_Elements = new ArrayList<>(); // Covers the next 5 hours

        /**
         * <p>Creates an orbital object selected in the host app. Its Horizons identifier is built from its type and name
         * with {@link HorizonsParser#parseId}.</p>
         *
         * @param type   type of the object (planet, dwarf planet, asteroid or comet)
         * @param title  name displayed to the user
         * @param name   internal name of the object in the host app
         * @param id     identifier in the host app
         * @param isMoon <code>true</code> if the object is a natural satellite of a planet
         */
        public OrbitalObject(SkyObjectType type, String title, String name, int id, boolean isMoon) {
            super(type, title, name, id);
            assert !type.is_static;
            this.horizons = HorizonsParser.parseId(type, name, id);
            this.isMoon = isMoon;
        }

        /**
         * <p>Creates an orbital object found in a Horizons search. Its type is {@link SkyObjectType#Horizons} and it has
         * no identifier in the host app (<code>-1</code>).</p>
         *
         * @param title name displayed to the user
         * @param hzId  Horizons identifier of the object
         */
        public OrbitalObject(String title, String hzId) {
            super(SkyObjectType.Horizons, title, title, -1);
            assert !type.is_static;
            this.horizons = hzId;
            this.isMoon = false;
        }

        /**
         * <p>Same rules as {@link SkyObject#equals(Object)}, with one more criterion: two orbital objects with the same
         * Horizons identifier are equal.</p>
         *
         * @param o the object to compare with
         * @return <code>true</code> if the two objects represent the same body
         */
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof OrbitalObject)) return false;
            if (super.equals(o)) return true;
            OrbitalObject other = (OrbitalObject) o;
            return Objects.equals(this.horizons, other.horizons);
        }
    }

    /**
     * <p>Artificial satellite, whose position is computed from its TLE (Two-Line Element set), the format used by
     * NORAD to publish the orbits of satellites.</p>
     */
    public static class Satellite extends SkyObject {
        /** NORAD catalogue number of the satellite. */
        public final int noradId;
        /** First line of the TLE. */
        public final String Tle1;
        /** Second line of the TLE. */
        public final String Tle2;

        /**
         * <p>Creates a satellite.</p>
         *
         * @param title   name displayed to the user
         * @param name    internal name of the satellite
         * @param id      identifier in the host app
         * @param noradId NORAD catalogue number
         * @param Tle1    first line of the TLE (must not be <code>null</code>)
         * @param Tle2    second line of the TLE (must not be <code>null</code>)
         */
        public Satellite(
                String title, String name, int id,
                int noradId, String Tle1, String Tle2
        ) {
            super(SkyObjectType.Satellite, title, name, id);
            assert Tle1 != null && Tle2 != null;
            this.noradId = noradId; this.Tle1 = Tle1; this.Tle2 = Tle2;
        }
    }

    /**
     * <p>Observation list of the user.</p>
     *
     * <p>It is a {@link HashSet} that also keeps the menu up to date: each object added is displayed in the list of
     * the WebView. Duplicates are detected with {@link SkyObject#equals(Object)}.</p>
     */
    public static class SkyObjectSet extends HashSet<SkyObject> {
        /** The menu service, used to update the WebView. */
        private final AppMenuService appService;

        /**
         * <p>Creates an empty observation list.</p>
         *
         * @param appService the menu service, used to update the WebView
         */
        public SkyObjectSet(AppMenuService appService) {
            this.appService = appService;
        }

        /**
         * <p>Adds an object to the list and displays it in the menu.</p>
         *
         * @param obj the object to add
         * @return <code>true</code> if the object was added, <code>false</code> if it was already in the list
         */
        @Override
        public boolean add(SkyObject obj) {
            boolean added = super.add(obj);
            if (added) {
                appService.webView.evaluateJavascript(
                    String.format("javascript:android_addQueueElement(%s)", serializeSkyObject(obj)),
                    null
                );
            }
            return added;
        }

        /**
         * <p>Finds an object of the list from the unique identifier used by the menu.</p>
         *
         * <p>The identifier has two forms (see {@link #serializeSkyObject(SkyObject)}):</p>
         * <ul>
         *   <li><code>"hz:&lt;Horizons identifier&gt;"</code> for an object added from Horizons;</li>
         *   <li>the identifier of the host app, as text, for the other objects.</li>
         * </ul>
         *
         * @param uniqueId the unique identifier of the object
         * @param skType   type of the object, used to check its class
         * @param <T>      the expected class
         * @return the object found
         * @throws NoSuchElementException  if no object of the list has this identifier
         * @throws IllegalArgumentException if the object found does not have the class expected for this type
         */
        @SuppressWarnings("unchecked")
        public <T extends SkyObject> T retrieveId(String uniqueId, SkyObjectType skType) throws NoSuchElementException, IllegalArgumentException {
            assert skType != SkyObjectType.None;

            SkyObject skObj;
            if (uniqueId.startsWith("hz:")) {
                skObj = this.stream()
                    .filter(o -> Objects.equals(((OrbitalObject) o).horizons, uniqueId.substring(3)))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("Requested object is not in the queue"));
            } else {
                skObj = this.stream()
                    .filter(o -> Objects.equals(String.valueOf(o.id), uniqueId))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("Requested object is not in the queue"));
            }

            Class<? extends SkyObject> expectedType;
            if (skType == SkyObjectType.Satellite) expectedType = Satellite.class;
            else if (skType.is_static) expectedType = StaticObject.class;
            else expectedType = OrbitalObject.class;

            if (!expectedType.isInstance(skObj)) {
                throw new IllegalArgumentException(
                    "Object found (" + skObj.getClass().getSimpleName() + ") does not match expected type " + expectedType.getSimpleName()
                );
            }
            return (T) skObj;
        }

        /**
         * <p>Removes an object from the list. If its information card is open in the menu, it is closed.</p>
         *
         * @param uniqueId the unique identifier of the object (see {@link #retrieveId})
         * @param skType   type of the object
         * @throws NoSuchElementException if no object of the list has this identifier
         */
        public void deleteId(String uniqueId, SkyObjectType skType) {
            SkyObject skObj = retrieveId(uniqueId, skType);
            if (skObj.is_viewed) {
                appService.webView.evaluateJavascript(
                    "javascript:android_toggleObjectCard(false)",
                    null
                );
            }
            this.remove(skObj);
        }
    }

    /*public static SkyObject parseSkyObject(String uniqueId) throws Exception {
        JSONObject jsonObj = new JSONObject(jsonStr);
        int rawType = jsonObj.getInt("type");
        SkyObjectType type = SkyObjectType.fromInt(rawType);
        if (type == SkyObjectType.None) throw new IllegalArgumentException("Invalid sky object type: " + rawType);

        String name = jsonObj.getString("name");
        String title = jsonObj.getString("title");
        int id = jsonObj.getInt("id");
        return new SkyObject(type, title, name, id);
    }*/

    /**
     * <p>Converts an object to JSON, to send it to the HTML menu.</p>
     *
     * <p>Fields always present:</p>
     * <ul>
     *   <li><code>id</code>: unique identifier, <code>"hz:&lt;Horizons identifier&gt;"</code> for an object added from
     *       Horizons, the identifier of the host app otherwise;</li>
     *   <li><code>type</code>, <code>type_name</code> and <code>type_icon</code>: numeric value, displayed name and icon
     *       of the type;</li>
     *   <li><code>title</code>: name displayed to the user.</li>
     * </ul>
     * <p>Additional fields: <code>is_calib_point</code>, <code>mag</code>, <code>ra</code> and <code>dec</code> (in
     * radians) for fixed objects, <code>norad</code> for satellites.</p>
     *
     * @param obj the object to convert
     * @return the JSON object
     * @throws RuntimeException if the JSON cannot be built
     */
    public static JSONObject serializeSkyObject(SkyObject obj) {
        try {
            JSONObject jsonObj = new JSONObject();

            String uniqueId;
            if (obj instanceof OrbitalObject
                && obj.type == SkyObjectType.Horizons
            ) { uniqueId = String.format("hz:%s", ((OrbitalObject) obj).horizons); }
            else uniqueId = String.format("%s", obj.id);

            jsonObj.put("id", uniqueId);
            jsonObj.put("type", obj.type.value);
            jsonObj.put("type_name", obj.type.name_fr);
            jsonObj.put("type_icon", obj.type.material_icon);
            jsonObj.put("title", obj.title);

            if (obj instanceof StaticObject) {
                jsonObj.put("is_calib_point", ((StaticObject) obj).isCalibPoint);
                jsonObj.put("mag", ((StaticObject) obj).mag);
                jsonObj.put("ra", ((StaticObject) obj).RA_j2000);
                Logcat("DEC " + ((StaticObject) obj).DEC_j2000);
                jsonObj.put("dec", ((StaticObject) obj).DEC_j2000);
            } else if (obj instanceof Satellite) {
                jsonObj.put("norad", ((Satellite) obj).noradId);
            }
            return jsonObj;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * <p>Converts an angle in radians to the DMS format (Degrees / arc Minutes / arc Seconds), used to display a
     * <b>Declination (Dec)</b>, for example <code>+41° 16' 09.0"</code>.</p>
     *
     * <p>The Dec works like a celestial latitude: it goes from -90° (south celestial pole) to +90° (north
     * celestial pole). The DMS format divides a degree into 60 arc minutes, and each arc minute into 60 arc
     * seconds: not to be confused with the minutes and seconds of time used for the RA (see
     * {@link #radToHMS}).</p>
     *
     * <p>For display only (UI, logs). Never convert back from DMS to do calculations: all internal calculations
     * (Orekit, calibration, Alt/Az transformations) must stay in radians.</p>
     *
     * @param angleRad the angle, in radians
     * @return the angle in DMS format, always with its sign
     */
    public static String radToDMS(double angleRad) {
        double angleDeg = Math.toDegrees(angleRad);

        String sign = angleDeg < 0 ? "-" : "+";
        angleDeg = Math.abs(angleDeg);

        int degrees = (int) angleDeg;
        double minutesFull = (angleDeg - degrees) * 60.0;
        int minutes = (int) minutesFull;
        double seconds = (minutesFull - minutes) * 60.0;

        return String.format("%s%d° %02d' %04.1f\"", sign, degrees, minutes, seconds);
    }

    /**
     * <p>Converts an angle in radians to the HMS format (Hours / Minutes of time / Seconds of time), used to
     * display a <b>Right Ascension (RA)</b>, for example <code>0h 42m 44.3s</code>.</p>
     *
     * <p>The RA is traditionally given in hours rather than degrees, because it is historically linked to
     * sidereal time: the sky seems to make a full 360° rotation in 24 h, so 1 h = 15°. It goes from 0 h to 24 h
     * and is never negative (unlike the Dec, see {@link #radToDMS}).</p>
     *
     * <p>For display only (UI, logs). Never convert back from HMS to do calculations: all internal calculations
     * (Orekit, calibration, Alt/Az transformations) must stay in radians.</p>
     *
     * @param angleRad the angle, in radians (a negative angle is brought back between 0 and 2π)
     * @return the angle in HMS format
     */
    public static String radToHMS(double angleRad) {
        double angleDeg = Math.toDegrees(angleRad);
        if (angleDeg < 0) angleDeg += 360.0; // The RA is always positive: 0-360°

        double hoursFull = angleDeg / 15.0; // 360° / 24h = 15°/h

        int hours = (int) hoursFull;
        double minutesFull = (hoursFull - hours) * 60.0;
        int minutes = (int) minutesFull;
        double seconds = (minutesFull - minutes) * 60.0;

        return String.format("%dh %02dm %04.1fs", hours, minutes, seconds);
    }

    /**
     * <p>Converts the J2000 coordinates of a fixed object into the coordinates of the current date.</p>
     *
     * <p>Because of precession and nutation, the Earth's axis slowly moves, and the J2000 coordinates shift over
     * time. The direction of the object is turned into a unit vector, transformed with Orekit from the
     * <code>EME2000</code> frame (J2000) to the <code>TOD</code> frame (true equator and equinox of date), then turned
     * back into a right ascension and a declination.</p>
     *
     * @param raJ2000  right ascension at the J2000 epoch, in radians
     * @param decJ2000 declination at the J2000 epoch, in radians
     * @return <code>{ra, dec}</code> of the current date, in radians, with <code>ra</code> between 0 and 2π
     */
    private static double[] dso_j2000toOfDate(double raJ2000, double decJ2000) {
        AbsoluteDate now = new AbsoluteDate(
            new Date(),
            TimeScalesFactory.getUTC()
        );

        double cosDec = Math.cos(decJ2000);
        Vector3D vJ2000 = new Vector3D(
            cosDec * Math.cos(raJ2000),
            cosDec * Math.sin(raJ2000),
            Math.sin(decJ2000)
        );

        Frame eme2000 = FramesFactory.getEME2000();
        Frame trueOfDate = FramesFactory.getTOD(false);

        Transform t = eme2000.getTransformTo(trueOfDate, now);
        Vector3D vDate = t.transformVector(vJ2000);

        double ra = Math.atan2(vDate.getY(), vDate.getX());
        if (ra < 0) {
            ra += 2.0 * Math.PI;
        }
        double r = vDate.getNorm();
        double dec = Math.asin(vDate.getZ() / r);

        return new double[]{ra, dec};
    }

    // OREKIT //
    /**
     * <p>Installs the data needed by the Orekit library (leap seconds, Earth orientation, ephemerides...).</p>
     *
     * <p>The <code>orekit_data.zip</code> archive embedded in the raw resources is copied once to the internal storage
     * of the app, then registered as the data source of Orekit. It must be called before any Orekit calculation.</p>
     *
     * @param activity the activity of the host app
     * @throws Exception if the resource cannot be found or copied
     */
    public static void OreKitInstaller(Activity activity) throws Exception {
        Context ctx = activity.getApplicationContext();
        File internalDir = ctx.getFilesDir();
        File out_orekitZip = new File(internalDir, "orekit_data.zip");

        if (!out_orekitZip.exists()) {
            int resId = Utils.getRessourceIdWithoutR(ctx, "orekit_data", "raw");
            InputStream in = ctx.getResources().openRawResource(resId);

            // Create the destination file in the internal storage: /data/user/0/[PKG]/files/
            OutputStream out = Files.newOutputStream(out_orekitZip.toPath());

            // Standard copy buffer
            byte[] buffer = new byte[8192];
            int read;
            // Raw copy of the zip, without any change
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            // Close the streams
            out.flush();
            out.close();
            in.close();
        }

        DataProvidersManager manager = DataContext.getDefault().getDataProvidersManager();
        manager.addProvider(new ZipJarCrawler(out_orekitZip));
    }

    /*public static void OreKitStressTest() throws Exception {
        // Get the UTC time scale from Orekit
        TimeScale utc = TimeScalesFactory.getUTC();

        // Get the TAI time scale (depends on the leap seconds)
        TimeScale tai = TimeScalesFactory.getTAI();

        // Get the TDB time scale (also depends on the time pipeline)
        TimeScale tdb = TimeScalesFactory.getTDB();

        // Get the current system instant (UTC)
        Instant now = Instant.now();

        // Extract the epoch seconds (UTC) from the Instant
        long epochSeconds = now.getEpochSecond();

        // Extract the nanoseconds (fraction of a second) from the Instant
        int nanos = now.getNano();

        // Convert epochSeconds to milliseconds to build a readable date
        long epochMillis = epochSeconds * 1000L + (nanos / 1_000_000L);

        // Create a java.util.Date from the epoch in ms
        java.util.Date javaDate = new java.util.Date(epochMillis);

        // Convert the java.util.Date to an Orekit date through calendar components
        // (Orekit prefers DateComponents/TimeComponents: this is the stable constructor)
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTime(javaDate);

        // UTC year
        int year = cal.get(java.util.Calendar.YEAR);
        // UTC month (Calendar is 0-based, hence +1)
        int month = cal.get(java.util.Calendar.MONTH) + 1;
        // UTC day
        int day = cal.get(java.util.Calendar.DAY_OF_MONTH);
        // UTC hour
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        // Minute UTC
        int minute = cal.get(java.util.Calendar.MINUTE);
        // UTC second
        int second = cal.get(java.util.Calendar.SECOND);
        // Fraction of a second, in seconds (double)
        double fractional = nanos / 1e9;


        // Build the date components (YYYY-MM-DD)
        DateComponents dc = new DateComponents(year, month, day);

        // Build the time components (hh:mm:ss.sss)
        TimeComponents tc = new TimeComponents(hour, minute, second + fractional);

        // Single physical instant
        AbsoluteDate dateUtc = new AbsoluteDate(dc, tc, utc);

        // Components of this instant in UTC
        DateTimeComponents utcComp = dateUtc.getComponents(utc);

        // TAI conversion: throws an exception if the leap seconds are missing
        DateTimeComponents taiComp = dateUtc.getComponents(tai);

        // TDB conversion: also throws an exception if the time pipeline is broken
        DateTimeComponents tdbComp = dateUtc.getComponents(tdb);

        // Log the dates with Logcat, not System.out
        Logcat("UTC = " + utcComp, 0);
        Logcat("TAI = " + taiComp, 0);
        Logcat("TDB = " + tdbComp, 0);

        // Get the ITRF terrestrial frame (forces the use of the EOP/IERS data)
        Frame itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);

        // Get the ICRF inertial frame
        Frame icrf = FramesFactory.getICRF();

        // Force an ITRF -> ICRF transformation at the given date
        // Throws an exception if the EOP data is missing or not loaded
        itrf.getTransformTo(icrf, dateUtc);

        // If we get here, the time and EOP data are OK
        Logcat("Orekit data check OK", 0);
    }*/
}
