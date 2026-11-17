package com.invaliddev403.gtvremote;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

@SuppressLint("MissingPermission")
final class BluetoothHidController {
    interface Listener {
        void onReady();
        void onConnectionChanged(BluetoothDevice device, int state);
        void onAdvertisingChanged(boolean advertising);
        void onError(String message);
    }

    private static final ParcelUuid HID_SERVICE =
            ParcelUuid.fromString("00001812-0000-1000-8000-00805F9B34FB");

    private static final class PendingReport {
        final HidKeyboard.Report report;
        final int holdMilliseconds;
        final int generation;

        PendingReport(HidKeyboard.Report report, int holdMilliseconds, int generation) {
            this.report = report;
            this.holdMilliseconds = holdMilliseconds;
            this.generation = generation;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<PendingReport> pendingReports = new ArrayDeque<>();
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hidDevice;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothDevice connectedDevice;
    private boolean registered;
    private boolean advertising;
    private boolean sending;
    private boolean closed;
    private boolean manualDisconnect;
    private int generation;

    BluetoothHidController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            listener.onError("Bluetooth is not available on this device");
            return;
        }
        if (!adapter.isEnabled()) {
            listener.onError("Enable Bluetooth to use GTV Remote");
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null || !adapter.isMultipleAdvertisementSupported()) {
            listener.onError("BLE advertising is not supported on this phone");
            return;
        }
        boolean started = adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile != BluetoothProfile.HID_DEVICE || closed) return;
                hidDevice = (BluetoothHidDevice)proxy;
                registerHidApp();
            }

            @Override
            public void onServiceDisconnected(int profile) {
                hidDevice = null;
                registered = false;
                resetReports();
                listener.onError("Android Bluetooth HID service disconnected");
            }
        }, BluetoothProfile.HID_DEVICE);
        if (!started) {
            listener.onError("This phone does not expose Android's Bluetooth HID device profile");
        }
    }

    void close() {
        closed = true;
        resetReports();
        stopAdvertising();
        if (hidDevice != null) {
            if (registered) hidDevice.unregisterApp();
            if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
        }
        registered = false;
        hidDevice = null;
    }

    boolean isConnected() {
        return connectedDevice != null && hidDevice != null;
    }

    BluetoothDevice getConnectedDevice() {
        return connectedDevice;
    }

    void connectTo(BluetoothDevice device) {
        if (!registered || hidDevice == null || device == null) {
            listener.onError("Bluetooth HID is not ready");
            return;
        }
        manualDisconnect = true;
        stopAdvertising();
        if (!hidDevice.connect(device)) {
            listener.onError("Could not start the connection. Keep the TV nearby and awake.");
        }
    }

    void disconnect() {
        manualDisconnect = true;
        if (hidDevice != null && connectedDevice != null) {
            hidDevice.disconnect(connectedDevice);
        }
        resetReports();
    }

    void rejectConnection(BluetoothDevice device) {
        if (hidDevice != null && device != null) hidDevice.disconnect(device);
    }

    void startAdvertising() {
        manualDisconnect = false;
        if (!registered || advertiser == null || advertising || connectedDevice != null) return;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(HID_SERVICE)
                .build();
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (SecurityException | IllegalStateException exception) {
            listener.onError("Could not advertise: " + exception.getMessage());
        }
    }

    void stopAdvertising() {
        if (advertiser == null || !advertising) return;
        try {
            advertiser.stopAdvertising(advertiseCallback);
        } catch (SecurityException | IllegalStateException ignored) {
        }
        advertising = false;
        listener.onAdvertisingChanged(false);
    }

    void sendKey(String key) {
        enqueue(HidKeyboard.key(key), 80);
    }

    void sendText(String text) {
        if (text == null || text.isEmpty() || text.length() > 96) {
            listener.onError("Enter between 1 and 96 characters");
            return;
        }
        HidKeyboard.Report[] reports = new HidKeyboard.Report[text.length()];
        try {
            for (int i = 0; i < text.length(); i++) {
                char value = text.charAt(i);
                if (value < 32 || value > 126) {
                    listener.onError("Only standard printable ASCII characters are supported");
                    return;
                }
                reports[i] = HidKeyboard.character(value);
            }
        } catch (IllegalArgumentException exception) {
            listener.onError(exception.getMessage());
            return;
        }
        for (HidKeyboard.Report report : reports) enqueue(report, 30);
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            listener.onAdvertisingChanged(true);
        }

        @Override
        public void onStartFailure(int errorCode) {
            advertising = false;
            listener.onAdvertisingChanged(false);
            listener.onError("Advertising failed: " + advertisingError(errorCode));
        }
    };

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
            BluetoothHidController.this.registered = registered;
            if (registered) {
                listener.onReady();
                startAdvertising();
                if (pluggedDevice != null) {
                    connectedDevice = pluggedDevice;
                    listener.onConnectionChanged(pluggedDevice, BluetoothProfile.STATE_CONNECTED);
                }
            } else {
                listener.onError("Android could not register the HID keyboard profile");
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device;
                resetReports();
                stopAdvertising();
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice == null || connectedDevice.equals(device)) {
                    connectedDevice = null;
                }
                resetReports();
                if (!manualDisconnect) startAdvertising();
            }
            listener.onConnectionChanged(device, state);
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT &&
                    (id == HidKeyboard.KEYBOARD_REPORT_ID || id == HidKeyboard.CONSUMER_REPORT_ID)) {
                hidDevice.replyReport(device, type, id,
                        new byte[id == HidKeyboard.KEYBOARD_REPORT_ID ? 8 : 2]);
            } else {
                hidDevice.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
            }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
        }

        @Override
        public void onSetProtocol(BluetoothDevice device, byte protocol) {
        }

        @Override
        public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) {
        }

        @Override
        public void onVirtualCableUnplug(BluetoothDevice device) {
            connectedDevice = null;
            resetReports();
            listener.onConnectionChanged(device, BluetoothProfile.STATE_DISCONNECTED);
            startAdvertising();
        }
    };

    private void registerHidApp() {
        BluetoothHidDeviceAppSdpSettings settings = new BluetoothHidDeviceAppSdpSettings(
                "GTV Remote", "Google TV keyboard and remote", "invaliddev403",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD, HidKeyboard.descriptor());
        BluetoothHidDeviceAppQosSettings qos = new BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250, 11250);
        Executor executor = handler::post;
        if (!hidDevice.registerApp(settings, qos, qos, executor, hidCallback)) {
            listener.onError("Android rejected the Bluetooth HID registration");
        }
    }

    private void enqueue(HidKeyboard.Report report, int holdMilliseconds) {
        if (!isConnected()) {
            listener.onError("Google TV is not connected");
            return;
        }
        pendingReports.add(new PendingReport(report, holdMilliseconds, generation));
        sendNextReport();
    }

    private void sendNextReport() {
        if (sending || !isConnected()) return;
        PendingReport pending = pendingReports.poll();
        if (pending == null) return;
        if (pending.generation != generation) {
            sendNextReport();
            return;
        }
        sending = true;
        BluetoothDevice target = connectedDevice;
        boolean accepted = hidDevice.sendReport(target, pending.report.id, pending.report.data);
        if (!accepted) {
            sending = false;
            listener.onError("Android could not send the HID report");
            return;
        }
        handler.postDelayed(() -> {
            if (pending.generation != generation || !isConnected() || !target.equals(connectedDevice)) {
                sending = false;
                return;
            }
            byte[] release = new byte[pending.report.data.length];
            hidDevice.sendReport(target, pending.report.id, release);
            handler.postDelayed(() -> {
                if (pending.generation == generation) sending = false;
                sendNextReport();
            }, 10);
        }, pending.holdMilliseconds);
    }

    private void resetReports() {
        generation++;
        pendingReports.clear();
        sending = false;
    }

    private static String advertisingError(int error) {
        switch (error) {
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED: return "already started";
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE: return "advertising data too large";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED: return "feature unsupported";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR: return "internal Bluetooth error";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS: return "too many advertisers";
            default: return "code " + error;
        }
    }
}
