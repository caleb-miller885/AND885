package com.atakmap.android.plugintemplate.plugin.Platform.Network;

import com.atakmap.android.plugintemplate.plugin.Platform.Models.PipelinePreset;
import com.google.gson.annotations.SerializedName;

/**
 * Response body for GET {endpoint}/status — the platform's own pipeline state (e.g. "ready",
 * "not ready", "paused"; exact value set TBD from the real onboard API), not connection state.
 * Connection state is never read from this body — PlatformEntry.connectionState is derived
 * purely from whether a response came back at all (see CUASPaneRegistry.connectEndpoint), so a
 * platform can be CONNECTED while its pipelineStatus is "not ready".
 *
 * activePreset reuses Platform.Models.PipelinePreset directly — the same class the UI/config
 * screens already use — rather than a separate status-only shape. See PipelinePreset's own doc
 * for what that costs (mutable fields, no-arg constructor).
 */
public class PlatformStatusResponse {

    @SerializedName("status")
    public String pipelineStatus;

    @SerializedName("active_preset")
    public PipelinePreset activePreset;

    @SerializedName("uptime_seconds")
    public long uptimeSeconds;
}
