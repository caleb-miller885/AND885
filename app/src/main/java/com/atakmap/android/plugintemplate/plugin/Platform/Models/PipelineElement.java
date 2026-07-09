package com.atakmap.android.plugintemplate.plugin.Platform.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * One stage of a platform's processing pipeline (e.g. "DETECTOR", "CLASSIFIER"). Shared between
 * the UI and the network layer — see PipelinePreset's doc for why fields are mutable and there's
 * no constructor (relies on the implicit default one so Gson doesn't fall back to unsafe
 * allocation).
 */
public class PipelineElement {

    public String name;
    public String activeModel;
    public List<ElementOption> options = new ArrayList<>();
}
