package com.atakmap.android.plugintemplate.plugin.Platform.Models;

import java.util.ArrayList;
import java.util.List;

/** A named, ordered set of {@link PipelineElement}s that can be loaded onto a platform. */
public class PipelinePreset {

    public final String uid;
    public final String name;
    public final List<PipelineElement> elements;

    public PipelinePreset(String uid, String name, List<PipelineElement> elements) {
        this.uid = uid;
        this.name = name;
        this.elements = elements != null ? elements : new ArrayList<>();
    }

    /** Used by the preset ArrayAdapter to render the spinner's collapsed + dropdown rows. */
    @Override
    public String toString() {
        return name;
    }
}
