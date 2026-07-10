package com.atakmap.android.plugintemplate.plugin.Models;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.atakmap.android.plugintemplate.plugin.Platform.Models.ElementOption;
import com.atakmap.android.plugintemplate.plugin.Platform.Models.PipelineElement;
import com.atakmap.android.plugintemplate.plugin.Platform.Models.PipelinePreset;

/**
 * Stand-in data source for the Platform package's panes while the real platform API isn't wired
 * up yet. CUAS-local demo fixture only — not part of the Platform package, and not meant to be
 * copied over when Platform/ is transferred into the host plugin.
 */
public class MockPipelineData {

    public static List<PipelinePreset> buildSamplePresets() {
        List<PipelinePreset> presets = new ArrayList<>();
        presets.add(buildPreset("preset-balanced", "BALANCED",
                "yolov8-m", "resnet50-cls", "kalman-std"));
        presets.add(buildPreset("preset-recall", "HIGH RECALL",
                "yolov8-l", "efficientnet-b4", "kalman-wide"));
        presets.add(buildPreset("preset-latency", "LOW LATENCY",
                "yolov8-n", "mobilenet-v3", "alpha-beta"));
        return presets;
    }

    private static PipelinePreset buildPreset(String uid, String name,
            String detectorModel, String classifierModel, String trackerModel) {
        List<PipelineElement> elements = new ArrayList<>();

        elements.add(element("DETECTOR", detectorModel, Arrays.asList(
                option("MODEL", Arrays.asList("yolov8-n", "yolov8-m", "yolov8-l"),
                        indexOf(detectorModel, "yolov8-n", "yolov8-m", "yolov8-l")),
                option("RESOLUTION", Arrays.asList("640", "960", "1280"), 1))));

        elements.add(element("CLASSIFIER", classifierModel, Arrays.asList(
                option("MODEL", Arrays.asList("mobilenet-v3", "resnet50-cls", "efficientnet-b4"),
                        indexOf(classifierModel, "mobilenet-v3", "resnet50-cls", "efficientnet-b4")),
                option("CONFIDENCE", Arrays.asList("LOW", "MEDIUM", "HIGH"), 1))));

        elements.add(element("TRACKER", trackerModel, Arrays.asList(
                option("MODEL", Arrays.asList("alpha-beta", "kalman-std", "kalman-wide"),
                        indexOf(trackerModel, "alpha-beta", "kalman-std", "kalman-wide")))));

        elements.add(element("GEOLOCATOR", "triangulation-v2", Arrays.asList(
                option("MODEL", Arrays.asList("triangulation-v1", "triangulation-v2"), 1))));

        PipelinePreset preset = new PipelinePreset();
        preset.uid = uid;
        preset.name = name;
        preset.elements = elements;
        return preset;
    }

    private static PipelineElement element(String name, String activeModel, List<ElementOption> options) {
        PipelineElement el = new PipelineElement();
        el.name = name;
        el.activeModel = activeModel;
        el.options = options;
        return el;
    }

    private static ElementOption option(String label, List<String> choices, int selectedIndex) {
        ElementOption opt = new ElementOption();
        opt.label = label;
        opt.options = choices;
        opt.selectedIndex = selectedIndex;
        return opt;
    }

    private static int indexOf(String value, String... choices) {
        for (int i = 0; i < choices.length; i++)
            if (choices[i].equals(value)) return i;
        return 0;
    }
}
