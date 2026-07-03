package com.atakmap.android.plugintemplate.plugin.Panes;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.plugintemplate.plugin.Constants;
import com.atakmap.android.plugintemplate.plugin.R;
import com.atakmap.android.preference.AtakPreferences;

import java.util.Locale;

import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;

public class SettingsPane {

    private final Context pluginContext;
    private final CUASPaneRegistry registry;
    private Pane pane;

    private EditText etThreshold;
    private TextView tvStatus;

    public SettingsPane(Context pluginContext, CUASPaneRegistry registry) {
        this.pluginContext = pluginContext;
        this.registry      = registry;
    }

    public Pane getPane() {
        if (pane == null) {
            View root = PluginLayoutInflater.inflate(pluginContext, R.layout.settings_page, null);
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
        etThreshold = root.findViewById(R.id.et_reclassification_threshold);
        tvStatus    = root.findViewById(R.id.tv_reclassification_threshold_status);

        etThreshold.setText(String.format(Locale.US, "%.2f", getSavedThreshold()));

        root.findViewById(R.id.btn_save_reclassification_threshold)
                .setOnClickListener(v -> onSaveThreshold());

        root.findViewById(R.id.nav_drones).setOnClickListener(v -> registry.showLandingPane());
        root.findViewById(R.id.nav_pending).setOnClickListener(v -> registry.showPendingPane());
        root.findViewById(R.id.nav_sensors).setOnClickListener(v -> registry.showSensorsPane());
        root.findViewById(R.id.nav_alerts).setOnClickListener(v -> registry.showAlertsPane());
    }

    private void onSaveThreshold() {
        String raw = etThreshold.getText().toString().trim();
        float value;
        try {
            value = Float.parseFloat(raw);
        } catch (NumberFormatException e) {
            showStatus("Enter a valid number", true);
            return;
        }
        if (value < 0.0 || value > 1.0) {
            showStatus("Value must be between 0.0 and 1.0", true);
            return;
        }

        SharedPreferences.Editor editor = AtakPreferences.getInstance(pluginContext)
                .getSharedPrefs()
                .edit();
        editor.putFloat(Constants.PREF_RECLASSIFICATION_THRESHOLD, value);
        editor.apply();

        showStatus("Saved", false);
    }

    private double getSavedThreshold() {
        return AtakPreferences.getInstance(pluginContext)
                .getSharedPrefs()
                .getFloat(Constants.PREF_RECLASSIFICATION_THRESHOLD,
                        (float) Constants.DEFAULT_RECLASSIFICATION_THRESHOLD);
    }

    private void showStatus(String message, boolean isError) {
        if (tvStatus == null) return;
        tvStatus.setText(message);
        tvStatus.setTextColor(pluginContext.getResources().getColor(
                isError ? R.color.threat_critical : R.color.threat_minimal));
    }

    public void clearItems() {
        // No dynamic list state to clear; kept for parity with sibling panes.
    }
}
