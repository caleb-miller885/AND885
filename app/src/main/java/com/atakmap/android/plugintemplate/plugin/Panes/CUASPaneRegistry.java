package com.atakmap.android.plugintemplate.plugin.Panes;

import android.content.Context;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.plugintemplate.plugin.Constants;
import com.atakmap.android.plugintemplate.plugin.Services.CUASServiceRegistry;

import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;

public class CUASPaneRegistry implements ClassificationSelectionListener {

    private final IHostUIService uiService;
    private final Context pluginContext;
    private final CUASServiceRegistry services;

    private LandingPane landingPane;
    private PendingPane pendingPane;
    private AlertsPane  alertsPane;

    public CUASPaneRegistry(IHostUIService uiService, Context pluginContext, CUASServiceRegistry services) {
        this.uiService     = uiService;
        this.pluginContext = pluginContext;
        this.services      = services;
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

    public void pushAlert(String message, String severity) {
        getAlertsPane().pushAlert(message, severity);
    }

    // ── Item lifecycle ────────────────────────────────────────────────────────

    public void onItemAdded(MapItem item) {
        if (!item.hasMetaValue(Constants.UAS_ITEM)) return;
        getLandingPane().addOrUpdateItem(item);
        if (item.getMetaString(Constants.SELECTED_CLASSIFICATION_RESULT, null) == null)
            getPendingPane().addOrUpdateItem(item);
    }

    public void onItemRemoved(MapItem item) {
        if (landingPane != null) landingPane.removeItem(item);
        if (pendingPane  != null) pendingPane.removeItem(item);
    }

    // ── ClassificationSelectionListener ──────────────────────────────────────

    @Override
    public void onClassificationSelected(MapItem item, String serializedResult) {
        // Parse type2525 from position 3 of the pipe-delimited result
        String[] parts  = serializedResult.split("\\|", 5);
        String type2525 = parts.length >= 4 ? parts[3] : null;

        if (type2525 != null && !type2525.isEmpty()) {
            item.setType(type2525);
            MapView mv = MapView.getMapView();
            if (mv != null)
                mv.getMapEventDispatcher().dispatch(
                        new MapEvent.Builder(MapEvent.ITEM_REFRESH).setItem(item).build());
        }

        item.setMetaString(Constants.SELECTED_CLASSIFICATION_RESULT, serializedResult);

        if (pendingPane != null) pendingPane.removeItem(item);
        getLandingPane().onItemClassified(item, serializedResult);
    }

    private static final SimpleDateFormat ALERT_TIME_FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    void onReclassificationRequired(MapItem item, String reason) {
        // Always route through getLandingPane() so the instance is non-null
        // and the pending summary section updates regardless of whether the pane is visible.
        getLandingPane().removeItem(item);
        getLandingPane().addOrUpdateItem(item);
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void onStop() {
        if (landingPane != null) { landingPane.clearItems(); landingPane = null; }
        if (pendingPane != null) { pendingPane.clearItems(); pendingPane = null; }
        if (alertsPane  != null) { alertsPane.clearItems();  alertsPane  = null; }
    }
}
