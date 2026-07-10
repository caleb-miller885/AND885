package com.atakmap.android.plugintemplate.plugin.Panes;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.atakmap.android.maps.MapItem;
import com.atakmap.android.plugintemplate.plugin.Constants;
import com.atakmap.android.plugintemplate.plugin.HelperFunctions;
import com.atakmap.android.plugintemplate.plugin.Models.MockPipelineData;
import com.atakmap.android.plugintemplate.plugin.Platform.PlatformEntry;
import com.atakmap.android.plugintemplate.plugin.Platform.PlatformPipelinePane;
import com.atakmap.android.plugintemplate.plugin.Platform.PlatformStore;
import com.atakmap.android.plugintemplate.plugin.Platform.PresetConfigPane;
import com.atakmap.android.plugintemplate.plugin.Platform.Models.PipelinePreset;
import com.atakmap.android.plugintemplate.plugin.Platform.Network.PlatformApiClient;
import com.atakmap.android.plugintemplate.plugin.Platform.Network.PlatformStatusResponse;
import com.atakmap.android.plugintemplate.plugin.Services.CUASServiceRegistry;

import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class CUASPaneRegistry {

    private final IHostUIService uiService;
    private final Context pluginContext;
    private final CUASServiceRegistry services;

    private LandingPane landingPane;
    private PendingPane pendingPane;
    private AlertsPane  alertsPane;
    private SensorsPane sensorsPane;
    private SettingsPane settingsPane;

    // ── Platform pipeline demo ───────────────────────────────────────────────
    // Everything below is CUAS-local scaffolding to click-test the Platform/ package with mock
    // data. platforms mirrors the host plugin's intended shape: a UID-keyed map of PlatformEntry
    // (see LandingPane's own entries map for the same add/update/remove pattern driven off
    // MapItem UID). currentPlatformUid is the "which platform is the single shared pane editing"
    // pointer, matching how the host's real platformConfigPane is meant to work.
    private PlatformPipelinePane platformPipelinePane;
    private PresetConfigPane     presetConfigPane;
    private final Map<String, PlatformEntry> platforms = new HashMap<>();
    private String currentPlatformUid;
    private final PlatformStore platformStore;

    public CUASPaneRegistry(IHostUIService uiService, Context pluginContext, CUASServiceRegistry services) {
        this.uiService     = uiService;
        this.pluginContext = pluginContext;
        this.services      = services;
        this.platformStore = new PlatformStore(pluginContext);
        services.cotProcessor.setReclassificationListener(this::onReclassificationRequired);
        services.cotProcessor.setPendingItemListener(this::onPendingItemUpdated);
    }

    // ── Pane accessors ────────────────────────────────────────────────────────

    private LandingPane getLandingPane() {
        if (landingPane == null)
            landingPane = new LandingPane(pluginContext, services, this);
        return landingPane;
    }

    private PendingPane getPendingPane() {
        if (pendingPane == null)
            pendingPane = new PendingPane(pluginContext, this);
        return pendingPane;
    }

    private AlertsPane getAlertsPane() {
        if (alertsPane == null)
            alertsPane = new AlertsPane(pluginContext, this);
        return alertsPane;
    }

    private SensorsPane getSensorsPane() {
        if (sensorsPane == null)
            sensorsPane = new SensorsPane(pluginContext, this);
        return sensorsPane;
    }

    private SettingsPane getSettingsPane() {
        if (settingsPane == null)
            settingsPane = new SettingsPane(pluginContext, this);
        return settingsPane;
    }

    private PlatformPipelinePane getPlatformPipelinePane() {
        if (platformPipelinePane == null)
            platformPipelinePane = new PlatformPipelinePane(pluginContext, this);
        return platformPipelinePane;
    }

    private PresetConfigPane getPresetConfigPane() {
        if (presetConfigPane == null)
            presetConfigPane = new PresetConfigPane(pluginContext, this);
        return presetConfigPane;
    }

    // ── Platform pipeline / preset actions ───────────────────────────────────
    // Called directly by PlatformPipelinePane / PresetConfigPane (they hold a CUASPaneRegistry
    // reference — swap that type when Platform/ is transferred into the host plugin).

    public void loadPreset(PipelinePreset preset) {
        PlatformEntry entry = platforms.get(currentPlatformUid);
        if (entry == null) return;
        entry.activePreset = preset;
        getPlatformPipelinePane().setActivePreset(preset);
        //LOAD preset api call
        Toast.makeText(pluginContext, "Loaded preset: " + preset.name, Toast.LENGTH_SHORT).show();
    }

    public void showPresetConfig(PipelinePreset preset) {
        PresetConfigPane cfg = getPresetConfigPane();
        Pane pane = cfg.getPane();
        cfg.setPreset(preset);
        if (uiService != null && !uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    /** User pressed CONNECT in the pipeline pane for whichever platform is currently open. */
    public void connectEndpoint(String endpoint) {
        PlatformEntry entry = platforms.get(currentPlatformUid);
        if (entry == null) return;
        entry.endpoint = endpoint;
        platformStore.save(entry.uid, entry.name, endpoint);
        fetchStatus(entry, endpoint);
    }

    /**
     * Hits GET {endpoint}/status for the given platform. Shared by the manual CONNECT flow above
     * and the auto-reconnect-on-seen flow below — entry is never assumed to be the platform whose
     * pane is currently open, so every UI touch here is gated on entry.uid == currentPlatformUid.
     */
    private void fetchStatus(PlatformEntry entry, String endpoint) {
        entry.connectionState = PlatformEntry.ConnectionState.CONNECTING;
        if (entry.uid.equals(currentPlatformUid))
            getPlatformPipelinePane().setConnectionStatus(entry.connectionState, endpoint);

        String base = endpoint.startsWith("http") ? endpoint : "http://" + endpoint;
        String url  = base.endsWith("/") ? base + "status" : base + "/status";

        PlatformApiClient.SERVICE.getStatus(url).enqueue(new Callback<PlatformStatusResponse>() {
            @Override
            public void onResponse(Call<PlatformStatusResponse> call, Response<PlatformStatusResponse> response) {
                PlatformStatusResponse body = response.isSuccessful() ? response.body() : null;
                onConnectResult(entry, endpoint, response.isSuccessful(),
                        response.isSuccessful() ? null : "HTTP " + response.code(),
                        body != null ? body.pipelineStatus : null,
                        body != null ? body.activePreset : null);
            }

            @Override
            public void onFailure(Call<PlatformStatusResponse> call, Throwable t) {
                onConnectResult(entry, endpoint, false, t.getMessage(), null, null);
            }
        });
    }

    private void onConnectResult(PlatformEntry entry, String endpoint, boolean success,
                                  String failureReason, String pipelineStatus, PipelinePreset activePreset) {
        new Handler(Looper.getMainLooper()).post(() -> {
            entry.connectionState = success
                    ? PlatformEntry.ConnectionState.CONNECTED
                    : PlatformEntry.ConnectionState.DISCONNECTED;
            if (pipelineStatus != null) entry.pipelineStatus = pipelineStatus;
            if (activePreset != null) entry.activePreset = activePreset;

            if (entry.uid.equals(currentPlatformUid)) {
                getPlatformPipelinePane().setConnectionStatus(entry.connectionState, endpoint);
                getPlatformPipelinePane().setPipelineStatus(entry.pipelineStatus);
                if (activePreset != null) getPlatformPipelinePane().setActivePreset(entry.activePreset);
            }
            Toast.makeText(pluginContext,
                    success ? "Connected: " + endpoint : "Connect failed: " + failureReason,
                    Toast.LENGTH_SHORT).show();
        });
    }

    public void savePreset(PipelinePreset preset) {
        Toast.makeText(pluginContext, "Saved preset: " + preset.name, Toast.LENGTH_SHORT).show();
        showPlatformPipelinePane();
        //Edit preset API call
    }

    // ── Show panes ────────────────────────────────────────────────────────────

    public void showLandingPane() {
        Pane pane = getLandingPane().getPane();
        if (uiService != null && !uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    public void showPendingPane() {
        Pane pane = getPendingPane().getPane();
        if (uiService != null && !uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    public void showAlertsPane() {
        Pane pane = getAlertsPane().getPane();
        if (uiService != null && !uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    public void showSensorsPane() {
        Pane pane = getSensorsPane().getPane();
        if (uiService != null && !uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    public void showSettingsPane() {
        Pane pane = getSettingsPane().getPane();
        if (uiService != null && !uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }


    //Needs to be tied into the platform added call
    public void onPlatformSeen(String uid, String name) {
        PlatformEntry entry = platforms.get(uid);
        boolean firstThisSession = entry == null;
        if (firstThisSession) {
            entry = new PlatformEntry(uid);
            platforms.put(uid, entry);
        }
        entry.name = name;

        if (firstThisSession && entry.endpoint == null) {
            String savedEndpoint = platformStore.getEndpoint(uid);
            if (savedEndpoint != null) {
                entry.endpoint = savedEndpoint;
                fetchStatus(entry, savedEndpoint);
            }
        }
    }

    /** The host settings screen's DELETE action for a saved platform. */
    public void forgetPlatform(String uid) {
        platforms.remove(uid);
        platformStore.forget(uid);
    }

    /**
     * Demo entry point — stands in for "settings button on a platform row" in the host plugin.
     * Real usage would be showPlatformPipelinePane(String uid) called from the landing page's
     * per-platform settings button. Routes through onPlatformSeen so the demo shows the real
     * behavior: no endpoint (and no presets) until you configure + CONNECT one, and — if you'd
     * previously connected in an earlier run of the app — an automatic reconnect using whatever
     * PlatformStore remembers.
     */
    public void showPlatformPipelinePane() {
        String uid = "demo-platform-1";
        onPlatformSeen(uid, "SKYRAIDER-3 (DEMO)");

        PlatformEntry entry = platforms.get(uid);
        if (entry.availablePresets.isEmpty())
            entry.availablePresets = MockPipelineData.buildSamplePresets();
        if (entry.activePreset == null)
            entry.activePreset = entry.availablePresets.get(0);

        currentPlatformUid = uid;

        PlatformPipelinePane p = getPlatformPipelinePane();
        Pane pane = p.getPane();
        p.bind(entry);
        if (uiService != null && !uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    public void pushAlert(String message, String severity) {
        getAlertsPane().pushAlert(message, severity);
    }

    // ── Item lifecycle ────────────────────────────────────────────────────────

    public void onEffectorAdded(MapItem item) {
        if (!item.hasMetaValue(Constants.UAS_ITEM)) return;
        getLandingPane().addOrUpdateItem(item);
        if (item.getMetaString(Constants.SELECTED_CLASSIFICATION_RESULT, null) == null)
            getPendingPane().addOrUpdateItem(item);
    }

    public void onEffectorRemoved(MapItem item) {
        if (landingPane != null) landingPane.removeItem(item);
        if (pendingPane  != null) pendingPane.removeItem(item);
    }

    public void onSensorAdded(MapItem item) {
        getSensorsPane().addOrUpdateItem(item);
    }

    public void onSensorRemoved(MapItem item) {
        if (sensorsPane != null) sensorsPane.removeItem(item);
    }

    // ── ClassificationSelectionListener ──────────────────────────────────────
    public void onClassificationSelected(MapItem item, String serializedResult) {
        String[] parts  = serializedResult.split("\\|", 5);
        String type2525 = parts.length >= 4 ? parts[3] : null;

        if (type2525 != null && !type2525.isEmpty()) {
            item.setType(type2525);
            HelperFunctions.refreshOverlayManager();
            HelperFunctions.refreshMapItem(item);
        }

        item.setMetaString(Constants.SELECTED_CLASSIFICATION_RESULT, serializedResult);

        if (pendingPane != null) pendingPane.removeItem(item);
        getLandingPane().onItemClassified(item, serializedResult);
    }

    private static final SimpleDateFormat ALERT_TIME_FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    void onReclassificationRequired(MapItem item, String reason) {
        getLandingPane().removeItem(item);
        getLandingPane().addOrUpdateItem(item);
        getPendingPane().addOrUpdateItem(item);
        String ts = ALERT_TIME_FMT.format(new Date());
        String alertMsg = "[" + ts + "] " + item.getTitle() + ": reclassification required — " + reason;
        getAlertsPane().pushAlert(alertMsg, AlertsPane.SEVERITY_CRITICAL);
    }

    void onPendingItemUpdated(MapItem item) {
        if (pendingPane != null) pendingPane.addOrUpdateItem(item);
        if (landingPane != null) landingPane.addOrUpdateItem(item);
    }

    // ── Search area ───────────────────────────────────────────────────────────

    public void checkActiveSearchAreas() {
        if (landingPane != null) landingPane.checkActiveSearchAreas();
    }

    public void refreshSearchAreaFilter() {
        if (landingPane != null) landingPane.refreshSearchAreaFilter();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void onStop() {
        if (landingPane != null) { landingPane.clearItems(); landingPane = null; }
        if (pendingPane != null) { pendingPane.clearItems(); pendingPane = null; }
        if (alertsPane  != null) { alertsPane.clearItems();  alertsPane  = null; }
        if (sensorsPane != null) { sensorsPane.clearItems(); sensorsPane = null; }
        if (settingsPane != null) { settingsPane.clearItems(); settingsPane = null; }
        if (platformPipelinePane != null) { platformPipelinePane.clearItems(); platformPipelinePane = null; }
        if (presetConfigPane != null) { presetConfigPane.clearItems(); presetConfigPane = null; }
        platforms.clear();
        currentPlatformUid = null;
    }
}
