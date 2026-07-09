package com.atakmap.android.plugintemplate.plugin.Platform.Models;

import java.util.ArrayList;
import java.util.List;

/** One stage of a platform's processing pipeline (e.g. "DETECTOR", "CLASSIFIER"). */
public class PipelineElement {

    public final String name;
    public String activeModel;
    public final List<ElementOption> options;

    public PipelineElement(String name, String activeModel, List<ElementOption> options) {
        this.name = name;
        this.activeModel = activeModel;
        this.options = options != null ? options : new ArrayList<>();
    }
}
