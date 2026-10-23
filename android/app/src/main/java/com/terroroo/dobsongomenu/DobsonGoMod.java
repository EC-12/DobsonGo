package com.terroroo.dobsongomenu;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.orekit.control.indirect.adjoint.cost.LogarithmicBarrierCartesianFuel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>Entry point of the DobsonGo mod.</p>
 *
 * <p>It is created with the activity of the host app, and prepares everything the telescope menu needs:</p>
 * <ol>
 *   <li>it loads the native C++ library of the mod;</li>
 *   <li>it asks the user for the required permissions: first the permission to draw over other apps
 *       (overlay), then the Bluetooth permissions (or the location permission on Android 11 and below);</li>
 *   <li>once everything is granted, it initialises the preferences, the Orekit library and the native part
 *       of the mod ({@link #init(Activity)});</li>
 *   <li>finally, it starts and binds {@link AppMenuService}, the service that displays the floating menu.</li>
 * </ol>
 *
 * <p>It also follows the lifecycle of the host activity, to hide the menu when the app goes to the background
 * and show it again when it comes back.</p>
 */
@SuppressLint("MissingPermission")
public final class DobsonGoMod {
    // --- Native methods, implemented in the C++ library --- //
    /**
     * <p>Initialises the native C++ part of the mod.</p>
     *
     * @param activityCtx application context of the host app
     * @return <code>0</code> on success, or an error code otherwise
     */
    private static native int c_init(Context activityCtx);
    // ------ //

    /** Handler of the main thread, used to update the UI from background threads (Bluetooth, network). */
    public static final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** MAC address of the telescope of the current or last session, used to reconnect quickly. <code>null</code> if there is none. */
    public static String ACTIVE_SESSION;
    /** Connection key entered by the user, used to answer the telescope's authentication challenge. <code>null</code> if not set. */
    public static String BT_CONN_KEY;

    /** Request code: Bluetooth or location permissions. */
    private static final int REQ_IN_APP_PERMS = 1212;
    /** Request code: permission to draw over other apps. */
    private static final int REQ_OVERLAY_PERM = 666;
    /** Request code: request to turn Bluetooth on. */
    private static final int REQ_ENABLE_BT = 42;
    /**
     * <code>true</code> while a permission screen is open, between the moment the activity is paused and the moment it resumes.
     */
    private static boolean requestingPermRN = false;
    /** Code of the request currently in progress, or <code>0</code> if there is none. */
    private static int requestingPermCode;
    /** Number of times the in-app permissions can still be requested before the app is closed. */
    private static int requestAskingCount = 2;
    /** Permissions that still have to be granted, filled by {@link #checkInAppPerms(Context)}. */
    private static final List<String> missingInAppPerms = new ArrayList<>();

    /** The menu service, once it is bound. <code>null</code> before that. */
    private AppMenuService menuService;
    /** Connection to {@link AppMenuService}: keeps a reference to the service while it is bound. */
    private final ServiceConnection connection = new ServiceConnection() {
        /**
         * <p>Called when the menu service is bound: keeps a reference to it.</p>
         *
         * @param name    component name of the service
         * @param service binder returned by the service
         */
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AppMenuService.LocalBinder binder = (AppMenuService.LocalBinder) service;
            menuService = binder.getService();
        }

        /**
         * <p>Called when the connection to the service is lost: the reference is cleared.</p>
         *
         * @param name component name of the service
         */
        @Override
        public void onServiceDisconnected(ComponentName name) { menuService = null; }

        /**
         * <p>Called when the binding can no longer be used: the reference is cleared.</p>
         *
         * @param name component name of the service
         */
        @Override
        public void onBindingDied(ComponentName name) { menuService = null; }
    };

    /**
     * <p>Asks the user to turn Bluetooth on, with the standard Android dialog.</p>
     *
     * <p>The answer is handled when the host activity resumes: if Bluetooth is now on, the scan for telescopes
     * starts.</p>
     *
     * @param ctx context used to start the request
     */
    public static void requestEnableBt(Context ctx) {
        Intent btReqIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        btReqIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        requestingPermCode = REQ_ENABLE_BT;
        ctx.startActivity(btReqIntent);
    }

    /**
     * <p>Creates the mod and starts the permission flow.</p>
     *
     * <p>The steps are as follows:</p>
     * <ul>
     *   <li>if the app is not allowed to draw over other apps, the overlay settings screen is opened;</li>
     *   <li>otherwise, if Bluetooth permissions are missing, they are requested;</li>
     *   <li>otherwise, the mod is initialised directly with {@link #init(Activity)}.</li>
     * </ul>
     *
     * <p>The answers are handled in <code>onActivityResumed</code>, when the user comes back to the app.
     * The in-app permissions are requested at most twice: if they are refused again, the app is closed.
     * The app is also closed if the overlay permission is refused.</p>
     *
     * @param activity the activity of the host app
     */
    public DobsonGoMod(Activity activity) {
        System.loadLibrary("dobsongomenu"); // Native C++ library of the mod
        Context ctx = activity.getApplicationContext();
        Application app = activity.getApplication();

        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            // Lifecycle events not used by the mod
            @Override public void onActivityCreated(@NonNull Activity a, android.os.Bundle b) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull android.os.Bundle b) {}
            @Override public void onActivityDestroyed(@NonNull Activity a) {}
            /** Shows the menu again when the host app comes back to the foreground. */
            @Override public void onActivityStarted(@NonNull Activity _activity) {
                if (menuService != null) menuService.restoreView();
            }
            /** Hides the menu when the host app goes to the background. */
            @Override public void onActivityStopped(@NonNull Activity _activity) {
                if (menuService != null) menuService.hideView();
            }
            /** Hides the menu while a permission screen or dialog is displayed. */
            @Override public void onActivityPaused(@NonNull Activity _activity) {
                if (requestingPermCode != 0) {
                    requestingPermRN = true;
                    if (menuService != null) menuService.hideView();
                }
            }
            /**
             * <p>Handles the answer of the user when coming back from a permission screen or dialog:</p>
             * <ul>
             *   <li><b>in-app permissions</b>: initialises the mod if they are granted, asks again or closes the app otherwise;</li>
             *   <li><b>overlay permission</b>: continues with the in-app permissions if it is granted, closes the app otherwise;</li>
             *   <li><b>Bluetooth activation</b>: starts the scan if Bluetooth is now on, shows a message otherwise.</li>
             * </ul>
             *
             * @param _activity the resumed activity
             */
            @Override public void onActivityResumed(@NonNull Activity _activity) {
                if (requestingPermRN) {
                    if (menuService != null) menuService.restoreView();
                    requestingPermRN = false;
                    Context ctx = _activity.getApplicationContext();
                    if (requestingPermCode == REQ_IN_APP_PERMS) {
                        requestingPermCode = 0;
                        if (!checkInAppPerms(ctx)) {
                            if (requestAskingCount <= 0) {
                                ExitActivity.exitApplication(ctx,
                                    "Les permissions d'application sont obligatoires !",
                                    "Terminating process: user refused to give in-app permissions"
                                );
                            } else {
                                requestingPermCode = REQ_IN_APP_PERMS;
                                activity.requestPermissions(missingInAppPerms.toArray(new String[0]), REQ_IN_APP_PERMS);
                                requestAskingCount--;
                            }
                        } else { init(_activity); }
                    } else if (requestingPermCode == REQ_OVERLAY_PERM) {
                        requestingPermCode = 0;
                        if (android.provider.Settings.canDrawOverlays(ctx)) {
                            Utils.showToast(ctx, "Permission overlay accordée !");
                            if (!checkInAppPerms(ctx)) {
                                requestingPermCode = REQ_IN_APP_PERMS;
                                activity.requestPermissions(missingInAppPerms.toArray(new String[0]), REQ_IN_APP_PERMS);
                                requestAskingCount--;
                            } else { init(_activity); }
                        } else {
                            ExitActivity.exitApplication(ctx,
                                "La permission overlay est nécessaire !",
                                "Terminating process: user refused to give overlay permission"
                            );
                        }
                    } else if (requestingPermCode == REQ_ENABLE_BT) {
                        requestingPermCode = 0;
                        BluetoothManager bluetoothManager = _activity.getSystemService(BluetoothManager.class);
                        BluetoothAdapter adapter = bluetoothManager.getAdapter();
                        if (adapter.isEnabled()) {
                            menuService.webView.evaluateJavascript(
                                "javascript:Android.toggleScan(false)",
                                null
                            );
                        } else { Utils.showToast(ctx, "Le Bluetooth doit être activé pour le scan !"); }
                    }
                }
            }
        });

        if (!android.provider.Settings.canDrawOverlays(ctx)) {
            Intent overlayPermIntent = new Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + ctx.getPackageName())
            );
            activity.startActivity(overlayPermIntent);
            Utils.showToast(ctx, "Veuillez accorder la permission overlay");
            requestingPermCode = REQ_OVERLAY_PERM;
        } else if (!checkInAppPerms(ctx)) {
            requestingPermCode = REQ_IN_APP_PERMS;
            activity.requestPermissions(missingInAppPerms.toArray(new String[0]), REQ_IN_APP_PERMS);
            requestAskingCount--;
        } else { init(activity); }
    }

    /**
     * <p>Initialises the mod, once all permissions are granted.</p>
     *
     * <ol>
     *   <li>prepares the encrypted preferences ({@link Prefs#init()});</li>
     *   <li>installs the data needed by Orekit, the astronomy library ({@link RealAstronomy#OreKitInstaller(Activity)});</li>
     *   <li>initialises the native C++ part ({@link #c_init(Context)});</li>
     *   <li>loads the saved connection key, then starts and binds {@link AppMenuService}.</li>
     * </ol>
     *
     * <p>If one of these steps fails, the app is closed with an explanation.</p>
     *
     * @param activity the activity of the host app
     */
    private void init(Activity activity) {
        Context ctx = activity.getApplicationContext();

        try { Prefs.init(); }
        catch (Exception e) {
            ExitActivity.exitApplication(ctx,
                "Impossible d'initialiser les préférences !",
                "Terminating process: failed to init shared prefs:\n" + Log.getStackTraceString(e)
            );
            return;
        }

        try { RealAstronomy.OreKitInstaller(activity); /*RealAstronomy.OreKitStressTest();*/ }
        catch (Exception e) {
            ExitActivity.exitApplication(ctx,
                "Impossible d'initialiser OreKit !",
                "Terminating process: failed to load orekit data:\n" + Log.getStackTraceString(e)
            );
            return;
        }

        int c_init_ret = c_init(ctx);
        if (c_init_ret != 0) {
            ExitActivity.exitApplication(ctx,
                "Impossible d'intialiser le mod !\n(voir logcat pour plus d'info)",
                "Terminating process: failed to init mods -> c_init returned: " + c_init_ret
            );
            return;
        }

        BT_CONN_KEY = Prefs.getPref(ctx, "BT_CONN_KEY");
        Intent serviceIntent = new Intent(ctx, AppMenuService.class);
        ctx.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    }

    /**
     * <p>Checks the permissions needed to scan and connect to the telescope over Bluetooth Low Energy.</p>
     *
     * <ul>
     *   <li>Android 12 and above: <code>BLUETOOTH_SCAN</code> and <code>BLUETOOTH_CONNECT</code>;</li>
     *   <li>Android 11 and below: <code>ACCESS_FINE_LOCATION</code>, required by Android for BLE scans.</li>
     * </ul>
     *
     * <p>The permissions that are still missing are stored in {@link #missingInAppPerms}, so that they can be requested.</p>
     *
     * @param ctx context used to check the permissions
     * @return <code>true</code> if every permission is granted
     */
    private boolean checkInAppPerms(Context ctx) {
        // Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+ (API +31)
            missingInAppPerms.add(Manifest.permission.BLUETOOTH_SCAN);
            missingInAppPerms.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else { // Android 11 and below: BLE scanning requires the location permission
            missingInAppPerms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        missingInAppPerms.removeIf(p -> ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
        return missingInAppPerms.isEmpty();
    }
}
