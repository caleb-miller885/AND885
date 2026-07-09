package com.atakmap.android.plugintemplate.plugin.Platform;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.plugintemplate.plugin.Panes.CUASPaneRegistry;
import com.atakmap.android.plugintemplate.plugin.Platform.Models.PipelineElement;
import com.atakmap.android.plugintemplate.plugin.Platform.Models.PipelinePreset;
import com.atakmap.android.plugintemplate.plugin.R;

import java.util.ArrayList;
import java.util.List;

import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;

/**
 * Pipeline overview + preset selector + connection status for a single platform (reached from
 * that platform's settings button on its landing page). One shared instance is reused across
 * platforms; the host tracks which platform UID is "current" and calls {@link #bind(PlatformEntry)}
 * with that platform's {@link PlatformEntry} each time it's shown. User actions are dispatched
 * straight to the {@link CUASPaneRegistry} passed into the constructor — swap that reference for
 * the host plugin's own registry class when this package is transferred over.
 */
public class PlatformPipelinePane {

    private final Context pluginContext;
    private final CUASPaneRegistry registry;
    private Pane pane;

    private TextView tvPlatformName;
    private TextView tvActivePresetName;
    private Spinner spinnerPreset;
    private TextView btnLoad;
    private TextView btnConfigure;

    private View pipelineStatusDot;
    private TextView tvPipelineStatus;
    private TextView tvElementCount;
    private LinearLayout pipelineStrip;

    private View connectionStatusDot;
    private TextView tvConnectionStatus;
    private TextView tvConnectionDetail;
    private TextView btnEditEndpoint;
    private LinearLayout endpointEditRow;
    private EditText etPlatformEndpoint;
    private TextView btnConnect;
    private boolean endpointEditorExpanded = false;

    private PipelinePreset activePreset;
    private List<PipelinePreset> availablePresets = new ArrayList<>();
    private ArrayAdapter<PipelinePreset> presetAdapter;

    public PlatformPipelinePane(Context pluginContext, CUASPaneRegistry registry) {
        this.pluginContext = pluginContext;
        this.registry = registry;
    }

    public Pane getPane() {
        if (pane == null) {
            View root = PluginLayoutInflater.inflate(pluginContext, R.layout.platform_pipeline_page, null);
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
        tvPlatformName      = root.findViewById(R.id.tv_platform_name);
        tvActivePresetName  = root.findViewById(R.id.tv_active_preset_name);
        spinnerPreset        = root.findViewById(R.id.spinner_preset);
        btnLoad              = root.findViewById(R.id.btn_load);
        btnConfigure         = root.findViewById(R.id.btn_configure);

        pipelineStatusDot = root.findViewById(R.id.pipeline_status_dot);
        tvPipelineStatus  = root.findViewById(R.id.tv_pipeline_status);
        tvElementCount    = root.findViewById(R.id.tv_element_count);
        pipelineStrip     = root.findViewById(R.id.pipeline_strip);

        connectionStatusDot = root.findViewById(R.id.connection_status_dot);
        tvConnectionStatus  = root.findViewById(R.id.tv_connection_status);
        tvConnectionDetail  = root.findViewById(R.id.tv_connection_detail);
        btnEditEndpoint      = root.findViewById(R.id.btn_edit_endpoint);
        endpointEditRow      = root.findViewById(R.id.endpoint_edit_row);
        etPlatformEndpoint   = root.findViewById(R.id.et_platform_endpoint);
        btnConnect           = root.findViewById(R.id.btn_connect);

        root.findViewById(R.id.back_button).setOnClickListener(v -> registry.showSettingsPane());

        btnEditEndpoint.setOnClickListener(v -> toggleEndpointEditor());

        btnConnect.setOnClickListener(v -> {
            String endpoint = etPlatformEndpoint.getText().toString().trim();
            if (!endpoint.isEmpty()) registry.connectEndpoint(endpoint);
        });

        presetAdapter = new ArrayAdapter<>(pluginContext, R.layout.platform_spinner_selected, availablePresets);
        presetAdapter.setDropDownViewResource(R.layout.platform_spinner_dropdown);
        spinnerPreset.setAdapter(presetAdapter);

        spinnerPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onPresetSelectionChanged(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        btnLoad.setOnClickListener(v -> {
            PipelinePreset selected = getSelectedPreset();
            if (selected != null) registry.loadPreset(selected);
        });

        btnConfigure.setOnClickListener(v -> {
            PipelinePreset target = getSelectedPreset();
            if (target == null) target = activePreset;
            if (target != null) registry.showPresetConfig(target);
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Binds the whole per-platform bundle in one call; equivalent to the setters below. */
    public void bind(PlatformEntry entry) {
        if (entry == null) return;
        setPlatformName(entry.name);
        setAvailablePresets(entry.availablePresets);
        setActivePreset(entry.activePreset);
        setEndpoint(entry.endpoint);
        setConnectionStatus(entry.connectionState, entry.endpoint);
    }

    public void setPlatformName(String name) {
        if (tvPlatformName != null) tvPlatformName.setText(name);
    }

    /** Full list of presets selectable in the dropdown (should include the active preset). */
    public void setAvailablePresets(List<PipelinePreset> presets) {
        availablePresets = presets != null ? presets : new ArrayList<>();
        presetAdapter.clear();
        presetAdapter.addAll(availablePresets);
        presetAdapter.notifyDataSetChanged();
        selectPresetInSpinner(activePreset);
    }

    /** The preset currently loaded/running in the pipeline. */
    public void setActivePreset(PipelinePreset preset) {
        activePreset = preset;
        if (tvActivePresetName != null)
            tvActivePresetName.setText(preset != null ? preset.name : "—");
        selectPresetInSpinner(preset);
        renderPipeline(preset, false);
    }

    public void clearItems() {
        // No persistent listeners beyond view state; kept for parity with sibling panes.
    }

    // ── Connection status / endpoint ─────────────────────────────────────────

    /** Renders in the same dot+label strip format as the pipeline status row. */
    public void setConnectionStatus(PlatformEntry.ConnectionState state, String detail) {
        if (tvConnectionStatus == null) return;
        int color = connectionColor(state);

        if (connectionStatusDot != null) {
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(color);
            connectionStatusDot.setBackground(dotBg);
        }
        tvConnectionStatus.setText(state.name());
        tvConnectionStatus.setTextColor(color);
        if (tvConnectionDetail != null) tvConnectionDetail.setText(detail != null ? detail : "");
    }

    /** Pre-fills the (collapsed) endpoint editor without changing connection status. */
    public void setEndpoint(String endpoint) {
        if (etPlatformEndpoint != null) etPlatformEndpoint.setText(endpoint != null ? endpoint : "");
    }

    private int connectionColor(PlatformEntry.ConnectionState state) {
        switch (state) {
            case CONNECTED:  return pluginContext.getResources().getColor(R.color.threat_minimal);
            case CONNECTING: return pluginContext.getResources().getColor(R.color.threat_high);
            default:         return pluginContext.getResources().getColor(R.color.threat_critical);
        }
    }

    private void toggleEndpointEditor() {
        endpointEditorExpanded = !endpointEditorExpanded;
        if (endpointEditRow != null)
            endpointEditRow.setVisibility(endpointEditorExpanded ? View.VISIBLE : View.GONE);
        if (btnEditEndpoint != null)
            btnEditEndpoint.setText(endpointEditorExpanded ? "CLOSE" : "EDIT");

        if (endpointEditorExpanded && endpointEditRow != null) {
            // Wait for the GONE -> VISIBLE layout pass so the row has real bounds, then ask the
            // enclosing ScrollView to scroll just enough to bring it fully on screen.
            endpointEditRow.post(() -> {
                Rect rect = new Rect(0, 0, endpointEditRow.getWidth(), endpointEditRow.getHeight());
                endpointEditRow.requestRectangleOnScreen(rect, true);
            });
        }
    }

    // ── Selection / live vs. preview ─────────────────────────────────────────

    private void onPresetSelectionChanged(int position) {
        if (position < 0 || position >= availablePresets.size()) return;
        PipelinePreset selected = availablePresets.get(position);
        boolean isActive = activePreset != null && activePreset.uid.equals(selected.uid);
        renderPipeline(selected, !isActive);
        if (btnLoad != null) {
            btnLoad.setEnabled(!isActive);
            btnLoad.setAlpha(isActive ? 0.5f : 1f);
        }
    }

    private PipelinePreset getSelectedPreset() {
        if (spinnerPreset == null) return null;
        int pos = spinnerPreset.getSelectedItemPosition();
        if (pos < 0 || pos >= availablePresets.size()) return null;
        return availablePresets.get(pos);
    }

    private void selectPresetInSpinner(PipelinePreset preset) {
        if (preset == null || spinnerPreset == null) return;
        for (int i = 0; i < availablePresets.size(); i++) {
            if (availablePresets.get(i).uid.equals(preset.uid)) {
                spinnerPreset.setSelection(i, false);
                break;
            }
        }
    }

    // ── Pipeline strip rendering ──────────────────────────────────────────────

    private void renderPipeline(PipelinePreset preset, boolean preview) {
        if (pipelineStrip == null) return;
        pipelineStrip.removeAllViews();

        int accent = preview
                ? pluginContext.getResources().getColor(R.color.threat_high)
                : pluginContext.getResources().getColor(R.color.threat_minimal);

        if (pipelineStatusDot != null) {
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(accent);
            pipelineStatusDot.setBackground(dotBg);
        }
        if (tvPipelineStatus != null) {
            tvPipelineStatus.setText(preview
                    ? "PREVIEW — " + (preset != null ? preset.name : "") + " (not loaded)"
                    : "PIPELINE — RUNNING");
            tvPipelineStatus.setTextColor(accent);
        }

        List<PipelineElement> elements = preset != null ? preset.elements : new ArrayList<>();
        if (tvElementCount != null)
            tvElementCount.setText(elements.size() + (elements.size() == 1 ? " ELEMENT" : " ELEMENTS"));

        for (int i = 0; i < elements.size(); i++) {
            View chip = PluginLayoutInflater.inflate(pluginContext, R.layout.platform_pipeline_element_chip, null);
            bindChip(chip, elements.get(i), accent);
            pipelineStrip.addView(chip);

            if (i < elements.size() - 1)
                pipelineStrip.addView(makeSeparator());
        }
    }

    private TextView makeSeparator() {
        TextView arrow = new TextView(pluginContext);
        arrow.setText("›");
        arrow.setTextColor(pluginContext.getResources().getColor(R.color.cuas_text_secondary));
        arrow.setTextSize(16f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        arrow.setLayoutParams(lp);
        return arrow;
    }

    private void bindChip(View chip, PipelineElement el, int accent) {
        TextView tvName  = chip.findViewById(R.id.tv_element_name);
        TextView tvModel = chip.findViewById(R.id.tv_element_model);
        if (tvName != null) tvName.setText(el.name);
        if (tvModel != null) tvModel.setText(el.activeModel != null ? el.activeModel : "—");

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(6f);
        bg.setColor(pluginContext.getResources().getColor(R.color.cuas_card_bg));
        bg.setStroke(2, accent);
        chip.setBackground(bg);
    }
}
