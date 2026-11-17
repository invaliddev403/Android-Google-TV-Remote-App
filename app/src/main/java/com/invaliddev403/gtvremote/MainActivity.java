package com.invaliddev403.gtvremote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SuppressLint("MissingPermission")
public final class MainActivity extends Activity implements BluetoothHidController.Listener {
    private static final int BLUETOOTH_PERMISSION_REQUEST = 40;

    private final int[] keyButtons = {
            R.id.key_up, R.id.key_down, R.id.key_left, R.id.key_right, R.id.key_ok,
            R.id.key_back, R.id.key_home, R.id.key_power, R.id.key_volume_up,
            R.id.key_volume_down, R.id.key_mute, R.id.key_play_pause, R.id.key_rewind,
            R.id.key_fast_forward, R.id.key_previous, R.id.key_next, R.id.key_stop
    };

    private BluetoothHidController hidController;
    private DeviceStore deviceStore;
    private BluetoothDevice connectedDevice;
    private List<DeviceStore.Entry> devices = new ArrayList<>();
    private TextView statusText;
    private TextView connectionDetails;
    private Spinner deviceSpinner;
    private Button connectButton;
    private Button renameButton;
    private Button disconnectButton;
    private EditText textInput;
    private boolean pairingNew;
    private boolean initialized;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        connectionDetails = findViewById(R.id.connection_details);
        deviceSpinner = findViewById(R.id.device_spinner);
        connectButton = findViewById(R.id.connect_device);
        renameButton = findViewById(R.id.rename_device);
        disconnectButton = findViewById(R.id.disconnect_device);
        textInput = findViewById(R.id.text_input);
        deviceStore = new DeviceStore(this);
        hidController = new BluetoothHidController(this, this);

        ((TextView)findViewById(R.id.version_text)).setText(
                "Android v" + BuildConfig.VERSION_NAME + " · Direct BLE HID · Local names only");

        for (int id : keyButtons) {
            Button button = findViewById(id);
            button.setOnClickListener(view -> {
                String key = (String)view.getTag();
                if ("power".equals(key)) {
                    confirmPower();
                } else {
                    hidController.sendKey(key);
                }
            });
        }

        findViewById(R.id.type_text).setOnClickListener(view -> sendText());
        textInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                sendText();
                return true;
            }
            return false;
        });
        connectButton.setOnClickListener(view -> connectSelectedDevice());
        renameButton.setOnClickListener(view -> {
            DeviceStore.Entry selected = selectedDevice();
            if (selected != null) promptRename(selected, false);
        });
        findViewById(R.id.pair_device).setOnClickListener(view -> pairNewDevice());
        disconnectButton.setOnClickListener(view -> {
            pairingNew = false;
            hidController.disconnect();
            connectedDevice = null;
            statusText.setText("Disconnected · Choose Connect or Pair new");
            updateButtons();
        });
        findViewById(R.id.bluetooth_settings).setOnClickListener(view ->
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));

        refreshDevices(null);
        if (hasBluetoothPermissions()) {
            initializeBluetooth();
        } else {
            requestBluetoothPermissions();
        }
    }

    @Override
    protected void onDestroy() {
        if (hidController != null) hidController.close();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != BLUETOOTH_PERMISSION_REQUEST) return;
        if (hasBluetoothPermissions()) {
            initializeBluetooth();
        } else {
            statusText.setText("Bluetooth permissions are required for direct TV control");
            notifyUser("Grant nearby-device Bluetooth permissions to use the remote");
        }
    }

    @Override
    public void onReady() {
        statusText.setText("HID ready · Advertising this phone to Google TV");
        updateButtons();
    }

    @Override
    public void onConnectionChanged(BluetoothDevice device, int state) {
        if (state == BluetoothProfile.STATE_CONNECTED) {
            DeviceStore.Entry existing = deviceStore.find(device.getAddress());
            if (pairingNew && existing != null) {
                hidController.rejectConnection(device);
                notifyUser("Rejected the previously saved TV while pairing a new device");
                return;
            }
            connectedDevice = device;
            pairingNew = false;
            DeviceStore.Entry entry = deviceStore.addOrUpdate(device.getAddress(), deviceName(device));
            refreshDevices(entry.address);
            statusText.setText("Connected to " + entry.name);
            connectionDetails.setText(entry.address + " · Active Bluetooth HID host");
            if (existing == null) promptRename(entry, true);
        } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
            if (connectedDevice == null || connectedDevice.equals(device)) {
                connectedDevice = null;
                connectionDetails.setText("No active connection");
                statusText.setText(pairingNew
                        ? "Pairing · Select this phone in Add accessory on the TV"
                        : "Disconnected");
            }
        } else if (state == BluetoothProfile.STATE_CONNECTING) {
            statusText.setText("Connecting to " + deviceName(device) + "…");
        }
        updateButtons();
    }

    @Override
    public void onAdvertisingChanged(boolean advertising) {
        if (connectedDevice != null) return;
        if (advertising) {
            statusText.setText(pairingNew
                    ? "Pairing · Select this phone in Add accessory on the TV"
                    : "Advertising this phone · Ready to connect");
        } else if (pairingNew) {
            statusText.setText("Advertising stopped · Tap Pair new to retry");
        }
    }

    @Override
    public void onError(String message) {
        statusText.setText(message);
        notifyUser(message);
        updateButtons();
    }

    private void initializeBluetooth() {
        if (initialized) return;
        initialized = true;
        hidController.start();
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[] {
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            }, BLUETOOTH_PERMISSION_REQUEST);
        }
    }

    private void pairNewDevice() {
        pairingNew = true;
        if (connectedDevice != null) hidController.disconnect();
        connectedDevice = null;
        connectionDetails.setText("Pairing a new host; saved TVs will be rejected until pairing completes");
        hidController.startAdvertising();
        statusText.setText("Pairing · Select this phone in Add accessory on the TV");
        notifyUser("On Google TV, open Remotes & Accessories → Add accessory");
    }

    private void connectSelectedDevice() {
        DeviceStore.Entry selected = selectedDevice();
        if (selected == null) {
            notifyUser("No saved device selected");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            notifyUser("Enable Bluetooth first");
            return;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        for (BluetoothDevice device : bonded) {
            if (selected.address.equalsIgnoreCase(device.getAddress())) {
                pairingNew = false;
                statusText.setText("Connecting to " + selected.name + "…");
                hidController.connectTo(device);
                return;
            }
        }
        notifyUser("This TV is not bonded in Android Bluetooth settings");
    }

    private void sendText() {
        hidController.sendText(textInput.getText().toString());
        textInput.selectAll();
    }

    private void confirmPower() {
        if (connectedDevice == null) {
            notifyUser("Google TV is not connected");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Send Power?")
                .setMessage("Send the Power command to " + deviceName(connectedDevice) + "?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Power", (dialog, which) -> hidController.sendKey("power"))
                .show();
    }

    private void promptRename(DeviceStore.Entry entry, boolean newlyPaired) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(entry.name);
        input.setSelectAllOnFocus(true);
        input.setHint("Local device name");
        new AlertDialog.Builder(this)
                .setTitle(newlyPaired ? "Name this TV" : "Local device name")
                .setMessage("Only this app's label changes; the TV's Bluetooth name does not.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    try {
                        DeviceStore.Entry renamed = deviceStore.rename(entry.address,
                                input.getText().toString());
                        refreshDevices(renamed.address);
                        if (connectedDevice != null &&
                                connectedDevice.getAddress().equalsIgnoreCase(renamed.address)) {
                            statusText.setText("Connected to " + renamed.name);
                        }
                    } catch (IllegalArgumentException exception) {
                        notifyUser(exception.getMessage());
                    }
                })
                .show();
    }

    private DeviceStore.Entry selectedDevice() {
        int position = deviceSpinner.getSelectedItemPosition();
        return position >= 0 && position < devices.size() ? devices.get(position) : null;
    }

    private void refreshDevices(String selectedAddress) {
        devices = deviceStore.all();
        ArrayAdapter<DeviceStore.Entry> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, devices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(adapter);
        int selectedPosition = -1;
        for (int i = 0; i < devices.size(); i++) {
            DeviceStore.Entry entry = devices.get(i);
            if (selectedAddress != null && entry.address.equalsIgnoreCase(selectedAddress)) {
                selectedPosition = i;
            }
            if (connectedDevice != null && selectedAddress == null &&
                    entry.address.equalsIgnoreCase(connectedDevice.getAddress())) {
                selectedPosition = i;
            }
        }
        if (selectedPosition >= 0) deviceSpinner.setSelection(selectedPosition);
        updateButtons();
    }

    private void updateButtons() {
        boolean hasSelection = selectedDevice() != null;
        connectButton.setEnabled(hasSelection && connectedDevice == null);
        renameButton.setEnabled(hasSelection);
        disconnectButton.setEnabled(connectedDevice != null);
    }

    private String deviceName(BluetoothDevice device) {
        if (device == null) return "Google TV";
        DeviceStore.Entry saved = deviceStore.find(device.getAddress());
        if (saved != null) return saved.name;
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? "Google TV" : name;
        } catch (SecurityException exception) {
            return "Google TV";
        }
    }

    private void notifyUser(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
