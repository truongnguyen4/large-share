package com.datalogic.largeshareapp.security;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.UUID;

/**
 * Persists this device's id and the fleet secret. The secret is meant to be provisioned by the
 * operator (for example pushed by MDM or typed once on the root); the built-in default only exists
 * so a fresh install can talk to another fresh install during development.
 */
public final class IdentityStore {
    public static final String DEFAULT_FLEET_SECRET = "largeshare-default-fleet-secret";
    private static final String PREFS = "largeshare_identity";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_FLEET_SECRET = "fleet_secret";

    private IdentityStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @SuppressLint("HardwareIds")
    public static String getDeviceId(Context context) {
        SharedPreferences p = prefs(context);
        String id = p.getString(KEY_DEVICE_ID, null);
        if (id == null || id.isEmpty()) {
            id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id == null || id.isEmpty()) {
                id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            }
            p.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    public static String getFleetSecret(Context context) {
        return prefs(context).getString(KEY_FLEET_SECRET, DEFAULT_FLEET_SECRET);
    }

    public static void setFleetSecret(Context context, String secret) {
        String value = secret == null || secret.trim().isEmpty() ? DEFAULT_FLEET_SECRET : secret.trim();
        prefs(context).edit().putString(KEY_FLEET_SECRET, value).apply();
    }

    public static Identity load(Context context) {
        return new Identity(getDeviceId(context), getFleetSecret(context));
    }
}
