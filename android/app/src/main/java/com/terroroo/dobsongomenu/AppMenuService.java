package com.terroroo.dobsongomenu;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static com.terroroo.dobsongomenu.DobsonGoMod.ACTIVE_SESSION;
import static com.terroroo.dobsongomenu.DobsonGoMod.mainHandler;
import static com.terroroo.dobsongomenu.UIController.Views.CONN_KEY_EDIT;
import static com.terroroo.dobsongomenu.UIController.Views.CONN_MAIN;
import static com.terroroo.dobsongomenu.Utils.Logcat;
import static com.terroroo.dobsongomenu.Utils.bytesToUTF8String;
import static com.terroroo.dobsongomenu.Utils.encodeAssetToBase64;
import static com.terroroo.dobsongomenu.Utils.getRessourceIdWithoutR;
import static com.terroroo.dobsongomenu.Utils.showToast;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * <p>Service that displays the floating DobsonGo menu over the host app.</p>
 *
 * <p>The menu is an HTML page (<code>telescope_menu.html</code>, in the assets) displayed in a {@link WebView}, inside
 * an overlay window (<code>TYPE_APPLICATION_OVERLAY</code>) that the user can drag around the screen.</p>
 *
 * <ul>
 *   <li><b>HTML to Java</b>: the page calls the methods of {@link JSInterface}, exposed as <code>Android</code> in
 *       JavaScript;</li>
 *   <li><b>Java to HTML</b>: the service updates the page by evaluating JavaScript functions
 *       (<code>android_...</code>);</li>
 *   <li><b>Native code to Java</b>: the static methods at the end of the class are called by the native C++ code
 *       when the user selects an object in the host app.</li>
 * </ul>
 *
 * <p>The service is started and bound by {@link DobsonGoMod}, which hides and shows the menu with
 * {@link #hideView()} and {@link #restoreView()} when the host app goes to the background or comes back.</p>
 */
@SuppressLint({"ClickableViewAccessibility", "MissingPermission"})
public final class AppMenuService extends Service {
    /**
     * <p>Binder returned to the clients of the service. As the service runs in the same process, it gives a direct
     * reference to it.</p>
     */
    public class LocalBinder extends Binder {
        /**
         * <p>Returns the instance of the service.</p>
         *
         * @return the service
         */
        public AppMenuService getService() { return AppMenuService.this; }
    }
    /** Binder returned by {@link #onBind(Intent)}. */
    private final IBinder binder = new LocalBinder();

    // Set in onCreate(), then never reassigned during the life of the service
    /** Window manager used to add the menu over the host app. */
    private WindowManager windowManager;
    /** Layout parameters of the overlay window: position, size and focus flags. */
    private WindowManager.LayoutParams params;
    /** WebView displaying the HTML menu. */
    public WebView webView;
    /**
     * <p>Weak reference to the WebView, used by the static methods called from the native code.</p>
     *
     * <p>Set once the page has loaded, and cleared when the service is destroyed.</p>
     */
    private static WeakReference<WebView> sWebV;

    /** Observation list: the objects added by the user, displayed in the menu. */
    public final RealAstronomy.SkyObjectSet QUEUE = new RealAstronomy.SkyObjectSet(this);
    /** Object currently selected in the host app, or <code>null</code>. */
    public static RealAstronomy.SkyObject currentSkyObj;

    /** Controller of the former native menu (see {@link #setupClickActions()}). Not initialised at the moment. */
    private UIController ui;
    /** Root view of the former native menu. Not used at the moment. */
    private View rootFrame;
    /**
     * <p><code>true</code> while the telescope is tracking an object: selecting another object of the list is then
     * refused.</p>
     */
    public boolean trackingActive = false;
    /** Stars used to calibrate the telescope. */
    public final Set<RealAstronomy.StaticObject> calibrationPoints = new HashSet<>();
    //private ActionListener listener;

    /** Weak reference to the controller of the former native menu. Not used at the moment. */
    private static WeakReference<UIController> uiRef;

    /** Former native menu: object displayed in the Horizons tab. */
    private static RealAstronomy.SkyObject horizonsViewObject;
    /** Former native menu: error displayed in the selection tab, or <code>null</code>. */
    private static Utils.DobsonGoException SELECTION_ERROR;
    /** Former native menu: focal length of the eyepiece entered by the user, in mm (0 to 100). */
    private static int eyepieceFocalLen;
    /** Former native menu: last Horizons search, so that the same search is not sent twice. */
    private static String horizonsResearchString;

    /**
     * <p>Called when {@link DobsonGoMod} binds the service.</p>
     *
     * @param intent the intent used to bind the service
     * @return the {@link LocalBinder}, which gives direct access to the service
     */
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    /**
     * <p>Creates the menu and displays it over the host app.</p>
     *
     * <ol>
     *   <li>prepares the overlay window: size adapted to its content, top-left corner of the screen, not focusable, so
     *       that the keyboard and the touches outside the menu still go to the host app;</li>
     *   <li>creates the WebView, enables JavaScript and the DOM storage, loads <code>telescope_menu.html</code> and
     *       exposes {@link JSInterface} under the name <code>Android</code>;</li>
     *   <li>makes the background of the WebView transparent, adds it to the window and enables dragging
     *       ({@link #setupDrag()}).</li>
     * </ol>
     *
     * <p>Once the page has loaded, {@link BLEManager} is initialised and the JavaScript function
     * <code>android_init</code> receives the icon of the menu (in Base64) and whether the connection key still has
     * to be entered.</p>
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onCreate() {
        super.onCreate();
        Logcat("AppMenuService started", 0);
        Context ctx = this;

        params = new WindowManager.LayoutParams(
            WRAP_CONTENT,
            WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 550;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        webView = new WebView(ctx);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.loadUrl("file:///android_asset/telescope_menu.html");
        webView.addJavascriptInterface(new JSInterface(this), "Android");
        webView.setWebViewClient(new WebViewClient() {
            /**
             * <p>Called once the HTML page has loaded: initialises Bluetooth and the JavaScript side of the menu.</p>
             *
             * @param view the WebView
             * @param url  the URL of the loaded page
             */
            @Override
            public void onPageFinished(WebView view, String url) {
                BLEManager.init(AppMenuService.this);
                sWebV = new WeakReference<>(webView);
                webView.evaluateJavascript(
                    String.format("javascript:android_init(\"%s\", %s)", encodeAssetToBase64(ctx, "menu_icon_round.HD.png"), DobsonGoMod.BT_CONN_KEY == null),
                    null
                );
                /*webView.evaluateJavascript(
                    String.format("javascript:android_connectedServer(\"%s\")", "CACA PROUT"),
                    null
                );*/
            }
        });
        webView.setClipToOutline(false);
        webView.setClipToPadding(false);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        windowManager.addView(webView, params);
        setupDrag();
    }

    /**
     * <p>Called when the service is stopped: disconnects the telescope and removes the menu from the screen.</p>
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Utils.Logcat("AppMenuService stopped", 0);
        BLEManager.disconnectServer();
        sWebV = null;
        hideView();
    }

    /**
     * <p>Removes the menu from the screen, if it is displayed. The WebView is kept, so that
     * {@link #restoreView()} can show it again in the same state.</p>
     */
    public void hideView() {
        if (windowManager != null && webView != null && webView.isAttachedToWindow()) windowManager.removeView(webView);
    }

    /**
     * <p>Shows the menu again after {@link #hideView()}.</p>
     *
     * <p>If the service is not initialised (window manager, layout parameters or WebView missing), the app is closed.</p>
     */
    public void restoreView() {
        if (windowManager == null || params == null || webView == null) {
            ExitActivity.exitApplication(
                this,
                "Impossible de restaurer le service du menu !",
                "Terminating process: failed to restore service (windowManager or webView or params are null)"
            );
        }
        if (!webView.isAttachedToWindow()) windowManager.addView(webView, params);
    }

    /**
     * <p>Finds an object in the observation list and checks its class.</p>
     *
     * <p>Objects are compared with {@link RealAstronomy.SkyObject#equals(Object)}: same name, same title or same
     * identifier.</p>
     *
     * @param skyObject the object to find
     * @param type      the expected class, for example <code>RealAstronomy.StaticObject.class</code>
     * @param <T>       the expected type
     * @return the object of the list, cast to the expected type
     * @throws NoSuchElementException  if the object is not in the list
     * @throws IllegalArgumentException if the object of the list is not of the expected type
     */
    public <T extends RealAstronomy.SkyObject> T retrieveObject(RealAstronomy.SkyObject skyObject, Class<T> type)
    throws NoSuchElementException, IllegalArgumentException {
        RealAstronomy.SkyObject skObj = QUEUE.stream()
            .filter(skyObject::equals)
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("Requested object is not in the queue"));

        if (!type.isInstance(skObj)) throw new IllegalArgumentException("Requested object is not a valid " + type.getSimpleName());
        return type.cast(skObj);
    }

    /**
     * <p>Removes an object from the observation list.</p>
     *
     * @param skyObject the object to remove
     * @throws NoSuchElementException if the object is not in the list
     */
    public void deleteObject(RealAstronomy.SkyObject skyObject) throws NoSuchElementException {
        if (!QUEUE.remove(skyObject)) throw new NoSuchElementException("Requested object is not in the queue");
    }

    //public void attachListener(ActionListener _listener) { listener = _listener; }

    /**
     * <p>Applies the current layout parameters to the window of the menu, after it has been moved, resized or its
     * focus has changed.</p>
     */
    public void updateView() { windowManager.updateViewLayout(webView, params); }

    /**
     * <p>Sets up the click listeners of the former native menu ({@link UIController}): opening and closing the menu,
     * connection key, eyepiece focal length and tracking button.</p>
     *
     * <p><b>Not used at the moment:</b> this method is never called and {@link #ui} is never initialised. The menu is
     * now the HTML page of the WebView, whose actions go through {@link JSInterface}.</p>
     */
    private void setupClickActions() {
        ui.fb.setOnClickListener(v -> {
            v.setVisibility(View.GONE);
            ui.floating_menu.setVisibility(View.VISIBLE);
            if (DobsonGoMod.BT_CONN_KEY == null) ui.changeView(CONN_KEY_EDIT, "");
            if (ui.currentView == CONN_KEY_EDIT) {
                changeFocus(true);
            }
            //viewGeoObject("88P/Howell", "88P/Howell", 1000046, false, 9);
        });

        ui.close_btn.setOnClickListener(v -> {
            //if (bleManager.isScanning) bleManager.stopScan();
            changeFocus(false);
            ui.fb.setVisibility(View.VISIBLE);
            ui.floating_menu.setVisibility(View.GONE);
            if (ui.ble_main_layout.getVisibility() == View.VISIBLE) {
                ui.changeView(CONN_MAIN);
            }
        });

        ui.scan_btn.setOnClickListener(v -> {
            /*//viewError("CACA");
            //bleManager.isConnected = true;
            //viewObject(2, "Pluton", "pluto", 90, Double.NaN, Double.NaN, null, null);
            if (!bleManager.isScanning) {
                if (!bleManager.btAdapter.isEnabled()) {
                    //listener.requestEnableBt(v.getContext(), bleManager.btAdapter);
                } else bleManager.startScan();
            } else bleManager.stopScan();*/
        });

        /*ui.object_tracking_layout.setOnClickListener(v -> {
            if (ui.object_info_layout.getVisibility() == View.GONE) {
                ui.tracking_arrow_indicator.animate()
                    .rotation(90f)
                    .setDuration(200)
                    .start();
                ui.object_info_layout.setVisibility(View.VISIBLE);
            } else {
                ui.tracking_arrow_indicator.animate()
                    .rotation(0f)
                    .setDuration(200)
                    .start();
                ui.object_info_layout.setVisibility(View.GONE);
            }
        });*/

        ui.validate_connkey_btn.setOnClickListener(v -> {
            String connkey = ui.connkey_input.getText().toString();
            if (connkey.isEmpty()) {
                showToast(v.getContext(), "Ne peut pas être vide !");
            } else {
                if (!connkey.equals(DobsonGoMod.BT_CONN_KEY)) {
                    try {
                        Prefs.storePref(v.getContext(), "BT_CONN_KEY", connkey);
                    } catch (Exception ignored) {}
                    DobsonGoMod.BT_CONN_KEY = connkey;
                    showToast(v.getContext(), "Nouvelle clé de connexion enregistrée !");
                }
                ui.connkey_input.getText().clear();
                changeFocus(false);
                ui.changeView(CONN_MAIN);
            }
        });

        ui.edit_connkey_btn.setOnClickListener(v -> {
            ui.changeView(CONN_KEY_EDIT, DobsonGoMod.BT_CONN_KEY);
            changeFocus(true);
        });

        ui.edit_focal_btn.setOnClickListener(v -> {
            if (ui.eyepiece_focal_input.isEnabled()) {
                String strFocalLength = ui.eyepiece_focal_input.getText().toString();
                if (strFocalLength.isEmpty()) {
                    showToast(v.getContext(), "Ne peut pas être vide !");
                } else {
                    int intFocalLength;
                    try { intFocalLength = Integer.parseInt(strFocalLength); }
                    catch (NumberFormatException e) {
                        showToast(v.getContext(), "Seulement des chiffres sont autorisés !");
                        return;
                    }
                    if (!(0 <= intFocalLength && intFocalLength <= 100)) {
                        showToast(v.getContext(), "La valeur doit être comprise entre 0 et 100 !");
                    } else {
                        eyepieceFocalLen = intFocalLength;
                        ui.setEditTextEnabledState(ui.eyepiece_focal_input, false);
                        changeFocus(false);
                        v.setBackgroundResource(getRessourceIdWithoutR(v.getContext(), "ic_menu_edit", "drawable", "android"));
                        showToast(v.getContext(), "Focale enregistrée !");
                    }
                }
            } else {
                ui.setEditTextEnabledState(ui.eyepiece_focal_input, true);
                changeFocus(true);
                v.setBackgroundResource(getRessourceIdWithoutR(v.getContext(), "ic_menu_add", "drawable", "android"));
            }
        });

        /*ui.view_selection_menu.setup((klass, index, animate) -> {
            RealAstronomy.SkyObject skyObject = ui.object_tracking_layout.getSkyObject();
            if (skyObject != null && skyObject.isTracking()) {
                showToast(klass.getContext(), "Veuillez désactiver le tracking d'abord !");
                return;
            }

            UIController.SelectionMenuTypes selectionIndex = UIController.SelectionMenuTypes.fromInt(index);
            assert selectionIndex != UIController.SelectionMenuTypes.NULL;
            if (selectionIndex == klass.currentSelection) return;
            else klass.currentSelection = selectionIndex;

            Button target = klass.viewButtons.get(index);
            int[] targetPos = new int[2];
            int[] parentPos = new int[2];

            target.getLocationOnScreen(targetPos);
            klass.getLocationOnScreen(parentPos);

            float absoluteX = (targetPos[0] - parentPos[0]) - klass.getPaddingLeft();
            if (animate) {
                klass.slideIndicator.animate()
                    .translationX(absoluteX)
                    .setDuration(200)
                    .start();
            } else klass.slideIndicator.setX(absoluteX);

            for (int i = 0; i < klass.viewButtons.size(); i++) {
                klass.viewButtons.get(i).setTextColor(
                    i == index ? 0xFFFFFFFF : 0xFFAAAAAA
                );
            }

            if (index == UIController.SelectionMenuTypes.HORIZONS.value) {
                ui.changeView(UIController.Views.SCOPE_SELECT_OBJECT, horizonsViewObject != null ? horizonsViewObject : null);
                ui.horizons_search_layout.setVisibility(View.VISIBLE);
                SELECTION_ERROR = null;
            } else if (index == UIController.SelectionMenuTypes.CURRENT_VIEW.value) {
                if (currentViewObject == null && SELECTION_ERROR == null) {
                    SELECTION_ERROR = new DobsonGoException("Aucun objet n'est actuellement sélectionné !");
                } else if (currentViewObject != null) {
                    ui.changeView(UIController.Views.SCOPE_SELECT_OBJECT, currentViewObject);
                    SELECTION_ERROR = null;
                }
                ui.horizons_search_layout.setVisibility(View.GONE);
            }

            if (SELECTION_ERROR != null) ui.changeView(UIController.Views.SCOPE_ERROR, SELECTION_ERROR.ui_msg, selectionIndex == UIController.SelectionMenuTypes.HORIZONS);
        }, UIController.SelectionMenuTypes.HORIZONS);*/

        /*ui.view_selection_menu.setupBtnClickListener(index -> {
            if (index == 0) {
                skyObject = null;
                ui.changeView(UIController.Views.SCOPE_SELECT_OBJECT, (Object)null);
                ui.horizons_search_layout.setVisibility(View.VISIBLE);
                SELECTION_ERROR = null;
            }

            if (index == 1) {
                if (currentViewObj == null && SELECTION_ERROR == null) {
                    SELECTION_ERROR =
                } else if (currentViewObj != null) {
                    skyObject = currentViewObj;
                    ui.changeView(UIController.Views.SCOPE_SELECT_OBJECT, skyObject);
                    SELECTION_ERROR = null;
                }
                ui.horizons_search_layout.setVisibility(View.GONE);
            }

            if (SELECTION_ERROR != null) ui.changeView(UIController.Views.SCOPE_ERROR, SELECTION_ERROR.ui_msg);
        });

        ui.edit_horizons_search_btn.setOnClickListener(v -> {
            if (skyObject != null && skyObject.isTracking) {
                showToast(v.getContext(), "Veuillez désactiver le tracking d'abord !");
                return;
            }
            if (ui.horizons_search_input.isEnabled()) {
                String researchString = ui.horizons_search_input.getText().toString();
                if (researchString.isEmpty()) {
                    horizonsResearchString = null;
                    ui.horizons_list.setVisibility(View.GONE, true);
                } else if (researchString.equals(horizonsResearchString)) {
                    ui.horizons_list.setVisibility(View.VISIBLE, false);
                } else {
                    horizonsResearchString = researchString;
                    ui.horizons_list.setVisibility(View.VISIBLE, true);
                    HorizonsParser.fetchOrbitalObj(horizonsResearchString, new HorizonsParser.HorizonsAPICallback() {
                        @Override
                        public void onSuccess(List<RealAstronomy.OrbitalObject> objects) {
                            for (RealAstronomy.OrbitalObject object : objects) {
                                ui.horizons_list.addChildItem(object.name, object.horizonId, ctx -> {
                                    Logcat("cacaED: " + object.horizonId, 0);
                                });
                            }
                        }

                        @Override
                        public void onError(DobsonGoException e) {
                            if (e.code == 404) Logcat(Log.getStackTraceString(e), 1);
                            else Logcat(Log.getStackTraceString(e), 2);
                            ui.horizons_list.addEmpty(e.ui_msg);
                        }
                    });
                }
                ui.setEditTextEnabledState(ui.horizons_search_input, false);
                changeFocus(false);
                v.setBackgroundResource(getRessourceIdWithoutR(v.getContext(), "ic_menu_edit", "drawable", "android"));
            } else {
                ui.horizons_list.setVisibility(View.GONE, false);
                ui.setEditTextEnabledState(ui.horizons_search_input, true);
                changeFocus(true);
                v.setBackgroundResource(getRessourceIdWithoutR(v.getContext(), "ic_menu_search", "drawable", "android"));
            }
        });*/

        ui.object_tracking_layout.setOnClickListener(klass -> {
            RealAstronomy.SkyObject trackerSkyObject = klass.getSkyObject();
            assert trackerSkyObject != null; // Cannot be null: the listener is only enabled when an object is set
            if (ui.track_error_layout.getVisibility() == View.VISIBLE) ui.track_error_layout.setVisibility(View.GONE); // for cumulative errors

            klass.trackObjectBtn.setText("Chargement...");
            klass.trackObjectBtn.setBackgroundColor(0xFF0066cc);

            if (!trackerSkyObject.isTracking()
                && trackerSkyObject instanceof RealAstronomy.OrbitalObject
                && ((RealAstronomy.OrbitalObject) trackerSkyObject).K_Elements.isEmpty()
            ) {
                RealAstronomy.OrbitalObject orbitalObject = (RealAstronomy.OrbitalObject) trackerSkyObject;
                HorizonsParser.fetchKElem(orbitalObject, new HorizonsParser.HorizonsAPICallback() {
                    @Override
                    public void onSuccess() {
                        Logcat("orbital elements:" + orbitalObject.K_Elements.size(), 0);
                        for (RealAstronomy.KeplerianSnapshot element : orbitalObject.K_Elements) {
                            Logcat(element.JDTDB + " | " + element.EC, 0);
                        }
                        orbitalObject.changeTrackingState(true);
                    }

                    @Override
                    public void onError(Utils.DobsonGoException e) {
                        if (e.code == 404) Logcat(Log.getStackTraceString(e), 1);
                        else Logcat(Log.getStackTraceString(e), 2);
                        ui.changeView(UIController.Views.SCOPE_ERROR, e.ui_msg, true);
                        orbitalObject.changeTrackingState(false);
                    }
                });
            } else {
                trackerSkyObject.changeTrackingState(!trackerSkyObject.isTracking());
                if (!trackerSkyObject.isTracking() /*&& ui.view_selection_menu.currentSelection == UIController.SelectionMenuTypes.CURRENT_VIEW*/) {
                    if (currentSkyObj == null) {
                        ui.changeView(UIController.Views.SCOPE_SELECT_OBJECT, (Object)null);
                        /*ui.view_selection_menu.triggerChangeListener(
                            ((UIController.SelectionMenuTypes) ui.view_selection_menu.currentSelection).value
                        );*/
                    } else if (trackerSkyObject.id != currentSkyObj.id) {
                        ui.changeView(UIController.Views.SCOPE_SELECT_OBJECT, currentSkyObj);
                    }
                }
            }
        }, UIController.ObjectTrackerLayout.ListenerType.TRACK);
    }

    /**
     * <p>Gives the keyboard focus to the menu, or back to the host app.</p>
     *
     * <p>By default, the window of the menu is not focusable (<code>FLAG_NOT_FOCUSABLE</code>), so that the host app
     * keeps receiving the keyboard. The menu must take the focus while the user types in one of its text fields, then
     * give it back.</p>
     *
     * <p>Nothing is done if the window is already in the requested state.</p>
     *
     * @param setMenuForeground <code>true</code> to give the focus to the menu, <code>false</code> to give it back to
     *                          the host app
     */
    public void changeFocus(boolean setMenuForeground) {
        // FLAG_NOT_FOCUSABLE tells Android that the window cannot receive the keyboard focus.
        // The window is therefore focused when this flag is absent from the flags (the & gives 0).

        boolean isFocused = (params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
        if (setMenuForeground && isFocused) return;
        if (!setMenuForeground && !isFocused) return;

        if (setMenuForeground) {
            params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        } else {
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        }
        updateView();
    }

    /**
     * <p>Allows the user to move the menu by dragging it.</p>
     *
     * <ul>
     *   <li>if the finger moves by more than 10 pixels, the touch is a <b>drag</b>: the window follows the finger;</li>
     *   <li>otherwise, the touch is a <b>tap</b>: the touch listener is removed for a moment, and a press and a release
     *       are simulated at the same place, so that the HTML page receives the click. The listener is then set up
     *       again.</li>
     * </ul>
     */
    private void setupDrag() {
        final float[] touchStartX = new float[1];
        final float[] touchStartY = new float[1];
        final int[] startX = new int[1];
        final int[] startY = new int[1];
        final boolean[] isMoved = new boolean[1];

        webView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isMoved[0] = false;
                    touchStartX[0] = event.getRawX();
                    touchStartY[0] = event.getRawY();
                    startX[0] = params.x;
                    startY[0] = params.y;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - touchStartX[0];
                    float dy = event.getRawY() - touchStartY[0];
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isMoved[0] = true;
                    params.x = startX[0] + (int) dx;
                    params.y = startY[0] + (int) dy;
                    updateView();
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!isMoved[0]) {
                        webView.setOnTouchListener(null);

                        long t = SystemClock.uptimeMillis();
                        MotionEvent evDown = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, event.getX(), event.getY(), 0);
                        MotionEvent evUp = MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_UP, event.getX(), event.getY(), 0);
                        webView.dispatchTouchEvent(evDown);
                        webView.dispatchTouchEvent(evUp);
                        evDown.recycle();
                        evUp.recycle();

                        setupDrag();
                    }
                    return false;
            }
            return false;
        });
    }

    // ############## Called from native code ############## //
    /**
     * <p>Called from the native code when the object selected in the host app cannot be read.</p>
     *
     * <p>The error is written to Logcat and a message is shown to the user.</p>
     *
     * @param proxy_id identifier written at the start of the log message, to know where the error comes from
     * @param error    technical description of the error, written to Logcat
     * @param ui_msg   message shown to the user
     */
    public static void reportSelectionError(String proxy_id, String error, String ui_msg) {
        Logcat(String.format("%s: %s", proxy_id, error), 2);
        WebView webvRef = sWebV.get();
        if (webvRef != null) showToast(webvRef.getContext(), ui_msg);
    }

    /**
     * <p>Called from the native code when the user selects an object in the host app.</p>
     *
     * <p>An object of the right class is created from the type, and stored in {@link #currentSkyObj}:</p>
     * <ul>
     *   <li>satellite: {@link RealAstronomy.Satellite}, from its TLE;</li>
     *   <li>fixed object (star, constellation, deep-sky object, meteor shower): {@link RealAstronomy.StaticObject},
     *       from its J2000 coordinates;</li>
     *   <li>planet, dwarf planet, asteroid or comet: {@link RealAstronomy.OrbitalObject};</li>
     *   <li>unknown type: the error is logged and the selection is cancelled.</li>
     * </ul>
     *
     * <p>The menu is then updated according to the state of the telescope, which must be connected:</p>
     * <ul>
     *   <li><b>not calibrated</b>: only stars can be selected. For a star, the card of the object is displayed with a
     *       button to calibrate the telescope on it; any other object cancels the selection;</li>
     *   <li><b>calibrated</b>: a button to add the object to the observation list is displayed.</li>
     * </ul>
     *
     * @param _type    type of the object, as the numeric value of {@link RealAstronomy.SkyObjectType}
     * @param title    name displayed to the user
     * @param name     internal name of the object in the host app
     * @param id       identifier of the object in the host app
     * @param isMoon   <code>true</code> if the object is a natural satellite (orbital objects only)
     * @param RAj2000  right ascension at the J2000 epoch, in radians (fixed objects only)
     * @param DECj2000 declination at the J2000 epoch, in radians (fixed objects only)
     * @param mag      apparent magnitude (fixed objects only)
     * @param NoradId  NORAD catalogue number (satellites only)
     * @param Tle1     first line of the TLE (satellites only)
     * @param Tle2     second line of the TLE (satellites only)
     */
    public static void onObjectSelected(
        int _type, String title, String name, int id, boolean isMoon,
        double RAj2000, double DECj2000, double mag,
        int NoradId, String Tle1, String Tle2
    ) {
        RealAstronomy.SkyObjectType type = RealAstronomy.SkyObjectType.fromInt(_type);
        if (type == RealAstronomy.SkyObjectType.None) {
            Logcat(String.format("Error : Object type for '%s' is undefined", title), 2);
            onObjectUnselected();
        } else {
            WebView webvRef = sWebV.get();
            assert webvRef != null;

            if (type == RealAstronomy.SkyObjectType.Satellite) currentSkyObj = new RealAstronomy.Satellite(title, name, id, NoradId, Tle1, Tle2);
            else if (type.is_static) currentSkyObj = new RealAstronomy.StaticObject(type, title, name, id, RAj2000, DECj2000, mag);
            else if (type == RealAstronomy.SkyObjectType.Planet
                || type == RealAstronomy.SkyObjectType.Asteroid
                || type == RealAstronomy.SkyObjectType.Dwarf
                || type == RealAstronomy.SkyObjectType.Comet
            ) currentSkyObj = new RealAstronomy.OrbitalObject(type, title, name, id, isMoon);
            else {
                Logcat(String.format("Unknown object type selected '%s'. Unable to perform selection request!", type.name), 2);
                onObjectUnselected();
                return;
            }

            BLEManager.BLEServer server = BLEManager.getServer();
            assert server != null;
            if (!server.calibrated && currentSkyObj.type == RealAstronomy.SkyObjectType.Star) {
                mainHandler.post(() -> webvRef.evaluateJavascript(
                    String.format("javascript:android_toggleObjectCard(true, %s);", RealAstronomy.serializeSkyObject(currentSkyObj)) +
                          String.format("btn_add_calib.querySelector('#add-calib-text').textContent = \"Calibrer %s\";", currentSkyObj.title) +
                          "add_queue_footer.classList.remove('hidden');" +
                          "btn_add_calib.classList.remove('hidden')",
                    null
                ));
            } else if (!server.calibrated) onObjectUnselected();
            else {
                mainHandler.post(() -> webvRef.evaluateJavascript(
                    "javascript:btn_add_from_app.classList.remove('hidden');" +
                          String.format("btn_add_from_app.querySelector('#add-from-app-label').textContent = \"Ajouter '%s' à la liste\";", title) +
                          "refreshSize()",
                    null
                ));
            }
        }
        //Logcat(currentViewObject.title + " (" + currentViewObject.type.name + ") | " + currentViewObject.name, 0);
        //Logcat("RA J2000: " + RealAstronomy.formatRA(RA_j2000_rad) + " | DEC J2000: " + RealAstronomy.formatDEC(DEC_j2000_rad), 0);
        //Logcat("RA OfDate: " + RealAstronomy.formatRA(currentViewObj.RA_ofDate) + " | DEC OfDate: " + RealAstronomy.formatDEC(currentViewObj.DEC_ofDate), 0);
        //Logcat("Tle1: " + currentViewObj.Tle1, 0);
        //Logcat("Tle2: " + currentViewObj.Tle2, 0);
        //Logcat("HorizonId: " + currentViewObj.horizonId, 0);
    }

    /**
     * <p>Called when no object is selected any more in the host app, or when the selection is cancelled.</p>
     *
     * <p>{@link #currentSkyObj} is cleared, and the menu hides the card of the object (telescope not calibrated) or the
     * button to add it to the list (telescope calibrated).</p>
     */
    public static void onObjectUnselected() {
        currentSkyObj = null;

        WebView webvRef = sWebV.get();
        assert webvRef != null;

        BLEManager.BLEServer server = BLEManager.getServer();
        assert server != null;
        if (!server.calibrated) {
            mainHandler.post(() -> webvRef.evaluateJavascript(
                "javascript:android_toggleObjectCard(false)",
                null
            ));
        } else {
            mainHandler.post(() -> webvRef.evaluateJavascript(
                "javascript:btn_add_from_app.classList.add('hidden');" +
                      "refreshSize()",
                null
            ));
        }
    }
    // ################################################################################ //
}
