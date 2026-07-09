package com.atakmap.android.plugintemplate.plugin.Platform;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.plugintemplate.plugin.Panes.CUASPaneRegistry;
import com.atakmap.android.plugintemplate.plugin.Platform.Models.ElementOption;
import com.atakmap.android.plugintemplate.plugin.Platform.Models.PipelineElement;
import com.atakmap.android.plugintemplate.plugin.Platform.Models.PipelinePreset;
import com.atakmap.android.plugintemplate.plugin.R;

import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;

/**
 * Per-element configuration screen for a single preset, reached from
 * {@link PlatformPipelinePane}'s "CONFIGURE PRESET" nav button. Renders one card per
 * {@link PipelineElement}, one spinner per {@link ElementOption}. Edits are written straight
 * back onto the preset object as the user picks; SAVE just hands that (already-mutated) preset
 * to the {@link CUASPaneRegistry} passed into the constructor — swap that reference for the host
 * plugin's own registry class when this package is transferred over.
 */
public class PresetConfigPane {

    private final Context pluginContext;
    private final CUASPaneRegistry registry;
    private Pane pane;

    private TextView tvPresetName;
    private LinearLayout elementsContainer;
    private TextView btnSave;

    private PipelinePreset preset;

    public PresetConfigPane(Context pluginContext, CUASPaneRegistry registry) {
        this.pluginContext = pluginContext;
        this.registry = registry;
    }

    public Pane getPane() {
        if (pane == null) {
            View root = PluginLayoutInflater.inflate(pluginContext, R.layout.platform_preset_config_page, null);
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
        tvPresetName      = root.findViewById(R.id.tv_config_preset_name);
        elementsContainer = root.findViewById(R.id.elements_container);
        btnSave           = root.findViewById(R.id.btn_save_preset);

        root.findViewById(R.id.back_button).setOnClickListener(v -> registry.showPlatformPipelinePane());

        btnSave.setOnClickListener(v -> {
            if (preset != null) registry.savePreset(preset);
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setPreset(PipelinePreset preset) {
        this.preset = preset;
        if (tvPresetName != null)
            tvPresetName.setText(preset != null ? preset.name : "—");
        renderElements();
    }

    public void clearItems() {
        // No persistent listeners beyond view state; kept for parity with sibling panes.
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void renderElements() {
        if (elementsContainer == null) return;
        elementsContainer.removeAllViews();
        if (preset == null) return;

        for (PipelineElement element : preset.elements) {
            View card = PluginLayoutInflater.inflate(pluginContext, R.layout.platform_preset_config_element, null);
            bindElementCard(card, element);
            elementsContainer.addView(card);
        }
    }

    private void bindElementCard(View card, PipelineElement element) {
        TextView tvName = card.findViewById(R.id.tv_element_name);
        if (tvName != null) tvName.setText(element.name);

        LinearLayout optionsContainer = card.findViewById(R.id.options_container);
        if (optionsContainer == null) return;
        optionsContainer.removeAllViews();

        for (ElementOption option : element.options) {
            View row = PluginLayoutInflater.inflate(pluginContext, R.layout.platform_config_option_row, null);
            bindOptionRow(row, element, option);
            optionsContainer.addView(row);
        }
    }

    private void bindOptionRow(View row, PipelineElement element, ElementOption option) {
        TextView tvLabel = row.findViewById(R.id.tv_option_label);
        Spinner spinner   = row.findViewById(R.id.spinner_option);
        if (tvLabel != null) tvLabel.setText(option.label);
        if (spinner == null) return;

        ArrayAdapter<String> adapter = new ArrayAdapter<>(pluginContext,
                R.layout.platform_spinner_selected, option.options);
        adapter.setDropDownViewResource(R.layout.platform_spinner_dropdown);
        spinner.setAdapter(adapter);
        spinner.setSelection(option.selectedIndex, false);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                option.selectedIndex = position;
                if ("MODEL".equalsIgnoreCase(option.label) && position < option.options.size())
                    element.activeModel = option.options.get(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }
}
