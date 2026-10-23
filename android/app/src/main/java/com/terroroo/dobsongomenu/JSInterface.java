package com.terroroo.dobsongomenu;

import static com.terroroo.dobsongomenu.DobsonGoMod.ACTIVE_SESSION;
import static com.terroroo.dobsongomenu.DobsonGoMod.mainHandler;
import static com.terroroo.dobsongomenu.Utils.Logcat;

import android.Manifest;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;

import androidx.annotation.RequiresPermission;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Objects;

/**
 * <p>Bridge between the HTML menu and Java.</p>
 *
 * <p>An instance of this class is exposed to the JavaScript of the WebView under the name <code>Android</code>
 * (see {@link AppMenuService#onCreate()}): the page calls for example <code>Android.toggleScan(false)</code>.
 * Only the methods annotated with {@link JavascriptInterface} can be called from JavaScript.</p>
 *
 * <p>These methods are called on a background thread of the WebView, not on the main thread: each one therefore
 * posts its work to the main thread with <code>webView.post(...)</code> before touching the UI. The menu is then
 * updated by evaluating JavaScript functions of the page (<code>android_...</code>).</p>
 */
public final class JSInterface {
    /** The menu service, which owns the WebView and the observation list. */
    private final AppMenuService appService;
    /** Last Horizons search, so that the same search is not sent twice. Cleared once results are added to the list. */
    private String pastHzQuery;
    /** Not used at the moment. */
    private float screen;

    /**
     * <p>Creates the bridge for the given menu service.</p>
     *
     * @param service the menu service
     */
    public JSInterface(AppMenuService service) { this.appService = service; }

    /**
     * <p>Resizes the window of the menu to the size of the HTML content, each time this size changes.</p>
     *
     * @param width  width of the content, in CSS pixels
     * @param height height of the content, in CSS pixels
     */
    @JavascriptInterface
    public void resize(final int width, final int height) {
        appService.webView.post(() -> {
            Logcat("width: " + width + " | height: " + height, 0);
            ViewGroup.LayoutParams params = appService.webView.getLayoutParams();
            params.width = Utils.realScreenPx(appService.getApplicationContext(), width);
            params.height = Utils.realScreenPx(appService.getApplicationContext(), height);;
            appService.webView.setLayoutParams(params);
            appService.updateView();
        });
    }

    /**
     * <p>Saves the connection key entered by the user, used to authenticate with the telescope.</p>
     *
     * <p>The key is rejected if it is empty or contains spaces. If it differs from the current key, it is saved in
     * the preferences and in {@link DobsonGoMod#BT_CONN_KEY}. The menu then goes back to the connection screen and the
     * keyboard focus is given back to the host app.</p>
     *
     * @param newKey the new connection key
     */
    @JavascriptInterface
    public void editBTKey(final String newKey) {
        appService.webView.post(() -> {
            if (newKey.isEmpty()) {
                Utils.showToast(appService.getApplicationContext(), "Ne peut pas être vide !");
            } else if (newKey.matches(".*\\s.*")) {
                Utils.showToast(appService.getApplicationContext(), "Ne peut pas contenir d'espaces !");
            } else {
                if (!newKey.equals(DobsonGoMod.BT_CONN_KEY)) {
                    try {
                        Prefs.storePref(appService.getApplicationContext(), "BT_CONN_KEY", newKey);
                    } catch (Exception e) {
                        Logcat(Log.getStackTraceString(e), 2);
                        Utils.showToast(appService.getApplicationContext(), "Une erreur est survenue : impossible de modifier la clé de connexion");
                        return;
                    }
                    DobsonGoMod.BT_CONN_KEY = newKey;
                }
                Utils.showToast(appService.getApplicationContext(), "Clé enregistrée !");
                appService.changeFocus(false);
                appService.webView.evaluateJavascript(
                        "javascript:toggleScreenTransition('screen-1', 'screen-0');" +
                                "screen_0.querySelector('#key-input').value = '';" +
                                "screen_0.querySelector('.back-btn').style.display = 'block';" +
                                "refreshSize();",
                        null
                );
            }
        });
    }

    /**
     * <p>Starts or stops the search for telescopes. A connection key must have been saved first.</p>
     *
     * <ul>
     *   <li><b>Start</b>: if Bluetooth is off, the user is asked to turn it on. If a previous session is known
     *       ({@link DobsonGoMod#ACTIVE_SESSION}), no scan is started: the menu offers to reconnect to that telescope.
     *       Otherwise, the BLE scan starts;</li>
     *   <li><b>Stop</b>: if a scan is running, it is stopped.</li>
     * </ul>
     *
     * @param deleteActiveSession <code>true</code> to forget the previous session first, so that all telescopes
     *                            are searched
     */
    @JavascriptInterface
    public void toggleScan(final boolean deleteActiveSession) {
        appService.webView.post(() -> {
            if (deleteActiveSession) ACTIVE_SESSION = null;
            if (DobsonGoMod.BT_CONN_KEY == null) {
                Utils.showToast(appService.getApplicationContext(), "La clé de connexion n'a pas été enregistrée !");
                return;
            }
            if (!BLEManager.isScanning) {
                if (!BLEManager.isBtAdapterEnabled()) {
                    DobsonGoMod.requestEnableBt(appService.getApplicationContext());
                    return;
                }
                if (ACTIVE_SESSION != null) {
                    appService.webView.evaluateJavascript(
                        "javascript:android_toggleScan(false, false, 'Session trouvée !', true, true, true);" +
                              String.format("android_toggleReconnectBanner(true, '%s')", ACTIVE_SESSION),
                        null
                    );
                } else {
                    appService.webView.evaluateJavascript(
                        "javascript:android_toggleReconnectBanner(false, '?', null);" +
                              "android_toggleScan(true, true, 'Arrêter le scan', true, false, true)",
                        null
                    );
                    BLEManager.startScan();
                }
                appService.webView.evaluateJavascript(
                    "javascript:android_toggleError(false, 'ble-error', null)",
                    null
                );
            } else {
                BLEManager.stopScan();
                appService.webView.evaluateJavascript(
                    "javascript:android_toggleScan(false, true, 'Recommencer le scan', false, true, false);" +
                          "android_toggleError(false, 'ble-error', null)",
                    null
                );
            }
        });
    }

    /** Stops the scan, if one is running, when the user leaves the connection screen. */
    @JavascriptInterface
    public void closeScan() {
        appService.webView.post(() -> {
            if (BLEManager.isScanning) {
                BLEManager.stopScan();
                appService.webView.evaluateJavascript(
                    "javascript:android_toggleScan(false, true, 'Connecter via Bluetooth', true, true, false);" +
                          "android_toggleError(false, 'ble-error', null)",
                    null
                );
            }
        });
    }

    /** Connects to the telescope of the previous session ({@link DobsonGoMod#ACTIVE_SESSION}). */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @JavascriptInterface
    public void connectToActiveSession() {
        appService.webView.post(() -> {
            appService.webView.evaluateJavascript(
                "javascript:android_toggleReconnectBanner(false, '?', null)",
                null
            );
            assert ACTIVE_SESSION != null;
            connectToServer(ACTIVE_SESSION);
        });
    }

    /**
     * <p>Stops the scan and connects to the chosen telescope.</p>
     *
     * <p>The menu shows that the connection is in progress; the result is reported later by
     * {@link BLEManager.BLEServer}.</p>
     *
     * @param macAddress MAC address of the telescope
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @JavascriptInterface
    public void connectToServer(final String macAddress) {
        appService.webView.post(() -> {
            BLEManager.stopScan();
            String serverName = BLEManager.connectServer(appService, macAddress, macAddress.equals(ACTIVE_SESSION));
            appService.webView.evaluateJavascript(
                String.format("javascript:android_toggleScan(false, false, \"Connexion en cours à '%s'\", true, false, true)", serverName),
                null
            );
        });
    }

    /**
     * <p>Calibrates the telescope on the star currently selected in the host app.</p>
     *
     * <p><b>Temporary implementation:</b> the calibration system itself is not written yet, so the process is
     * simulated. After the countdown of the menu, the star is marked as a calibration point and added to the list,
     * then a random fake star is selected automatically a few seconds later, to test the calibration flow. Once the
     * telescope reports that it is calibrated, the button to confirm the calibration is displayed.</p>
     *
     * <p>Nothing is done if no object is selected or if the list is full.</p>
     */
    @JavascriptInterface
    public void calibratePoint() {
        appService.webView.post(() -> {
            if (AppMenuService.currentSkyObj == null) {
                Utils.showToast(appService.getApplicationContext(), "Vous devez d'abord sélectionner une étoile pour calibrer.");
            } else if (appService.QUEUE.size() + 1 > Utils.QUEUE_MAX_SIZE) {
                Utils.showToast(appService.getApplicationContext(), "La liste a atteint sa taille maximale. Veuillez retirer des éléments avant de continuer.");
            } else {
                appService.webView.evaluateJavascript(
                    "javascript:android_playCalibCountdown()",
                    null
                );
                mainHandler.postDelayed(() -> {
                    RealAstronomy.StaticObject staticSkObj = (RealAstronomy.StaticObject) AppMenuService.currentSkyObj;
                    // TODO: the real calibration system goes here
                    staticSkObj.isCalibPoint = true;
                    mainHandler.postDelayed(() -> {
                        appService.QUEUE.add(staticSkObj);
                        if (BLEManager.getServer().calibrated) {
                            appService.webView.evaluateJavascript(
                                "javascript:btn_confirm_calib.classList.remove('hidden');",
                                null
                            );
                        }
                        mainHandler.postDelayed(() -> AppMenuService.onObjectSelected(
                            1,
                            Utils.randomString(4).toUpperCase(),
                            Utils.randomString(6),
                            Utils.RANDOM.nextInt(500),
                            false,
                            0.186387,
                            0.720278,
                            5.6,
                            -1,
                            null,
                            null
                        ), 4000);
                    }, 3500);
                    AppMenuService.currentSkyObj = null;
                }, 1500);
            }
        });
    }

    /** Called when the user confirms that the calibration is complete. Not implemented yet. */
    @JavascriptInterface
    public void confirmCalibrationComplete() {

    }

    /*private static final Random random = new Random();
    private static final List<Pair<String, Integer>> fakeObjects = Arrays.asList(
            new Pair<>("Véga",          1),
            new Pair<>("Jupiter",       2),
            new Pair<>("Arcturus",      1),
            new Pair<>("M42 — Orion",   4),
            new Pair<>("ISS",           6),
            new Pair<>("Sirius",        1),
            new Pair<>("Mars",          2),
            new Pair<>("Saturne",       2),
            new Pair<>("Andromède",     4),
            new Pair<>("Polaris",       1),
            new Pair<>("Betelgeuse",    1),
            new Pair<>("Rigel",         1),
            new Pair<>("M13 — Hercule", 4),
            new Pair<>("Lune",          2),
            new Pair<>("Neptune",       2)
    );*/
    /**
     * <p>Adds the object selected in the host app ({@link AppMenuService#currentSkyObj}) to the observation list.</p>
     *
     * <p>The object is refused if:</p>
     * <ul>
     *   <li>the list is full ({@link Utils#QUEUE_MAX_SIZE});</li>
     *   <li>it is the moon of a planet other than the Earth: it is too small to be observed alone;</li>
     *   <li>it is the Sun (identifier <code>0</code>), for safety reasons.</li>
     * </ul>
     *
     * <p>A message tells the user whether the object was added or was already in the list.</p>
     */
    @JavascriptInterface
    public void addClickedObject() {
        appService.webView.post(() -> {
            assert AppMenuService.currentSkyObj != null;
            if (appService.QUEUE.size() + 1 > Utils.QUEUE_MAX_SIZE) {
                Utils.showToast(appService.getApplicationContext(), "La liste a atteint sa taille maximale. Veuillez retirer des éléments avant de continuer.");
            } else {
                if (AppMenuService.currentSkyObj instanceof RealAstronomy.OrbitalObject) {
                    RealAstronomy.OrbitalObject skObj = (RealAstronomy.OrbitalObject) AppMenuService.currentSkyObj;
                    if (skObj.isMoon && skObj.id != 31) { // 31: the Moon
                        Utils.showToast(appService.getApplicationContext(), "Il n'est pas possible de tracker les lunes seules pour des raisons évidentes de taille");
                        return;
                    }
                } else if (AppMenuService.currentSkyObj.id == 0) {
                    Utils.showToast(appService.getApplicationContext(), "Il n'est pas possible de tracker le Soleil pour des raisons évidentes de sécurité");
                    return;
                }
                if (appService.QUEUE.add(AppMenuService.currentSkyObj)) {
                    Utils.showToast(appService.getApplicationContext(), "Nouvel élément ajouté à la liste !");
                } else {
                    Utils.showToast(appService.getApplicationContext(), "L'objet est déjà dans la liste, il n'a pas été ajouté");
                }
                appService.webView.evaluateJavascript(
                    "javascript:btn_add_from_app.classList.add('hidden');" +
                          "android_refreshNoSelection();" +
                          "refreshSize()",
                    null
                );

                /*try {
                    Pair<String, Integer> fakeObj = fakeObjects.get(random.nextInt(fakeObjects.size()));
                    RealAstronomy.SkyObject obj;
                    RealAstronomy.SkyObjectType skType = RealAstronomy.SkyObjectType.fromInt(fakeObj.second);
                    if (skType == RealAstronomy.SkyObjectType.Satellite) {
                        obj = new RealAstronomy.Satellite(fakeObj.first, fakeObj.first, random.nextInt(), "caca", "caca");
                    } else if (skType.is_static) {
                        obj = new RealAstronomy.StaticObject(skType, fakeObj.first, fakeObj.first, random.nextInt(), 0, 0);
                    } else {
                        obj = new RealAstronomy.OrbitalObject(skType, fakeObj.first, fakeObj.first, random.nextInt());
                    }
                    if (appService.QUEUE.add(obj)) {
                        Utils.showToast(appService.getApplicationContext(), "Nouvel élément ajouté à la liste !");
                        appService.webView.evaluateJavascript(
                            String.format("javascript:android_addQueueElement(%s)", RealAstronomy.serializeSkyObject(obj)),
                            null
                        );
                    } else {
                        Utils.showToast(appService.getApplicationContext(), "L'objet été déjà présent, il n'a pas été ajouté");
                    }
                } catch (Exception e) {
                    Logcat(Log.getStackTraceString(e), 2);
                    Utils.showToast(appService.getApplicationContext(), "Une erreur est survenue : impossible d'ajouter l'élément à la liste");
                }*/
            }
        });
    }

    /**
     * <p>Removes an object from the observation list.</p>
     *
     * @param id   unique identifier of the object, as produced by {@link RealAstronomy#serializeSkyObject}:
     *             <code>"hz:&lt;Horizons identifier&gt;"</code> for an object added from Horizons, or the identifier
     *             of the host app otherwise
     * @param type type of the object, as the numeric value of {@link RealAstronomy.SkyObjectType}
     */
    @JavascriptInterface
    public void deleteObject(final String id, final int type) {
        appService.webView.post(() -> {
            appService.QUEUE.deleteId(id, RealAstronomy.SkyObjectType.fromInt(type));
            Utils.showToast(appService.getApplicationContext(), "L'élément a été supprimé !");
        });
    }

    /**
     * <p>Opens or closes the information card of an object of the list.</p>
     *
     * @param id   unique identifier of the object (see {@link #deleteObject(String, int)})
     * @param type type of the object, as the numeric value of {@link RealAstronomy.SkyObjectType}
     */
    @JavascriptInterface
    public void toggleObjectCard(final String id, final int type) {
        appService.webView.post(() -> {
            RealAstronomy.SkyObject selectedObj = appService.QUEUE.retrieveId(id, RealAstronomy.SkyObjectType.fromInt(type));
            assert selectedObj != null;
            if (selectedObj.is_viewed) {
                selectedObj.is_viewed = false;
                appService.webView.evaluateJavascript(
                    "javascript:android_toggleObjectCard(false)",
                    null
                );
            } else {
                selectedObj.is_viewed = true;
                appService.webView.evaluateJavascript(
                    String.format("javascript:android_toggleObjectCard(true, %s)", RealAstronomy.serializeSkyObject(AppMenuService.currentSkyObj)),
                    null
                );
            }
        });
    }

    /*@JavascriptInterface
    public void deleteQueueElement(final String jsonObject) {
        appService.webView.post(() -> {
            Logcat(jsonObject, 0);
            if (appService.trackingActive) {
                Utils.showToast(appService.getApplicationContext(), "Veuillez désactiver le tracking d'abord !");
                return;
            }
            try {
                RealAstronomy.SkyObject skObj = RealAstronomy.parseSkyObject(jsonObject);
                appService.webView.evaluateJavascript(
                    String.format("javascript:android_removeQueueElement(%s)", skObj.name),
                    (ignored) -> {
                        appService.deleteObject(skObj);
                        Utils.showToast(appService.getApplicationContext(), "Elément supprimé !");
                    }
                );
            } catch (Exception e) {
                Logcat(Log.getStackTraceString(e), 2);
                Utils.showToast(appService.getApplicationContext(), "Une erreur est survenue : impossible de supprimer l'élément de la liste");
            }
        });
    }*/

    /**
     * <p>Selects an object of the list to observe it. Refused while an object is being tracked.</p>
     *
     * @param item_name name of the object, passed back to the JavaScript function <code>android_selectItem</code>
     */
    @JavascriptInterface
    public void selectObservableItemFromQueue(final String item_name) {
        appService.webView.post(() -> {
            if (appService.trackingActive) {
                Utils.showToast(appService.getApplicationContext(), "Veuillez désactiver le tracking d'abord !");
            } else {
                appService.webView.evaluateJavascript(
                    String.format("javascript:android_selectItem(\"%s\")", item_name),
                    null
                );
            }
        });
    }

    /*@JavascriptInterface
    public void updateCalibPoint(final String jsonObject, final int index) {
        appService.webView.post(() -> {
            try {
                RealAstronomy.StaticObject calibPoint = appService.retrieveObject(
                    RealAstronomy.parseSkyObject(jsonObject),
                    RealAstronomy.StaticObject.class
                );
                if (calibPoint.type != RealAstronomy.SkyObjectType.Star) {
                    Logcat(String.format("Error: received object of type %s from webview", calibPoint.type.name), 2);
                    Utils.showToast(appService.getApplicationContext(), "La calibration n'est possible que sur des étoiles !");
                } else {
                    calibPoint.isCalibPoint = !calibPoint.isCalibPoint;
                    if (calibPoint.isCalibPoint) {
                        appService.calibrationPoints.add(calibPoint);

                        Utils.showToast(appService.getApplicationContext(), "Nouveau point de calibration ajouté !");
                    } else {
                        appService.calibrationPoints.remove(calibPoint);
                        Utils.showToast(appService.getApplicationContext(), "Le point de calibration a été enlevé");
                    }
                    appService.webView.evaluateJavascript(
                        String.format("javascript:android_editQueueList(%s, %s)", RealAstronomy.serializeSkyObject(calibPoint), index),
                        null
                    );
                    appService.webView.evaluateJavascript(
                        String.format("javascript:android_updateCalibrationData(%s)", appService.calibrationPoints.size()),
                        null
                    );
                }
            } catch (Exception e) {
                Logcat(Log.getStackTraceString(e), 2);
                Utils.showToast(appService.getApplicationContext(), "Une erreur est survenue : impossible d'ajouter l'élément de calibration à la liste");
            }
        });
    }*/

    /**
     * <p>Searches objects in the JPL Horizons database, from the search screen of the menu.</p>
     *
     * <p>The search is rejected if it is empty, contains spaces, or is the same as the previous one. Otherwise, the
     * previous results are cleared and {@link HorizonsParser#fetchOrbitalObj} is called. The results, or the error,
     * are passed to the JavaScript function <code>android_onHzResult</code>.</p>
     *
     * @param query the text typed by the user
     */
    @JavascriptInterface
    public void searchHorizons(final String query) {
        appService.webView.post(() -> {
            if (query.isEmpty()) {
                Utils.showToast(appService.getApplicationContext(), "Ne peut pas être vide !");
            } else if (query.matches(".*\\s.*")) {
                Utils.showToast(appService.getApplicationContext(), "Ne peut pas contenir d'espaces !");
            } else if (query.equals(pastHzQuery)) {
                Utils.showToast(appService.getApplicationContext(), "Votre recherche a déjà été effectuée !");
            } else {
                if (pastHzQuery != null) {
                    appService.webView.evaluateJavascript(
                        "javascript:android_clearPrevSearch();",
                        null
                    );
                }
                appService.webView.evaluateJavascript(
                    "javascript:android_toggleSearchState(true);",
                    null
                );
                pastHzQuery = query;
                HorizonsParser.fetchOrbitalObj(query, new HorizonsParser.HorizonsAPICallback() {
                    @Override
                    public void onSuccess(JSONArray objects) {
                        appService.webView.evaluateJavascript(
                            String.format("javascript:android_onHzResult(%s, 200, null)", objects.toString()),
                            null
                        );
                    }

                    @Override
                    public void onError(Utils.DobsonGoException e) {
                        if (e.code == 404) Logcat(Log.getStackTraceString(e), 1);
                        else {
                            String _e = Log.getStackTraceString(e);
                            if (_e.isEmpty()) _e = e.toString();
                            Logcat(_e, 2);
                        }
                        appService.webView.evaluateJavascript(
                            String.format("javascript:android_onHzResult([], %s, \"%s\")", e.code, e.ui_msg),
                            null
                        );
                    }
                });
            }
        });
    }

    /**
     * <p>Adds the Horizons results chosen by the user to the observation list.</p>
     *
     * <p>Nothing is added if the list would go over its maximum size. Each result becomes a
     * {@link RealAstronomy.OrbitalObject}; objects already in the list are ignored, and their number is given in the
     * message shown to the user. The search screen is then cleared and closed.</p>
     *
     * @param jsonHorizonsList JSON array of objects <code>{"name": ..., "hz_id": ...}</code>, as returned by
     *                         {@link HorizonsParser#fetchOrbitalObj}
     */
    @JavascriptInterface
    public void addHorizonsToQueue(final String jsonHorizonsList) {
        appService.webView.post(() -> {
            try {
                JSONArray hzObjects = new JSONArray(jsonHorizonsList);
                if (hzObjects.length() + appService.QUEUE.size() > Utils.QUEUE_MAX_SIZE) {
                    Utils.showToast(appService.getApplicationContext(), "La liste a atteint sa taille maximale. Veuillez retirer des éléments avant de continuer.");
                    return;
                }
                int doubled = 0;
                for (int i = 0; i < hzObjects.length(); i++) {
                    JSONObject obj = hzObjects.getJSONObject(i);
                    String title = obj.getString("name");
                    String hzId = obj.getString("hz_id");
                    RealAstronomy.OrbitalObject orbSkObj = new RealAstronomy.OrbitalObject(title, hzId);
                    if (!appService.QUEUE.add(orbSkObj)) doubled++;
                }
                Utils.showToast(appService.getApplicationContext(), String.format(
                        "%s élément(s) ajouté(s) à la liste !%s",
                        doubled > 0 ? hzObjects.length() - doubled : hzObjects.length(),
                        doubled > 0 ? String.format("\n(%s doublon(s) ignoré(s))", doubled) : ""
                ));
                appService.webView.evaluateJavascript(
                        "javascript:android_clearPrevSearch();" +
                              "hz_input.value = '';" +
                              "toggleHzScreen(false)",
                        null
                );
                pastHzQuery = null;
            } catch (Exception e) {
                Logcat(Log.getStackTraceString(e), 2);
                Utils.showToast(appService.getApplicationContext(), "Une erreur est survenue : impossible d'ajouter les éléments à la liste");
            }
        });
    }

    /**
     * <p>Gives the keyboard focus to the menu, or back to the host app, for example when a text field of the page
     * gains or loses the focus.</p>
     *
     * @param setMenuForeground <code>true</code> to give the focus to the menu, <code>false</code> to give it back
     *                          (see {@link AppMenuService#changeFocus(boolean)})
     */
    @JavascriptInterface
    public void requestFocus(final boolean setMenuForeground) {
        appService.webView.post(() -> appService.changeFocus(setMenuForeground));
    }

    /**
     * <p>Shows a short message (toast) to the user.</p>
     *
     * @param message the message to show
     */
    @JavascriptInterface
    public void showToast(final String message) {
        appService.webView.post(() -> Utils.showToast(appService.getApplicationContext(), message));
    }

    /**
     * <p>Writes a message of the page to Logcat.</p>
     *
     * @param message the message to log
     * @param code    the log level (see {@link Utils#Logcat(String, int)})
     */
    @JavascriptInterface
    public void logkat(final String message, final int code) {
        appService.webView.post(() -> Utils.Logcat(message, code));
    }
}