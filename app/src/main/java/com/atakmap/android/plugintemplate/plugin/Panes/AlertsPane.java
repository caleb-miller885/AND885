package com.atakmap.android.plugintemplate.plugin.Panes;

import static com.atakmap.android.plugintemplate.plugin.HelperFunctions.runOnMainThread;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.plugintemplate.plugin.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;

public class AlertsPane {

    public static final String SEVERITY_CRITICAL = "CRITICAL";
    public static final String SEVERITY_WARNING  = "WARNING";
    public static final String SEVERITY_INFO     = "INFO";

    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static final int COLOR_CRITICAL = Color.parseColor("#F44336");
    private static final int COLOR_WARNING  = Color.parseColor("#FFC107");
    private static final int COLOR_INFO     = Color.parseColor("#4A9EFF");

    private static final class PendingAlert {
        final String message;
        final String severity;
        final String timestamp;
        PendingAlert(String message, String severity, String timestamp) {
            this.message   = message;
            this.severity  = severity;
            this.timestamp = timestamp;
        }
    }

    private final Context pluginContext;
    private final CUASPaneRegistry registry;
    private Pane pane;

    private LinearLayout alertsList;
    private ScrollView alertsScroll;
    private TextView tvHeaderCount;
    private TextView tvEmpty;
    private int alertCount = 0;

    // Holds alerts that arrive before the pane is first inflated
    private final List<PendingAlert> queue = new ArrayList<>();

    public AlertsPane(Context pluginContext, CUASPaneRegistry registry) {
        this.pluginContext = pluginContext;
        this.registry      = registry;
    }

    public Pane getPane() {
        if (pane == null) {
            View root = PluginLayoutInflater.inflate(pluginContext, R.layout.alerts_page, null);
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
        alertsList    = root.findViewById(R.id.alerts_list);
        alertsScroll  = root.findViewById(R.id.alerts_scroll);
        tvHeaderCount = root.findViewById(R.id.tv_alert_header_count);
        tvEmpty       = root.findViewById(R.id.tv_alerts_empty);

        root.findViewById(R.id.nav_drones).setOnClickListener(v -> registry.showLandingPane());
        root.findViewById(R.id.nav_pending).setOnClickListener(v -> registry.showPendingPane());
        root.findViewById(R.id.nav_sensors).setOnClickListener(v -> registry.showSensorsPane());
        root.findViewById(R.id.nav_settings).setOnClickListener(v -> { /* TODO */ });

        // Replay any alerts that arrived before this pane was opened.
        // They were queued in reverse-chronological order so iterate in reverse
        // to insert them newest-first (addView at index 0 each time).
        for (int i = queue.size() - 1; i >= 0; i--) {
            PendingAlert p = queue.get(i);
            addRow(p.message, p.severity, p.timestamp);
        }
        queue.clear();

        updateHeader();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void pushAlert(String message, String severity) {
        String timestamp = TIME_FMT.format(new Date());
        runOnMainThread(() -> {
            if (alertsList == null) {
                // Pane not inflated yet — queue so it appears when the user opens it.
                // alertCount is incremented by addRow when replayed; don't double-count here.
                queue.add(0, new PendingAlert(message, severity, timestamp));
                return;
            }
            addRow(message, severity, timestamp);
            updateHeader();
            if (alertsScroll != null)
                alertsScroll.post(() -> alertsScroll.smoothScrollTo(0, 0));
        });
    }

    public void clearItems() {
        runOnMainThread(() -> {
            queue.clear();
            if (alertsList != null) alertsList.removeAllViews();
            alertCount = 0;
            updateHeader();
        });
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void addRow(String message, String severity, String timestamp) {
        View row = PluginLayoutInflater.inflate(pluginContext, R.layout.item_alert_layout, null);
        bindAlert(row, message, severity, timestamp);
        alertsList.addView(row, 0);
        alertCount++;
        if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);
    }

    private void bindAlert(View row, String message, String severity, String timestamp) {
        int color = colorForSeverity(severity);

        View bar = row.findViewById(R.id.alert_severity_bar);
        if (bar != null) bar.setBackgroundColor(color);

        TextView tvSeverity = row.findViewById(R.id.tv_alert_severity);
        if (tvSeverity != null) {
            tvSeverity.setText(severity);
            tvSeverity.setTextColor(color);
            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.RECTANGLE);
            badge.setCornerRadius(6f);
            badge.setColor(Color.argb(50, Color.red(color), Color.green(color), Color.blue(color)));
            badge.setStroke(1, color);
            tvSeverity.setBackground(badge);
        }

        TextView tvTime = row.findViewById(R.id.tv_alert_time);
        if (tvTime != null) tvTime.setText(timestamp);

        TextView tvMessage = row.findViewById(R.id.tv_alert_message);
        if (tvMessage != null) tvMessage.setText(message);
    }

    private void updateHeader() {
        if (tvHeaderCount != null)
            tvHeaderCount.setText(alertCount + (alertCount == 1 ? " ALERT" : " ALERTS"));
        if (tvEmpty != null)
            tvEmpty.setVisibility(alertCount == 0 ? View.VISIBLE : View.GONE);
    }

    private static int colorForSeverity(String severity) {
        if (severity == null) return COLOR_INFO;
        switch (severity) {
            case SEVERITY_CRITICAL: return COLOR_CRITICAL;
            case SEVERITY_WARNING:  return COLOR_WARNING;
            default:                return COLOR_INFO;
        }
    }
}
