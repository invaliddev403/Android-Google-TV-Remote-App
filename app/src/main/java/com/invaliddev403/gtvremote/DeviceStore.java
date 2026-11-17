package com.invaliddev403.gtvremote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DeviceStore {
    static final class Entry {
        final String address;
        String name;

        Entry(String address, String name) {
            this.address = address;
            this.name = name;
        }

        @Override
        public String toString() {
            return name + "\n" + address;
        }
    }

    private static final String PREFERENCES = "saved_devices";
    private static final String DEVICES = "devices";
    private final SharedPreferences preferences;

    DeviceStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    List<Entry> all() {
        ArrayList<Entry> result = new ArrayList<>();
        try {
            JSONArray devices = new JSONArray(preferences.getString(DEVICES, "[]"));
            for (int i = 0; i < devices.length(); i++) {
                JSONObject device = devices.getJSONObject(i);
                result.add(new Entry(device.getString("address"), device.getString("name")));
            }
        } catch (JSONException ignored) {
            preferences.edit().remove(DEVICES).apply();
        }
        return result;
    }

    Entry addOrUpdate(String address, String name) {
        ArrayList<Entry> devices = new ArrayList<>(all());
        String cleanName = cleanName(name, address);
        for (Entry device : devices) {
            if (device.address.equalsIgnoreCase(address)) {
                device.name = cleanName;
                save(devices);
                return device;
            }
        }
        Entry added = new Entry(address.toUpperCase(Locale.ROOT), cleanName);
        devices.add(added);
        save(devices);
        return added;
    }

    Entry rename(String address, String name) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 64) {
            throw new IllegalArgumentException("Enter a name between 1 and 64 characters");
        }
        ArrayList<Entry> devices = new ArrayList<>(all());
        for (Entry device : devices) {
            if (device.address.equalsIgnoreCase(address)) {
                device.name = cleanName;
                save(devices);
                return device;
            }
        }
        throw new IllegalArgumentException("Device is not saved");
    }

    Entry find(String address) {
        if (address == null) return null;
        for (Entry device : all()) {
            if (device.address.equalsIgnoreCase(address)) return device;
        }
        return null;
    }

    private static String cleanName(String name, String fallback) {
        String cleaned = name == null ? "" : name.trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private void save(List<Entry> devices) {
        JSONArray json = new JSONArray();
        for (Entry device : devices) {
            try {
                JSONObject item = new JSONObject();
                item.put("address", device.address);
                item.put("name", device.name);
                json.put(item);
            } catch (JSONException ignored) {
                return;
            }
        }
        preferences.edit().putString(DEVICES, json.toString()).apply();
    }
}
