package com.atakmap.android.plugintemplate.plugin.Panes;

import com.atakmap.android.maps.MapItem;

public interface ClassificationSelectionListener {
    void onClassificationSelected(MapItem item, String serializedResult);
}
