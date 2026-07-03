package com.atakmap.android.plugintemplate.plugin.Panes;

import static com.atakmap.android.plugintemplate.plugin.HelperFunctions.runOnMainThread;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapTouchController;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.plugintemplate.plugin.Constants;
import com.atakmap.android.plugintemplate.plugin.R;
import com.atakmap.coremap.maps.coords.GeoPoint;

import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SensorsPane {

    private final Context pluginContext;
    private final CUASPaneRegistry registry;
    private Pane pane;

    private LinearLayout sensorListContainer;
    private TextView tvHeaderCount;
    private TextView tvEmpty;

    private final Map<String, View> sensorViews = new HashMap<>();

    public SensorsPane(Context pluginContext, CUASPaneRegistry registry) {
        this.pluginContext = pluginContext;
        this.registry      = registry;
    }

    public Pane getPane() {
        if (pane == null) {
            View root = PluginLayoutInflater.inflate(pluginContext, R.layout.sensors_page, null);
            initPane(root);
            pane = new PaneBuilder(root)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.65D)
                    .build();
        }
        return pane;
    }

    private void initPane(View root) {
        sensorListContainer = root.findViewById(R.id.sensor_list);
        tvHeaderCount       = root.findViewById(R.id.tv_sensor_header_count);
        tvEmpty             = root.findViewById(R.id.tv_sensors_empty);

        root.findViewById(R.id.nav_drones).setOnClickListener(v -> registry.showLandingPane());
        root.findViewById(R.id.nav_pending).setOnClickListener(v -> registry.showPendingPane());
        root.findViewById(R.id.nav_alerts).setOnClickListener(v -> registry.showAlertsPane());
        root.findViewById(R.id.nav_settings).setOnClickListener(v -> registry.showSettingsPane());

        loadExistingItems();
        updateHeader();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void addOrUpdateItem(final MapItem item) {
        runOnMainThread(() -> {
            if (sensorListContainer == null) return;
            String uid = item.getUID();

            View itemView = sensorViews.get(uid);
            if (itemView == null) {
                itemView = PluginLayoutInflater.inflate(pluginContext, R.layout.item_sensor_layout, null);
                sensorViews.put(uid, itemView);
                sensorListContainer.addView(itemView);
                final MapItem captured = item;
                itemView.setOnClickListener(v -> panToItem(captured));
            }

            bindSensorRow(itemView, item);
            updateHeader();
        });
    }

    public void removeItem(final MapItem item) {
        runOnMainThread(() -> {
            View v = sensorViews.remove(item.getUID());
            if (v != null && sensorListContainer != null)
                sensorListContainer.removeView(v);
            updateHeader();
        });
    }

    public void clearItems() {
        runOnMainThread(() -> {
            if (sensorListContainer != null) sensorListContainer.removeAllViews();
            sensorViews.clear();
            updateHeader();
        });
    }

    // ── Binding ───────────────────────────────────────────────────────────────

    private void bindSensorRow(View v, MapItem item) {
        TextView tvCallsign = v.findViewById(R.id.tv_sensor_callsign);
        if (tvCallsign != null) tvCallsign.setText(item.getTitle());

        TextView tvType = v.findViewById(R.id.tv_sensor_type);
        if (tvType != null) tvType.setText(item.getType());

        double fov   = 0, range = 0;
        try { fov   = Double.parseDouble(item.getMetaString("dewcCuas.sensorFOV",   "0")); } catch (Exception ignored) {}
        try { range = Double.parseDouble(item.getMetaString("dewcCuas.sensorRange", "0")); } catch (Exception ignored) {}

        TextView tvFov = v.findViewById(R.id.tv_sensor_fov);
        if (tvFov != null)
            tvFov.setText(String.format(Locale.US, "FOV %.0f°", fov));

        TextView tvRange = v.findViewById(R.id.tv_sensor_range);
        if (tvRange != null)
            tvRange.setText(String.format(Locale.US, "RNG %.0f m", range));

        TextView btnVis = v.findViewById(R.id.btn_sensor_visibility);
        if (btnVis != null) {
            applyVisibilityButton(btnVis, item.getVisible());
            btnVis.setOnClickListener(btn -> {
                boolean nowVisible = !item.getVisible();
                item.setVisible(nowVisible);
                applyVisibilityButton(btnVis, nowVisible);
            });
        }
    }

    private static void applyVisibilityButton(TextView btn, boolean visible) {
        if (visible) {
            btn.setText("HIDE");
            btn.setTextColor(Color.WHITE);
        } else {
            btn.setText("SHOW");
            btn.setTextColor(0xFF7A8090);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void updateHeader() {
        int count = sensorViews.size();
        if (tvHeaderCount != null)
            tvHeaderCount.setText(count + (count == 1 ? " SENSOR" : " SENSORS"));
        if (tvEmpty != null)
            tvEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
    }

    private void loadExistingItems() {
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        List<MapItem> items = mv.getRootGroup().deepFindItems(Constants.SENSOR_ITEM, "true");
        for (MapItem item : items) addOrUpdateItem(item);
    }

    private void panToItem(MapItem item) {
        if (!(item instanceof PointMapItem)) return;
        GeoPoint pt = ((PointMapItem) item).getPoint();
        if (pt != null) MapTouchController.goTo(item, true);
    }
}
