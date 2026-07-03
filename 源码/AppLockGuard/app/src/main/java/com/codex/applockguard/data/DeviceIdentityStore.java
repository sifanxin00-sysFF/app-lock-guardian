package com.codex.applockguard.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.UUID;

public final class DeviceIdentityStore {
    private static final String PREFS = "device_identity_store";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_SECRET = "device_secret";

    private final SharedPreferences prefs;

    public DeviceIdentityStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getDeviceId() {
        String value = prefs.getString(KEY_DEVICE_ID, null);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        value = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, value).apply();
        return value;
    }

    public String getDeviceSecret() {
        String value = prefs.getString(KEY_DEVICE_SECRET, null);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        value = out.toString();
        prefs.edit().putString(KEY_DEVICE_SECRET, value).apply();
        return value;
    }
}
