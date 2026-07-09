package com.atakmap.android.plugintemplate.plugin.Platform.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * A single configurable spinner for a {@link PipelineElement} (e.g. "MODEL" -> its choices).
 * Shared between the UI (PresetConfigPane binds/edits these directly) and the network layer
 * (Gson deserializes response bodies straight onto this class) — see PipelinePreset's doc for
 * why fields are mutable and there's no constructor (relies on the implicit default one so Gson
 * doesn't fall back to unsafe allocation).
 */
public class ElementOption {

    public String label;
    public List<String> options = new ArrayList<>();
    public int selectedIndex;
}
