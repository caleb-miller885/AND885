package com.atakmap.android.plugintemplate.plugin.Panes;

import static com.atakmap.android.plugintemplate.plugin.HelperFunctions.runOnMainThread;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.editableShapes.Rectangle;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapTouchController;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.plugintemplate.plugin.Constants;
import com.atakmap.android.plugintemplate.plugin.HelperFunctions;
import com.atakmap.android.plugintemplate.plugin.R;
import com.atakmap.android.plugintemplate.plugin.Services.CUASServiceRegistry;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LandingPane {

    private static final int COLOR_FILTER_LABEL     = Color.WHITE;
    private static final int COLOR_SEARCH_ACCENT    = Color.parseColor("#4A9EFF");
    private static final int COLOR_SEARCH_ACTIVE_BG = Color.parseColor("#1A3A5C");

    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final Context pluginContext;
    private final CUASServiceRegistry services;
    private final CUASPaneRegistry registry;
    private Pane pane;

    // Active (classified) list
    private LinearLayout listContainer;
    private TextView tvTotalCount;
    private TextView tvCriticalCount;
    private TextView tvHighCount;
    private TextView tvMinimalCount;
    private TextView tvEmpty;
    private TextView filterAll;
    private TextView filterCritical;
    private TextView filterHigh;
    private TextView filterMinimal;

    // Pending summary section
    private LinearLayout pendingSection;
    private LinearLayout pendingListContainer;
    private TextView tvPendingCount;
    private TextView tvPendingBadge;

    // Collapsible controls panel
    private LinearLayout controlsPanel;
    private ImageView btnCollapseControls;
    private boolean controlsExpanded = true;

    // Search area controls
    private LinearLayout btnSearchArea;
    private TextView btnSearchAreaLabel;
    private ImageView searchAreaIcon;
    private TextView btnSearchAreaClear;
    private boolean searchAreaActive = false;
    private Rectangle activeSearchRect = null;

    private String currentFilter = "ALL";
    private final Map<String, UasEntry>     entries        = new HashMap<>();
    private final Map<String, View>         pendingViews   = new HashMap<>();

    private static final class UasEntry {
        final View view;
        MapItem item;
        String threatLevel;

        UasEntry(View view, MapItem item, String threatLevel) {
            this.view = view;
            this.item = item;
            this.threatLevel = threatLevel;
        }
    }

    public LandingPane(Context pluginContext, CUASServiceRegistry services, CUASPaneRegistry registry) {
        this.pluginContext = pluginContext;
        this.services      = services;
        this.registry      = registry;
    }

    public Pane getPane() {
        if (pane == null) {
            View root = PluginLayoutInflater.inflate(pluginContext, R.layout.landing_page, null);
            initLandingPane(root);
            pane = new PaneBuilder(root)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.65D)
                    .build();
        }
        return pane;
    }

    private void initLandingPane(View root) {
        listContainer        = root.findViewById(R.id.uas_list);
        tvTotalCount         = root.findViewById(R.id.tv_total_count);
        tvCriticalCount      = root.findViewById(R.id.tv_critical_count);
        tvHighCount          = root.findViewById(R.id.tv_high_count);
        tvMinimalCount       = root.findViewById(R.id.tv_minimal_count);
        tvEmpty              = root.findViewById(R.id.tv_empty);
        filterAll            = root.findViewById(R.id.filter_all);
        filterCritical       = root.findViewById(R.id.filter_critical);
        filterHigh           = root.findViewById(R.id.filter_high);
        filterMinimal        = root.findViewById(R.id.filter_minimal);

        pendingSection       = root.findViewById(R.id.pending_section);
        pendingListContainer = root.findViewById(R.id.pending_list);
        tvPendingCount       = root.findViewById(R.id.tv_pending_count);
        tvPendingBadge       = root.findViewById(R.id.tv_pending_badge);

        controlsPanel        = root.findViewById(R.id.controls_panel);
        btnCollapseControls  = root.findViewById(R.id.btn_collapse_controls);

        btnSearchArea        = root.findViewById(R.id.btn_search_area);
        btnSearchAreaLabel   = root.findViewById(R.id.btn_search_area_label);
        searchAreaIcon       = root.findViewById(R.id.search_area_icon);
        btnSearchAreaClear   = root.findViewById(R.id.btn_search_area_clear);

        if (btnCollapseControls != null)
            btnCollapseControls.setOnClickListener(v -> toggleControls());

        // Style the red pending badge
        if (tvPendingBadge != null) {
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.OVAL);
            badgeBg.setColor(Color.RED);
            tvPendingBadge.setBackground(badgeBg);
        }

        filterAll.setOnClickListener(v -> setFilter("ALL"));
        filterCritical.setOnClickListener(v -> setFilter(Constants.THREAT_CRITICAL));
        filterHigh.setOnClickListener(v -> setFilter(Constants.THREAT_HIGH));
        filterMinimal.setOnClickListener(v -> setFilter(Constants.THREAT_MINIMAL));

        checkActiveSearchAreas();
        btnSearchArea.setOnClickListener(v -> {
            if (searchAreaActive) {
                List<MapItem> areas = MapView.getMapView().getRootGroup()
                        .deepFindItems(Constants.SEARCH_AREA, "true");
                if (areas != null && !areas.isEmpty())
                    MapTouchController.goTo(areas.get(0), true);
            } else {
                services.rectangleSearchTool.start();
            }
        });
        btnSearchAreaClear.setOnClickListener(v -> services.rectangleSearchTool.clearActiveArea());

        root.findViewById(R.id.nav_alerts).setOnClickListener(v -> registry.showAlertsPane());
        root.findViewById(R.id.nav_pending).setOnClickListener(v -> registry.showPendingPane());
        root.findViewById(R.id.nav_settings).setOnClickListener(v -> { /* TODO */ });

        styleSearchAreaButton(false);
        setFilter("ALL");
        updateCounts();
        loadExistingItems();
    }

    // ── Controls panel collapse ───────────────────────────────────────────────

    private void toggleControls() {
        controlsExpanded = !controlsExpanded;
        if (controlsPanel != null)
            controlsPanel.setVisibility(controlsExpanded ? View.VISIBLE : View.GONE);
        if (btnCollapseControls != null)
            btnCollapseControls.setRotation(controlsExpanded ? 0f : 180f);
    }

    // ── Search area ───────────────────────────────────────────────────────────

    public void setSearchAreaActive(boolean active, List<MapItem> searchAreas) {
        runOnMainThread(() -> {
            searchAreaActive = active;
            if (active) {
                activeSearchRect = (searchAreas != null && !searchAreas.isEmpty() && searchAreas.get(0) instanceof Rectangle)
                        ? (Rectangle) searchAreas.get(0) : null;
            } else {
                activeSearchRect = null;
            }
            styleSearchAreaButton(active);
            if (btnSearchAreaClear != null)
                btnSearchAreaClear.setVisibility(active ? View.VISIBLE : View.GONE);
            applyFilter();
        });
    }

    public boolean isSearchAreaActive() { return searchAreaActive; }

    public void checkActiveSearchAreas() {
        List<MapItem> areas = MapView.getMapView().getRootGroup()
                .deepFindItems(Constants.SEARCH_AREA, "true");
        setSearchAreaActive(areas != null && !areas.isEmpty(), areas);
    }

    private void styleSearchAreaButton(boolean active) {
        if (btnSearchArea == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(5f);
        if (active) {
            bg.setColor(COLOR_SEARCH_ACTIVE_BG);
            bg.setStroke(1, COLOR_SEARCH_ACCENT);
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(1, COLOR_SEARCH_ACCENT);
        }
        btnSearchArea.setBackground(bg);
        if (btnSearchAreaLabel != null) {
            btnSearchAreaLabel.setText(active
                    ? pluginContext.getString(R.string.search_area_active)
                    : pluginContext.getString(R.string.search_area_define));
        }
    }

    // ── Filter ────────────────────────────────────────────────────────────────

    private void setFilter(String filter) {
        currentFilter = filter;
        updateFilterTabs();
        applyFilter();
    }

    private void updateFilterTabs() {
        styleTab(filterAll,      currentFilter.equals("ALL"),                      COLOR_FILTER_LABEL);
        styleTab(filterCritical, currentFilter.equals(Constants.THREAT_CRITICAL),  HelperFunctions.threatColor(Constants.THREAT_CRITICAL));
        styleTab(filterHigh,     currentFilter.equals(Constants.THREAT_HIGH),      HelperFunctions.threatColor(Constants.THREAT_HIGH));
        styleTab(filterMinimal,  currentFilter.equals(Constants.THREAT_MINIMAL),   HelperFunctions.threatColor(Constants.THREAT_MINIMAL));
    }

    private void styleTab(TextView tab, boolean active, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(5f);
        if (active) {
            bg.setColor(color);
            tab.setTextColor(Color.BLACK);
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(1, color);
            tab.setTextColor(color);
        }
        tab.setBackground(bg);
    }

    private void applyFilter() {
        for (UasEntry entry : entries.values()) {
            boolean matchesThreat = currentFilter.equals("ALL") || currentFilter.equals(entry.threatLevel);
            boolean inArea        = isInSearchArea(entry.item);
            entry.view.setVisibility(matchesThreat && inArea ? View.VISIBLE : View.GONE);
        }
    }

    private boolean isInSearchArea(MapItem item) {
        if (!searchAreaActive || activeSearchRect == null) return true;
        if (!(item instanceof PointMapItem)) return true;
        GeoPoint point = ((PointMapItem) item).getPoint();
        if (point == null) return true;
        GeoPointMetaData[] cornerMeta = activeSearchRect.getGeoPoints();
        if (cornerMeta == null || cornerMeta.length < 3) return true;

        int n = Math.min(cornerMeta.length, 4);
        GeoPoint[] corners = new GeoPoint[n];
        for (int i = 0; i < n; i++) corners[i] = cornerMeta[i].get();

        double cLat = 0, cLon = 0;
        for (GeoPoint c : corners) { cLat += c.getLatitude(); cLon += c.getLongitude(); }
        cLat /= n; cLon /= n;
        final double centLat = cLat, centLon = cLon;
        Arrays.sort(corners, (a, b) -> Double.compare(
                Math.atan2(a.getLatitude() - centLat, a.getLongitude() - centLon),
                Math.atan2(b.getLatitude() - centLat, b.getLongitude() - centLon)));

        double lat = point.getLatitude(), lon = point.getLongitude();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = corners[i].getLatitude(), xi = corners[i].getLongitude();
            double yj = corners[j].getLatitude(), xj = corners[j].getLongitude();
            if (((yi > lat) != (yj > lat)) && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi))
                inside = !inside;
        }
        return inside;
    }

    // ── Counts / badge ────────────────────────────────────────────────────────

    private void updateCounts() {
        int critical = 0, high = 0, minimal = 0;
        for (UasEntry e : entries.values()) {
            if (Constants.THREAT_CRITICAL.equals(e.threatLevel))      critical++;
            else if (Constants.THREAT_HIGH.equals(e.threatLevel))     high++;
            else                                                       minimal++;
        }
        int total = critical + high + minimal;

        if (tvTotalCount    != null) tvTotalCount.setText(total + " ACTIVE");
        if (tvCriticalCount != null) tvCriticalCount.setText(String.valueOf(critical));
        if (tvHighCount     != null) tvHighCount.setText(String.valueOf(high));
        if (tvMinimalCount  != null) tvMinimalCount.setText(String.valueOf(minimal));

        boolean bothEmpty = (total == 0 && pendingViews.isEmpty());
        if (tvEmpty != null) tvEmpty.setVisibility(bothEmpty ? View.VISIBLE : View.GONE);

        updatePendingSection();
    }

    private void updatePendingSection() {
        int count = pendingViews.size();
        if (pendingSection != null)
            pendingSection.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        if (tvPendingCount != null)
            tvPendingCount.setText(count + " PENDING");

        // Red badge on nav_pending icon
        if (tvPendingBadge != null) {
            if (count > 0) {
                tvPendingBadge.setText(String.valueOf(count));
                tvPendingBadge.setVisibility(View.VISIBLE);
            } else {
                tvPendingBadge.setVisibility(View.GONE);
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Routes a new/updated item to pending summary or active list. */
    public void addOrUpdateItem(final MapItem item) {
        runOnMainThread(() -> {
            if (listContainer == null) return;
            String uid      = item.getUID();
            String selected = item.getMetaString(Constants.SELECTED_CLASSIFICATION_RESULT, null);

            if (selected == null) {
                addOrUpdatePendingSummary(item);
            } else {
                View pendingView = pendingViews.remove(uid);
                if (pendingView != null && pendingListContainer != null)
                    pendingListContainer.removeView(pendingView);
                addOrUpdateActive(item, selected);
            }
            updateCounts();
        });
    }

    /** Called by registry after the user has selected a classification in PendingPane. */
    public void onItemClassified(final MapItem item, final String serializedResult) {
        runOnMainThread(() -> {
            String uid = item.getUID();
            View pendingView = pendingViews.remove(uid);
            if (pendingView != null && pendingListContainer != null)
                pendingListContainer.removeView(pendingView);
            addOrUpdateActive(item, serializedResult);
            updateCounts();
        });
    }

    public void removeItem(final MapItem item) {
        runOnMainThread(() -> {
            String uid = item.getUID();

            UasEntry active = entries.remove(uid);
            if (active != null && listContainer != null)
                listContainer.removeView(active.view);

            View pendingView = pendingViews.remove(uid);
            if (pendingView != null && pendingListContainer != null)
                pendingListContainer.removeView(pendingView);

            updateCounts();
        });
    }

    public void clearItems() {
        runOnMainThread(() -> {
            if (listContainer != null)        listContainer.removeAllViews();
            if (pendingListContainer != null) pendingListContainer.removeAllViews();
            entries.clear();
            pendingViews.clear();
            updateCounts();
        });
    }

    // ── Active items ──────────────────────────────────────────────────────────

    private void addOrUpdateActive(MapItem item, String selectedResult) {
        String uid     = item.getUID();
        String[] parts = selectedResult.split("\\|", 5);
        String threatLevel = parts.length >= 1 ? parts[0] : Constants.THREAT_MINIMAL;
        String medium      = parts.length >= 2 ? parts[1] : null;
        String typeName    = parts.length >= 5 ? parts[4] : null;

        UasEntry entry = entries.get(uid);
        if (entry == null) {
            View itemView = PluginLayoutInflater.inflate(pluginContext, R.layout.item_layout, null);
            entry = new UasEntry(itemView, item, threatLevel);
            entries.put(uid, entry);
            listContainer.addView(itemView);
            final UasEntry finalEntry = entry;
            itemView.setOnClickListener(v -> panToItem(finalEntry.item));
        } else {
            entry.item        = item;
            entry.threatLevel = threatLevel;
        }

        bindItemView(entry.view, item, threatLevel, medium, typeName);

        boolean visible = currentFilter.equals("ALL") || currentFilter.equals(threatLevel);
        entry.view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void bindItemView(View v, MapItem item, String threatLevel,
                              String classificationMedium, String typeName) {
        int color = HelperFunctions.threatColor(threatLevel);

        View strip = v.findViewById(R.id.threat_strip);
        if (strip != null) strip.setBackgroundColor(color);

        TextView tvCallsign = v.findViewById(R.id.tv_callsign);
        if (tvCallsign != null) tvCallsign.setText(item.getTitle());

        TextView tvClass = v.findViewById(R.id.tv_classification);
        if (tvClass != null) {
            tvClass.setText(threatLevel);
            tvClass.setTextColor(color);
            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.RECTANGLE);
            badge.setCornerRadius(6f);
            badge.setColor(Color.argb(50, Color.red(color), Color.green(color), Color.blue(color)));
            badge.setStroke(1, color);
            tvClass.setBackground(badge);
        }

        TextView tvDetails = v.findViewById(R.id.tv_details);
        if (tvDetails != null) {
            StringBuilder sb = new StringBuilder();
            if (classificationMedium != null) sb.append(classificationMedium);
            if (typeName != null && !typeName.isEmpty()) {
                if (sb.length() > 0) sb.append("  •  ");
                sb.append(typeName);
            }
            tvDetails.setText(sb.length() > 0 ? sb.toString() : "No details");
        }

        TextView tvTime = v.findViewById(R.id.tv_timestamp);
        if (tvTime != null) tvTime.setText(TIME_FMT.format(new Date()));
    }

    // ── Pending summary rows (single-line, no results inline) ─────────────────

    private void addOrUpdatePendingSummary(MapItem item) {
        String uid = item.getUID();

        View itemView = pendingViews.get(uid);
        if (itemView == null) {
            itemView = PluginLayoutInflater.inflate(pluginContext, R.layout.item_pending_layout, null);
            pendingViews.put(uid, itemView);
            pendingListContainer.addView(itemView);
            final MapItem capturedItem = item;
            // Tap on summary row opens the pending pane for selection
            itemView.setOnClickListener(v -> registry.showPendingPane());
        }

        TextView tvCallsign = itemView.findViewById(R.id.tv_pending_callsign);
        if (tvCallsign != null) tvCallsign.setText(item.getTitle());
        // pending_results_container is intentionally left empty — single-line summary only

        updatePendingSection();
    }

    // ── Load on open ─────────────────────────────────────────────────────────

    private void loadExistingItems() {
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        List<MapItem> items = mv.getRootGroup().deepFindItems(Constants.UAS_ITEM, "true");
        for (MapItem mapItem : items) addOrUpdateItem(mapItem);
    }

    // ── Map pan ───────────────────────────────────────────────────────────────

    private void panToItem(MapItem item) {
        if (!(item instanceof PointMapItem)) return;
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        GeoPoint pt = ((PointMapItem) item).getPoint();
        if (pt != null) MapTouchController.goTo(item, true);
    }
}
