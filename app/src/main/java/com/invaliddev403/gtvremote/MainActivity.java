package com.invaliddev403.gtvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity implements TvRemoteController.Listener {
    private final int[] keyButtons = {
            R.id.key_up, R.id.key_down, R.id.key_left, R.id.key_right, R.id.key_ok,
            R.id.key_back, R.id.key_home, R.id.key_power, R.id.key_volume_up,
            R.id.key_volume_down, R.id.key_mute, R.id.key_play_pause, R.id.key_rewind,
            R.id.key_fast_forward, R.id.key_previous, R.id.key_next, R.id.key_stop
    };

    private GtvRemoteApplication application;
    private TvRemoteController remoteController;
    private DeviceStore deviceStore;
    private List<DeviceStore.Entry> devices = new ArrayList<>();
    private TextView statusText;
    private TextView connectionDetails;
    private TextView transportStatus;
    private Spinner deviceSpinner;
    private Button connectButton;
    private Button renameButton;
    private Button disconnectButton;
    private EditText textInput;
    private TvRemoteController.State currentState = TvRemoteController.State.DISCONNECTED;
    private String pairingHost;
    private AlertDialog pairingCodeDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        connectionDetails = findViewById(R.id.connection_details);
        transportStatus = findViewById(R.id.transport_status);
        deviceSpinner = findViewById(R.id.device_spinner);
        connectButton = findViewById(R.id.connect_device);
        renameButton = findViewById(R.id.rename_device);
        disconnectButton = findViewById(R.id.disconnect_device);
        textInput = findViewById(R.id.text_input);
        deviceStore = new DeviceStore(this);
        application = (GtvRemoteApplication)getApplication();
        application.setRemoteListener(this);
        remoteController = application.remoteController();
        currentState = remoteController.getState();

        ((TextView)findViewById(R.id.version_text)).setText(
                "Android v" + BuildConfig.VERSION_NAME + " · Wi-Fi remote · Local names only");

        for (int id : keyButtons) {
            Button button = findViewById(id);
            button.setOnClickListener(view -> {
                String key = (String)view.getTag();
                if ("power".equals(key)) confirmPower();
                else remoteController.sendKey(key);
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
        connectButton.setOnClickListener(view -> {
            DeviceStore.Entry selected = selectedDevice();
            if (selected != null) remoteController.connect(selected.address);
        });
        renameButton.setOnClickListener(view -> {
            DeviceStore.Entry selected = selectedDevice();
            if (selected != null) promptRename(selected);
        });
        findViewById(R.id.forget_device).setOnClickListener(view -> confirmForget());
        findViewById(R.id.pair_device).setOnClickListener(view -> promptPairingHost());
        disconnectButton.setOnClickListener(view -> {
            pairingHost = null;
            dismissPairingCode();
            remoteController.disconnect();
        });
        findViewById(R.id.wifi_settings).setOnClickListener(view ->
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));

        refreshDevices(null);
        renderState(currentState, remoteController.getHost(),
                currentState == TvRemoteController.State.CONNECTED ? "Connected over Wi-Fi" : "Not connected");
        onDeviceInfoChanged();
    }

    @Override
    protected void onDestroy() {
        dismissPairingCode();
        application.clearRemoteListener(this);
        super.onDestroy();
    }

    @Override
    public void onStateChanged(TvRemoteController.State state, String host, String message) {
        currentState = state;
        renderState(state, host, message);
        if (state == TvRemoteController.State.AWAITING_CODE) promptPairingCode();
        if (state == TvRemoteController.State.CONNECTED) {
            DeviceStore.Entry entry = deviceStore.addOrUpdate(host, host);
            boolean newlyPaired = host.equalsIgnoreCase(pairingHost);
            pairingHost = null;
            refreshDevices(entry.address);
            if (newlyPaired) promptRename(entry);
        }
        if (state == TvRemoteController.State.DISCONNECTED ||
                state == TvRemoteController.State.ERROR) {
            dismissPairingCode();
        }
        updateButtons();
    }

    @Override
    public void onDeviceInfoChanged() {
        if (transportStatus != null) transportStatus.setText(remoteController.deviceSummary());
    }

    @Override
    public void onError(String message) {
        notifyUser(message);
    }

    private void renderState(TvRemoteController.State state, String host, String message) {
        statusText.setText(message);
        int color = state == TvRemoteController.State.CONNECTED ? R.color.success
                : state == TvRemoteController.State.ERROR ? R.color.danger : R.color.accent;
        statusText.setTextColor(getColor(color));
        connectionDetails.setText(host.isEmpty() ? "Phone and TV must use the same local network"
                : host + " · TLS ports 6466/6467");
        updateButtons();
    }

    private void promptPairingHost() {
        EditText input = dialogInput("TV IP address, for example 192.168.1.50");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        DeviceStore.Entry selected = selectedDevice();
        String existing = remoteController.getHost().isEmpty()
                ? selected == null ? "" : selected.address : remoteController.getHost();
        input.setText(existing);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Pair with Google TV")
                .setMessage("Enter the TV's local IP address. Find it on the TV under Settings → Network & Internet.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Pair", (dialog, which) -> {
                    pairingHost = input.getText().toString().trim();
                    remoteController.pair(pairingHost);
                })
                .show();
    }

    private void promptPairingCode() {
        dismissPairingCode();
        EditText input = dialogInput("Six-character code shown on TV");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        pairingCodeDialog = new AlertDialog.Builder(this)
                .setTitle("Enter TV pairing code")
                .setMessage("The code contains hexadecimal characters: 0–9 and A–F.")
                .setView(input)
                .setNegativeButton("Cancel", (dialog, which) -> {
                    pairingHost = null;
                    remoteController.disconnect();
                })
                .setPositiveButton("Confirm", (dialog, which) ->
                        remoteController.finishPairing(input.getText().toString()))
                .setOnCancelListener(dialog -> {
                    pairingHost = null;
                    remoteController.disconnect();
                })
                .create();
        pairingCodeDialog.show();
    }

    private void dismissPairingCode() {
        if (pairingCodeDialog != null) pairingCodeDialog.dismiss();
        pairingCodeDialog = null;
    }

    private EditText dialogInput(String hint) {
        EditText input = new EditText(this);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, 0, padding, 0);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(getColor(R.color.muted));
        return input;
    }

    private void sendText() {
        remoteController.sendText(textInput.getText().toString());
        textInput.selectAll();
    }

    private void confirmPower() {
        if (!remoteController.isConnected()) {
            notifyUser("Connect to Google TV first");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Send Power?")
                .setMessage("Send the Power command to " + remoteController.getHost() + "?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Power", (dialog, which) -> remoteController.sendKey("power"))
                .show();
    }

    private void promptRename(DeviceStore.Entry entry) {
        EditText input = dialogInput("Local device name");
        input.setText(entry.name);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Name this TV")
                .setMessage("Only this app's label changes; the TV's network name does not.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    try {
                        DeviceStore.Entry renamed = deviceStore.rename(entry.address,
                                input.getText().toString());
                        refreshDevices(renamed.address);
                    } catch (IllegalArgumentException exception) {
                        notifyUser(exception.getMessage());
                    }
                })
                .show();
    }

    private void confirmForget() {
        DeviceStore.Entry selected = selectedDevice();
        if (selected == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Forget " + selected.name + "?")
                .setMessage("This removes the local name and IP. You may also need to remove GTV Remote from the TV's paired devices before pairing again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget", (dialog, which) -> {
                    if (selected.address.equalsIgnoreCase(remoteController.getHost())) {
                        remoteController.disconnect();
                    }
                    deviceStore.remove(selected.address);
                    refreshDevices(null);
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
        for (int index = 0; index < devices.size(); index++) {
            DeviceStore.Entry entry = devices.get(index);
            if (selectedAddress != null && entry.address.equalsIgnoreCase(selectedAddress) ||
                    selectedAddress == null && entry.address.equalsIgnoreCase(remoteController.getHost())) {
                deviceSpinner.setSelection(index);
                break;
            }
        }
        updateButtons();
    }

    private void updateButtons() {
        if (connectButton == null) return;
        boolean idle = currentState == TvRemoteController.State.DISCONNECTED ||
                currentState == TvRemoteController.State.ERROR;
        connectButton.setEnabled(selectedDevice() != null && idle);
        renameButton.setEnabled(selectedDevice() != null);
        disconnectButton.setEnabled(!idle);
        disconnectButton.setText(currentState == TvRemoteController.State.CONNECTED
                ? "Disconnect" : "Cancel");
    }

    private void notifyUser(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
