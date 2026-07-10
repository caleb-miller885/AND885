package com.atakmap.android.plugintemplate.plugin.Platform;

import android.content.Context;

import com.atakmap.android.preference.AtakPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists what's known about each platform across app restarts — currently just its endpoint
 * (+ a display name, for a settings list that can show something readable even for a platform
 * that isn't currently broadcasting). Keyed by platform UID.
 *
 * Nothing live (connection state, presets, pipeline status) belongs here — that's re-fetched
 * once reconnected. Backed by AtakPreferences (the same mechanism CUAS's own SettingsPane already
 * uses for its reclassification threshold), holding one JSON blob under one key rather than one
 * pref-per-platform, since the point is being able to list and delete entries as a set (the
 * host's "saved platforms" settings screen), not just look one up by UID.
 */
public class PlatformStore {

    private static final String KEY_PLATFORMS = "platform_store_platforms";
    private static final Gson GSON = new Gson();

    private final AtakPreferences prefs;

    public PlatformStore(Context context) {
        this.prefs = AtakPreferences.getInstance(context);
    }

    public static class SavedPlatform {
        public String uid;
        public String name;
        public String endpoint;
    }

    /** Every saved platform, uid -> SavedPlatform. Empty (never null) if nothing's saved yet. */
    public Map<String, SavedPlatform> loadAll() {
        String json = prefs.get(KEY_PLATFORMS, (String) null);
        if (json == null) return new LinkedHashMap<>();
        Type type = new TypeToken<LinkedHashMap<String, SavedPlatform>>() { }.getType();
        Map<String, SavedPlatform> loaded = GSON.fromJson(json, type);
        return loaded != null ? loaded : new LinkedHashMap<>();
    }

    /** The saved endpoint for one platform, or null if it's never been configured. */
    public String getEndpoint(String uid) {
        SavedPlatform saved = loadAll().get(uid);
        return saved != null ? saved.endpoint : null;
    }

    /** Called once a platform's endpoint is successfully configured (see connectEndpoint). */
    public void save(String uid, String name, String endpoint) {
        Map<String, SavedPlatform> all = loadAll();
        SavedPlatform saved = new SavedPlatform();
        saved.uid = uid;
        saved.name = name;
        saved.endpoint = endpoint;
        all.put(uid, saved);
        writeAll(all);
    }

    /** Removes a platform's saved endpoint — the host settings screen's DELETE action. */
    public void forget(String uid) {
        Map<String, SavedPlatform> all = loadAll();
        if (all.remove(uid) != null) writeAll(all);
    }

    private void writeAll(Map<String, SavedPlatform> all) {
        prefs.set(KEY_PLATFORMS, GSON.toJson(all));
    }
}
