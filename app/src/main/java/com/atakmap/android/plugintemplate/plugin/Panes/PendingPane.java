package com.atakmap.android.plugintemplate.plugin.Panes;

import static com.atakmap.android.plugintemplate.plugin.HelperFunctions.runOnMainThread;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapTouchController;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.plugintemplate.plugin.Constants;
import com.atakmap.android.plugintemplate.plugin.HelperFunctions;
import com.atakmap.android.plugintemplate.plugin.R;
import com.atakmap.coremap.maps.coords.GeoPoint;

import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PendingPane {

    private final Context pluginContext;
    private final CUASPaneRegistry registry;
    private Pane pane;

    private LinearLayout pendingListContainer;
    private TextView tvHeaderCount;
    private TextView tvEmpty;

    private final Map<String, View> pendingViews = new HashMap<>();

    public PendingPane(Context pluginContext, CUASPaneRegistry registry) {
        this.pluginContext = pluginContext;
        this.registry      = registry;
    }

    public Pane getPane() {
        if (pane == null) {
            View root = PluginLayoutInflater.inflate(pluginContext, R.layout.pending_page, null);
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
        pendingListContainer = root.findViewById(R.id.pending_detail_list);
        tvHeaderCount        = root.findViewById(R.id.tv_pending_header_count);
        tvEmpty              = root.findViewById(R.id.tv_pending_empty);

        root.findViewById(R.id.nav_drones).setOnClickListener(v -> registry.showLandingPane());
        root.findViewById(R.id.nav_sensors).setOnClickListener(v -> registry.showSensorsPane());
        root.findViewById(R.id.nav_alerts).setOnClickListener(v -> registry.showAlertsPane());
        root.findViewById(R.id.nav_settings).setOnClickListener(v -> { /* TODO */ });

        loadExistingItems();
        updateHeader();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void addOrUpdateItem(final MapItem item) {
        runOnMainThread(() -> {
            if (pendingListContainer == null) return;
            String uid = item.getUID();
            ArrayList<String> results = item.getMetaStringArrayList(Constants.CLASSIFICATION_RESULTS);

            View itemView = pendingViews.get(uid);
            if (itemView == null) {
                itemView = PluginLayoutInflater.inflate(pluginContext, R.layout.item_pending_layout, null);
                pendingViews.put(uid, itemView);
                pendingListContainer.addView(itemView);
                final MapItem capturedItem = item;
                itemView.setOnClickListener(v -> panToItem(capturedItem));
            }

            bindPendingItem(itemView, item, results);
            updateHeader();
        });
    }

    public void removeItem(final MapItem item) {
        runOnMainThread(() -> {
            View v = pendingViews.remove(item.getUID());
            if (v != null && pendingListContainer != null)
                pendingListContainer.removeView(v);
            updateHeader();
        });
    }

    public void clearItems() {
        runOnMainThread(() -> {
            if (pendingListContainer != null) pendingListContainer.removeAllViews();
            pendingViews.clear();
            updateHeader();
        });
    }

    // ── Binding ───────────────────────────────────────────────────────────────

    private void bindPendingItem(View v, MapItem item, ArrayList<String> results) {
        TextView tvCallsign = v.findViewById(R.id.tv_pending_callsign);
        if (tvCallsign != null) tvCallsign.setText(item.getTitle());

        LinearLayout container = v.findViewById(R.id.pending_results_container);
        if (container == null) return;
        container.removeAllViews();
        if (results == null) return;

        for (String resultStr : results) {
            String[] parts = resultStr.split("\\|", 5);
            if (parts.length < 3) continue;

            String threatLevel = parts[0];
            String medium      = parts[1];
            double confidence;
            try   { confidence = Double.parseDouble(parts[2]); }
            catch (NumberFormatException e) { confidence = 0; }
            String type2525 = parts.length >= 4 ? parts[3] : "";
            String typeName = parts.length >= 5 ? parts[4] : "";

            View row = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.item_classification_result_layout, null);
            int color = HelperFunctions.threatColor(threatLevel);

            View dot = row.findViewById(R.id.result_dot);
            if (dot != null) {
                GradientDrawable dotBg = new GradientDrawable();
                dotBg.setShape(GradientDrawable.OVAL);
                dotBg.setColor(color);
                dot.setBackground(dotBg);
            }

            TextView tvMedium = row.findViewById(R.id.tv_result_medium);
            if (tvMedium != null) tvMedium.setText(medium);

            TextView tvThreat = row.findViewById(R.id.tv_result_threat);
            if (tvThreat != null) {
                tvThreat.setText(threatLevel);
                tvThreat.setTextColor(color);
                GradientDrawable badge = new GradientDrawable();
                badge.setShape(GradientDrawable.RECTANGLE);
                badge.setCornerRadius(6f);
                badge.setColor(Color.argb(50, Color.red(color), Color.green(color), Color.blue(color)));
                badge.setStroke(1, color);
                tvThreat.setBackground(badge);
            }

            TextView tvTypeName = row.findViewById(R.id.tv_result_typename);
            if (tvTypeName != null) tvTypeName.setText(typeName);

            TextView tvConf = row.findViewById(R.id.tv_result_confidence);
            if (tvConf != null)
                tvConf.setText(String.format(Locale.US, "%.0f%%", confidence * 100));

            TextView btnSelect = row.findViewById(R.id.btn_select_result);
            if (btnSelect != null) {
                final String serialized = resultStr;
                btnSelect.setOnClickListener(sel -> registry.onClassificationSelected(item, serialized));
            }

            container.addView(row);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void updateHeader() {
        int count = pendingViews.size();
        if (tvHeaderCount != null)
            tvHeaderCount.setText(count + " PENDING");
        if (tvEmpty != null)
            tvEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
    }

    private void loadExistingItems() {
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        List<MapItem> items = mv.getRootGroup().deepFindItems(Constants.UAS_ITEM, "true");
        for (MapItem item : items) {
            if (item.getMetaString(Constants.SELECTED_CLASSIFICATION_RESULT, null) == null) {
                addOrUpdateItem(item);
            }
        }
    }

    private void panToItem(MapItem item) {
        if (!(item instanceof PointMapItem)) return;
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        GeoPoint pt = ((PointMapItem) item).getPoint();
        if (pt != null) MapTouchController.goTo(item, true);
    }
}
