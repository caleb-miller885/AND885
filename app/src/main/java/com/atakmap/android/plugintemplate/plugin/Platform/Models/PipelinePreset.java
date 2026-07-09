package com.atakmap.android.plugintemplate.plugin.Platform.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * A named, ordered set of {@link PipelineElement}s that can be loaded onto a platform.
 *
 * Deliberately shared between the UI (PlatformPipelinePane/PresetConfigPane) and the network
 * layer (Gson deserializes response bodies straight onto this class — see
 * PlatformStatusResponse.activePreset) instead of a separate wire-DTO + mapper. The cost: fields
 * are mutable (not final) and there's no constructor — relying on the implicit default one keeps
 * Gson on the normal instantiation path (constructors + field initializers all run) instead of
 * falling back to unsafe field-only allocation, which would skip the `= new ArrayList<>()`
 * defaults below and silently leave List fields null when the server omits them.
 */
public class PipelinePreset {

    public String uid;
    public String name;
    public List<PipelineElement> elements = new ArrayList<>();

    /** Used by the preset ArrayAdapter to render the spinner's collapsed + dropdown rows. */
    @Override
    public String toString() {
        return name;
    }
}
