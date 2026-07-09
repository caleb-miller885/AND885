package com.atakmap.android.plugintemplate.plugin.Platform.Models;

import java.util.List;

/** A single configurable spinner for a {@link PipelineElement} (e.g. "MODEL" -> its choices). */
public class ElementOption {

    public final String label;
    public final List<String> choices;
    public int selectedIndex;

    public ElementOption(String label, List<String> choices, int selectedIndex) {
        this.label = label;
        this.choices = choices;
        this.selectedIndex = selectedIndex;
    }
}
