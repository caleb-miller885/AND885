package com.atakmap.android.plugintemplate.plugin.Platform;

import android.view.View;

import com.atakmap.android.plugintemplate.plugin.Platform.Models.PipelinePreset;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the landing page and the pipeline/preset panes need for one platform. Meant to be
 * the value type in the host's pane registry, keyed by platform UID in a
 * {@code Map<String, PlatformEntry>} — mirroring how CUAS's own LandingPane keys its per-item
 * state (see LandingPane.UasEntry) by MapItem UID, with add/update/remove driven from the
 * landing page's item-added / item-removed callbacks.
 *
 * mapItem is intentionally untyped (Object) since this module doesn't depend on the host's
 * platform/MapItem class — cast it back on the host side.
 */
public class PlatformEntry {

    public enum ConnectionState { CONNECTED, CONNECTING, DISCONNECTED }

    public final String uid;

    /** The platform's backing MapItem (or whatever identity object the host uses). */
    public Object mapItem;

    /** The row view bound to this platform on the landing page, if the host keeps one per entry. */
    public View listView;

    public String name;
    public String endpoint;
    public ConnectionState connectionState = ConnectionState.DISCONNECTED;

    public PipelinePreset activePreset;
    public List<PipelinePreset> availablePresets = new ArrayList<>();

    public PlatformEntry(String uid) {
        this.uid = uid;
    }
}
