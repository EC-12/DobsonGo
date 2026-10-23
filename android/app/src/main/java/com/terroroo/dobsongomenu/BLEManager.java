package com.terroroo.dobsongomenu;

import static com.terroroo.dobsongomenu.DobsonGoMod.ACTIVE_SESSION;
import static com.terroroo.dobsongomenu.DobsonGoMod.mainHandler;
import static com.terroroo.dobsongomenu.Utils.Logcat;
import static com.terroroo.dobsongomenu.Utils.stringToUUID;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;

/**
 * <p>Bluetooth Low Energy (BLE) communication with the telescope.</p>
 *
 * <p>This class handles the two steps of the connection:</p>
 * <ul>
 *   <li><b>Scanning</b>: {@link #startScan()} looks for nearby BLE devices and adds each new one to the list
 *       displayed in the menu;</li>
 *   <li><b>Connection</b>: {@link #connectServer} connects to the chosen device. The connection itself, the
 *       authentication and the exchanges with the telescope are handled by {@link BLEServer}.</li>
 * </ul>
 *
 * <p>Only one telescope can be connected at a time. {@link #init(AppMenuService)} must be called before any
 * other method.</p>
 */
@SuppressLint("MissingPermission")
public abstract class BLEManager {
    /** Telescope currently connected or being connected, or <code>null</code>. */
    private static BLEServer server;
    /** Android Bluetooth manager, used to access the adapter. */
    private static BluetoothManager btManager;
    /** Callback that receives the results of the BLE scan. */
    private static ScanCallback bleScanCallback;
    /**
     * <code>true</code> while a BLE scan is running.
     */
    public static boolean isScanning = false;
    /** Devices found during the current scan, indexed by MAC address, so that each one is listed only once. */
    private static final Map<String, BluetoothDevice> discoveredDevices = new HashMap<>();
    /**
     * <code>true</code> once {@link #init(AppMenuService)} has been called.
     */
    private static boolean init = false;

    /**
     * <p>Initialises the Bluetooth manager and the scan callback.</p>
     *
     * <p>If the phone has no Bluetooth adapter, the app is closed. Each device found during a scan, if it has a
     * name and has not been seen yet, is added to the list of the menu with its name, MAC address and signal
     * strength (RSSI).</p>
     *
     * @param service the menu service, used to access the system services and the WebView
     */
    public static void init(AppMenuService service) {
        btManager = service.getSystemService(BluetoothManager.class);
        BluetoothAdapter btAdapter = btManager.getAdapter();
        if (btAdapter == null) {
            ExitActivity.exitApplication(service.getApplicationContext(),
                "Impossible de trouver l'adaptateur Bluetooth !",
                "ERROR: unable to find bluetooth adapter"
            );
        }

        bleScanCallback = new ScanCallback() {
            /**
             * <p>Called for each BLE advertisement received during the scan: new named devices are added to the
             * menu.</p>
             *
             * @param callbackType type of callback (not used)
             * @param result       the scanned device, with its signal strength
             */
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                BluetoothDevice scannedServer = result.getDevice();
                if (!discoveredDevices.containsKey(scannedServer.getAddress()) && scannedServer.getName() != null) {
                    discoveredDevices.put(scannedServer.getAddress(), scannedServer);
                    service.webView.evaluateJavascript(
                        String.format("javascript:android_addBtDevice(\"%s\", \"%s\", %s)", scannedServer.getName(), scannedServer.getAddress(), result.getRssi()),
                        null
                    );
                }
            }
        };

        init = true;
    }

    /**
     * <p>Starts scanning for BLE devices.</p>
     *
     * <p>The current connection, if any, is closed first, and the list of discovered devices is cleared.</p>
     */
    public static void startScan() {
        assert init;

        if (server != null) { server.disconnect(); server = null; }
        discoveredDevices.clear();
        BluetoothAdapter btAdapter = btManager.getAdapter();
        btAdapter.getBluetoothLeScanner().startScan(bleScanCallback);
        isScanning = true;
    }

    /** Stops the BLE scan. */
    public static void stopScan() {
        assert init;

        BluetoothAdapter btAdapter = btManager.getAdapter();
        btAdapter.getBluetoothLeScanner().stopScan(bleScanCallback);
        isScanning = false;
    }

    /**
     * <p>Indicates whether Bluetooth is turned on.</p>
     *
     * @return <code>true</code> if the Bluetooth adapter is enabled
     */
    public static boolean isBtAdapterEnabled() {
        assert init;

        BluetoothAdapter btAdapter = btManager.getAdapter();
        return btAdapter.isEnabled();
    }

    /**
     * <p>Starts the connection to a telescope.</p>
     *
     * <p>A new {@link BLEServer} is created, which connects to the device and authenticates. The result is
     * reported to the menu asynchronously.</p>
     *
     * @param service         the menu service, used to update the WebView
     * @param address         MAC address of the device
     * @param isActiveSession <code>true</code> if this is the telescope of the previous session, which changes
     *                        the error message shown if it cannot be found
     * @return the name of the device
     */
    public static String connectServer(AppMenuService service, String address, boolean isActiveSession) {
        assert init;

        BluetoothAdapter btAdapter = btManager.getAdapter();
        BluetoothDevice device = btAdapter.getRemoteDevice(address);
        server = new BLEServer(service, device, isActiveSession);
        return device.getName();
    }

    /** Disconnects the current telescope, if there is one. */
    public static void disconnectServer() {
        assert init;
        if (server != null) server.disconnect();
    }

    /**
     * <p>Returns the telescope currently connected or being connected.</p>
     *
     * @return the current server, or <code>null</code> if there is none
     */
    public static BLEServer getServer() { return server; }

    /**
     * <p>Indicates whether a telescope is connected <b>and</b> has accepted the authentication.</p>
     *
     * <p><b>This method is called from the native C++ code: do not rename or remove it.</b></p>
     *
     * @return <code>true</code> if a trusted telescope is connected
     */
    public static boolean isServerConnected() {
        assert init;
        return server != null && server.trusted;
    }

    /**
     * <p>Connection to one telescope, seen as a BLE GATT server.</p>
     *
     * <p><b>Connection and authentication sequence</b></p>
     * <ol>
     *   <li>connection to the GATT server, then request of a 517-byte MTU, to exchange larger messages;</li>
     *   <li>discovery of the services, and check that the device has the authentication service
     *       (<code>0x183D</code>): otherwise, it is not a telescope;</li>
     *   <li>reading of the <i>is client trustworthy</i> characteristic;</li>
     *   <li>if the app is not trusted yet: reading of a 32-byte random challenge, then writing of the answer
     *       <code>HMAC-SHA256(connection key, challenge)</code> (see {@link Utils#hmac_sha256}), and reading of
     *       <i>is client trustworthy</i> again;</li>
     *   <li>once trusted: subscription to the notifications of the calibration progress and of the battery level,
     *       and update of the menu.</li>
     * </ol>
     *
     * <p><b>Command queue.</b> Android can only run one GATT operation at a time: a new read or write sent before
     * the previous one has finished is lost. Every operation is therefore added to {@link #bleQueue}, and the next
     * one only starts when the callback of the current one has been received.</p>
     */
    public static class BLEServer {
        // ############ BT SERVICES UUID ############ //
        /** Authentication service. */
        private static final UUID AUTH_SERVICE_UUID = stringToUUID("183D");
        /** GoTo control service of the telescope (calibration, pointing). */
        private static final UUID GOTOCTRL_SERVICE_UUID = stringToUUID("f170c5c8-cead-4be4-b5dc-7d6f6977fed4");
        /** Standard Environmental Sensing service, which contains the battery level. */
        private static final UUID ENV_SERVICE_UUID = stringToUUID("181A");
        /** Characteristic containing the random challenge sent by the telescope. */
        private static final UUID auth_ChallengeCharac_UUID = stringToUUID("358c44fb-60f8-4ccd-9dda-5459c8695681");
        /** Characteristic in which the app writes its answer to the challenge. */
        private static final UUID auth_ResponseCharac_UUID = stringToUUID("111b3755-b893-436b-b5bd-1e215aee5fed");
        /** Characteristic indicating whether the telescope trusts the app (1 byte, boolean). */
        private static final UUID auth_IsClientTrustworthy_UUID = stringToUUID("9ef29165-5a49-4a90-8031-04d84835a8c4");
        /** Standard Battery Level characteristic (1 byte, percentage). */
        private static final UUID envBatteryCharac_UUID = stringToUUID("2A19");
        /** Characteristic giving the number of stars used for the calibration (1 byte). */
        private static final UUID gotoCalibCharac_UUID = stringToUUID("eebf18e1-d441-49c2-82d4-492fd19cfc2c");
        /** Standard descriptor used to turn on the notifications of a characteristic (CCCD). */
        private static final UUID CCCD_descriptor_UUID = stringToUUID("00002902-0000-1000-8000-00805f9b34fb");
        // ################################# //

        // ############ BT SERVER PROPERTIES ############ //
        /** Number of stars already used to calibrate the telescope. */
        private int calibStars = 0;
        /**
         * <code>true</code> once at least 3 calibration stars have been recorded.
         */
        public boolean calibrated = false;
        /**
         * <code>true</code> once the telescope has accepted the authentication.
         */
        private boolean trusted = false;
        // ################################# //

        /** GATT connection to the telescope. */
        private final BluetoothGatt gatt;
        /**
         * <code>true</code> if this connection tries to resume the previous session.
         */
        private boolean isActiveSession;
        /**
         * <code>true</code> if the disconnection was requested by the app, not caused by a lost connection.
         */
        private boolean forcedDisconnection = false;
        /** Error message shown in the menu after a disconnection, or <code>null</code> for the default message. */
        private String CONN_ERROR_TAG;
        /** Text of the scan button after a disconnection, or <code>null</code> for the default text. */
        private String SCAN_BTN_TXT;

        /** Pending GATT operations, run one at a time. */
        private final Queue<Runnable> bleQueue = new LinkedList<>();
        /**
         * <code>true</code> while a GATT operation is running.
         */
        private boolean bleBusy = false;

        /**
         * <p>Creates the server and immediately starts the GATT connection to the device.</p>
         *
         * <p>All the steps of the connection sequence (see {@link BLEServer}) are handled by the callback defined in
         * this constructor.</p>
         *
         * @param service       the menu service, used to update the WebView
         * @param device        the Bluetooth device to connect to
         * @param activeSession <code>true</code> if this connection tries to resume the previous session
         */
        private BLEServer(AppMenuService service, BluetoothDevice device, final boolean activeSession) {
            isActiveSession = activeSession;

            BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
                /**
                 * <p>Called when the device connects or disconnects.</p>
                 *
                 * <ul>
                 *   <li><b>connected</b>: requests a larger MTU, first step of the sequence;</li>
                 *   <li><b>disconnected</b>: chooses the error message and the scan button text according to the situation
                 *       (previous session not found, connection lost, or device that is not a telescope), updates the menu and
                 *       releases the connection.</li>
                 * </ul>
                 *
                 * @param _gatt    the GATT connection
                 * @param status   status of the operation
                 * @param newState new connection state
                 */
                @Override
                public void onConnectionStateChange(BluetoothGatt _gatt, int status, int newState) {
                    //Logcat("onConnectionStateChange to: " + g.getDevice().getName(), 0);
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        bleQueue.add(() -> {
                            if (!gatt.requestMtu(517)) {
                                bleBusy = false;
                                processNextBleCommand();
                            }
                        });
                        processNextBleCommand();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Logcat("Disconnected from " + _gatt.getDevice().getName(), 0);
                        if (!forcedDisconnection) {
                            if (isActiveSession
                                && _gatt.getDevice().getAddress().equals(ACTIVE_SESSION)) {
                                SCAN_BTN_TXT = "Ignorer la session";
                                CONN_ERROR_TAG = "Impossible de retrouver la session. Vérifiez la clé de connexion et/ou la proximité avec le télescope.";
                                ACTIVE_SESSION = null;
                            } else if (trusted) {
                                SCAN_BTN_TXT = "Reconnecter";
                                CONN_ERROR_TAG = "Connexion avec l'appareil perdue !";
                                mainHandler.post(() -> service.webView.evaluateJavascript(
                                    "javascript:android_disconnectedServer();",
                                    null
                                ));
                            }
                        }
                        if (CONN_ERROR_TAG == null) CONN_ERROR_TAG = "Il semblerait que cet appareil ne soit pas un télescope !";
                        if (SCAN_BTN_TXT == null) SCAN_BTN_TXT = "Recommencer le scan";
                        mainHandler.post(() -> {
                            service.webView.evaluateJavascript(
                                String.format("javascript:android_toggleScan(false, true, \"%s\", true, true, false);", SCAN_BTN_TXT) +
                                String.format("android_toggleError(true, 'ble-error', \"Erreur : %s\")", CONN_ERROR_TAG),
                                null
                            );
                            if (gatt != null) gatt.close();
                            bleQueue.clear();
                            bleBusy = false;
                            server = null;
                        });
                    } else disconnect();
                }

                /**
                 * <p>Called when the MTU has been negotiated. If the 517 bytes are accepted, the services of the device are
                 * discovered; otherwise, the device is disconnected.</p>
                 *
                 * @param _g     the GATT connection
                 * @param mtu    the negotiated MTU
                 * @param status status of the operation
                 */
                @Override
                public void onMtuChanged(BluetoothGatt _g, int mtu, int status) {
                    bleBusy = false;
                    processNextBleCommand();

                    if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 517) {
                        bleQueue.add(() -> {
                            if (!gatt.discoverServices()) {
                                bleBusy = false;
                                processNextBleCommand();
                            }
                        });
                        processNextBleCommand();
                    } else {
                        CONN_ERROR_TAG = "Impossible de négocier le MTU";
                        disconnect();
                    }
                }

                /**
                 * <p>Called once the services of the device are known. If the authentication service is present, the
                 * <i>is client trustworthy</i> characteristic is read; otherwise, the device is not a telescope and is
                 * disconnected.</p>
                 *
                 * @param _g     the GATT connection
                 * @param status status of the operation
                 */
                @Override
                public void onServicesDiscovered(BluetoothGatt _g, int status) {
                    bleBusy = false;
                    processNextBleCommand();

                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        for (BluetoothGattService service : gatt.getServices()) {
                            if (service.getUuid().equals(AUTH_SERVICE_UUID)) {
                                readCharacteristic(service.getCharacteristic(auth_IsClientTrustworthy_UUID));
                                return;
                            }
                        }
                        CONN_ERROR_TAG = device.getName() + " n'est pas un télescope !";
                        disconnect();
                    } else {
                        CONN_ERROR_TAG = "Impossible de découvrir les services de l'appareil BLE";
                        disconnect();
                    }
                }

                /**
                 * <p>Deprecated version of the read callback, used up to Android 12. It forwards to
                 * {@link #onCharacteristicRead(BluetoothGatt, BluetoothGattCharacteristic, byte[], int)}.</p>
                 *
                 * @param _g             the GATT connection
                 * @param characteristic the characteristic that was read
                 * @param status         status of the operation
                 */
                @Override
                @SuppressWarnings("deprecation")
                public void onCharacteristicRead(BluetoothGatt _g, BluetoothGattCharacteristic characteristic, int status) {
                    // This version is only called on Android 12 and below:
                    // it forwards to the new version, to avoid duplicating the code
                    onCharacteristicRead(_g, characteristic, characteristic.getValue(), status);
                }

                /**
                 * <p>Called when a characteristic has been read. This is where the authentication takes place:</p>
                 * <ul>
                 *   <li><b>is client trustworthy = true</b>: notifications are enabled for the calibration and the battery,
                 *       the address of the telescope is saved as the active session, and the menu shows the connection;</li>
                 *   <li><b>is client trustworthy = false</b>: the challenge is read;</li>
                 *   <li><b>challenge</b>: the HMAC-SHA256 answer is computed with the connection key and written to the
                 *       telescope.</li>
                 * </ul>
                 * <p>If the read fails, the device is disconnected with an error message.</p>
                 *
                 * @param _g             the GATT connection
                 * @param characteristic the characteristic that was read
                 * @param value          the value that was read
                 * @param status         status of the operation
                 */
                @Override
                public void onCharacteristicRead(@NonNull BluetoothGatt _g, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, int status) {
                    bleBusy = false;
                    processNextBleCommand();

                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (characteristic.getUuid().equals(auth_IsClientTrustworthy_UUID)) {
                            trusted = Utils.bytesToBoolean(value);
                            if (trusted) {
                                BluetoothGattCharacteristic calibChar = gatt.getService(GOTOCTRL_SERVICE_UUID).getCharacteristic(gotoCalibCharac_UUID);
                                enableNotify(calibChar);
                                BluetoothGattCharacteristic batteryChar = gatt.getService(ENV_SERVICE_UUID).getCharacteristic(envBatteryCharac_UUID);
                                enableNotify(batteryChar);

                                ACTIVE_SESSION = gatt.getDevice().getAddress();
                                isActiveSession = false;

                                mainHandler.post(() -> {
                                    Utils.showToast(service.webView.getContext(), "Connexion au télescope réussie !");
                                    service.webView.evaluateJavascript(
                                        String.format("javascript:android_connectedServer(\"%s\")", gatt.getDevice().getName()),
                                        null
                                    );
                                });
                                mainHandler.postDelayed(() -> {
                                    AppMenuService.onObjectSelected(1, "Arcturus", "arctu", 90, false, 56.6964432, 12.364566, 5.6, -1, null, null);
                                    //AppMenuService.viewError("CACA", false);
                                }, 5000);
                                /*mainHandler.postDelayed(() -> {
                                    //AppMenuService.viewObject(2, "Pluton", "pluto", 90, Double.NaN, Double.NaN, null, null);
                                    AppMenuService.viewError("CACA", false);
                                }, 6000);
                                mainHandler.postDelayed(() -> {
                                    //AppMenuService.viewObject(2, "Pluton", "pluto", 90, Double.NaN, Double.NaN, null, null);
                                    //AppMenuService.viewError("CACA", false);
                                    AppMenuService.leftView();
                                }, 10000);*/
                            } else readCharacteristic(characteristic.getService().getCharacteristic(auth_ChallengeCharac_UUID));
                        } else if (characteristic.getUuid().equals(auth_ChallengeCharac_UUID)) {
                            try {
                                String challenge = Utils.bytesToUTF8String(value, 32);
                                byte[] response = Utils.hmac_sha256(DobsonGoMod.BT_CONN_KEY, challenge);
                                // Logcat("CHALLENGE: " + challenge, 0);
                                // Logcat("CALCULATED CHALLENGE HMAC: " + Utils.bytesToHex(response), 0);
                                writeCharacteristic(characteristic.getService().getCharacteristic(auth_ResponseCharac_UUID), response);
                            } catch (Exception e) {
                                Logcat(Log.getStackTraceString(e), 2);
                                CONN_ERROR_TAG = "Une erreur est survenue lors du calcul de la phrase de connexion";
                                disconnect();
                            }
                        }
                    } else {
                        int state = btManager.getConnectionState(gatt.getDevice(), BluetoothProfile.GATT);
                        Logcat("Error on characteristic read: expected 'GATT_SUCCESS (0)' status but got '" + status + "'", 2);
                        if (state == BluetoothProfile.STATE_CONNECTED) {
                            CONN_ERROR_TAG = "Une erreur est survenue lors de la communication avec le télescope !";
                            disconnect();
                        } else if (status == 133) {
                            CONN_ERROR_TAG = "Une erreur est survenue lors de la communication avec le télescope ! (vérifiez la clé de connexion)";
                        }
                    }
                }

                /**
                 * <p>Called when a characteristic has been written. After the answer to the challenge has been written,
                 * <i>is client trustworthy</i> is read again to know whether the telescope accepted it.</p>
                 *
                 * @param _g             the GATT connection
                 * @param characteristic the characteristic that was written
                 * @param status         status of the operation
                 */
                @Override
                public void onCharacteristicWrite(BluetoothGatt _g, BluetoothGattCharacteristic characteristic, int status) {
                    bleBusy = false;
                    processNextBleCommand();

                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (characteristic.getUuid().equals(auth_ResponseCharac_UUID)) {
                            readCharacteristic(characteristic.getService().getCharacteristic(auth_IsClientTrustworthy_UUID));
                        }
                    } else {
                        int state = btManager.getConnectionState(gatt.getDevice(), BluetoothProfile.GATT);
                        Logcat("Error on characteristic write: expected 'GATT_SUCCESS (0)' status but got '" + status + "'", 2);
                        if (state == BluetoothProfile.STATE_CONNECTED) {
                            CONN_ERROR_TAG = "Une erreur est survenue lors de la communication avec le télescope !";
                            disconnect();
                        }
                    }
                }

                /**
                 * <p>Deprecated version of the notification callback, used up to Android 12. It forwards to
                 * {@link #onCharacteristicChanged(BluetoothGatt, BluetoothGattCharacteristic, byte[])}.</p>
                 *
                 * @param _g             the GATT connection
                 * @param characteristic the characteristic that changed
                 */
                @Override
                @SuppressWarnings("deprecation")
                public void onCharacteristicChanged(BluetoothGatt _g, BluetoothGattCharacteristic characteristic) {
                    // This version is only called on Android 12 and below:
                    // it forwards to the new version, to avoid duplicating the code
                    onCharacteristicChanged(_g, characteristic, characteristic.getValue());
                }

                /**
                 * <p>Called when the telescope sends a notification:</p>
                 * <ul>
                 *   <li><b>battery level</b>: the percentage is displayed in the menu;</li>
                 *   <li><b>calibration</b>: the number of calibration stars is displayed, and the telescope is considered
                 *       calibrated from 3 stars.</li>
                 * </ul>
                 *
                 * @param _g             the GATT connection
                 * @param characteristic the characteristic that changed
                 * @param value          its new value
                 */
                @Override
                public void onCharacteristicChanged(@NonNull BluetoothGatt _g, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
                    if (characteristic.getUuid().equals(envBatteryCharac_UUID)) {
                        int nBattery = value[0] & 0xFF;
                        String battery = String.format("%d%%", nBattery);
                        mainHandler.post(() -> service.webView.evaluateJavascript(
                            String.format("javascript:android_updateScopeData('bat-level', \"%s\")", battery),
                            null
                        ));
                    } else if (characteristic.getUuid().equals(gotoCalibCharac_UUID)) {
                        calibStars = value[0] & 0xFF;
                        mainHandler.post(() -> service.webView.evaluateJavascript(
                            String.format("javascript:android_updateCalibrationData(%s)", calibStars),
                            null
                        ));
                        calibrated = calibStars >= 3; // Calibration threshold
                    }
                }

                /**
                 * <p>Called when a descriptor has been written, after a notification has been enabled. Errors are logged, and
                 * the next operation of the queue is started.</p>
                 *
                 * @param gatt       the GATT connection
                 * @param descriptor the descriptor that was written
                 * @param status     status of the operation
                 */
                @Override
                public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Logcat("Error on descriptor write: expected 'GATT_SUCCESS (0)' status but got '" + status + "'", 2);
                    }
                    bleBusy = false; // Release the lock
                    processNextBleCommand(); // Run the next operation, if any
                }
            };

            this.gatt = device.connectGatt(service.getApplicationContext(), false, gattCallback);
        }

        /**
         * <p>Disconnects from the telescope at the request of the app.</p>
         *
         * <p>The connection is actually released in
         * {@link BluetoothGattCallback#onConnectionStateChange}, once the disconnection is confirmed.</p>
         */
        public void disconnect() {
            forcedDisconnection = true;
            if (gatt != null) gatt.disconnect();
        }

        /*@SuppressWarnings("unchecked")
        public <T> void setupObserver(String observerId, Utils.Observer<T> observer) {

        }*/

        /**
         * <p>Runs the next GATT operation of the queue, if no other operation is running.</p>
         */
        private synchronized void processNextBleCommand() {
            if (bleBusy || bleQueue.isEmpty()) return;
            bleBusy = true;
            bleQueue.poll().run();
        }

        /**
         * <p>Adds the reading of a characteristic to the queue. The result is received in
         * {@link BluetoothGattCallback#onCharacteristicRead}.</p>
         *
         * @param characteristic the characteristic to read
         */
        private void readCharacteristic(BluetoothGattCharacteristic characteristic) {
            bleQueue.add(() -> {
                if (!gatt.readCharacteristic(characteristic)) {
                    bleBusy = false; // The call failed immediately: release the lock
                    processNextBleCommand();
                }
            });
            processNextBleCommand();
        }

        /**
         * <p>Adds the writing of a characteristic to the queue.</p>
         *
         * <p>The new API is used on Android 13 and above, and the deprecated one on older versions.</p>
         *
         * @param characteristic the characteristic to write
         * @param payload        the bytes to write
         * @throws RuntimeException (when the operation runs) if the characteristic is not writable or the write
         *                          cannot be started
         */
        private void writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] payload) {
            bleQueue.add(() -> {
                int props = characteristic.getProperties();
                if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
                    throw new RuntimeException("Characteristic not writable with response: " + characteristic.getUuid());
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    int code = gatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    if (code != BluetoothStatusCodes.SUCCESS) {
                        throw new RuntimeException("writeCharacteristic() returned error code '" + code + "' for '" + characteristic.getUuid() + "'");
                    }
                } else {
                    characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    characteristic.setValue(payload);
                    boolean success = gatt.writeCharacteristic(characteristic);
                    if (!success) {
                        throw new RuntimeException("writeCharacteristic() returned an error for '" + characteristic.getUuid() + "'");
                    }
                }
            });
            processNextBleCommand();
        }

        /**
         * <p>Adds to the queue the activation of the notifications of a characteristic.</p>
         *
         * <p>Notifications are enabled locally, then on the telescope by writing its CCCD descriptor. The new API is
         * used on Android 13 and above, and the deprecated one on older versions.</p>
         *
         * @param characteristic the characteristic to observe
         * @throws RuntimeException (when the operation runs) if the descriptor cannot be written
         */
        private void enableNotify(BluetoothGattCharacteristic characteristic) {
            bleQueue.add(() -> {
                gatt.setCharacteristicNotification(characteristic, true);
                BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_descriptor_UUID);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    int code = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (code != BluetoothStatusCodes.SUCCESS) {
                        throw new RuntimeException("writeDescriptor() returned error code '" + code + "' for '" + characteristic.getUuid() + "'");
                    }
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    boolean success = gatt.writeDescriptor(cccd);
                    if (!success) {
                        throw new RuntimeException("writeDescriptor() returned an error for '" + characteristic.getUuid() + "'");
                    }
                }
            });
            processNextBleCommand();
        }
    }
}
