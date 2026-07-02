package com.atakmap.android.plugintemplate.plugin.Services;

import android.content.Context;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.plugintemplate.plugin.Constants;

public class CUASServiceRegistry {

    public final CuasCotProcessor cotProcessor;
    public final RectangleSearchTool rectangleSearchTool;

    public CUASServiceRegistry(Context pluginContext, MapGroup cuasGroup, MapView mv) {
        cotProcessor        = new CuasCotProcessor(pluginContext, cuasGroup, mv);
        rectangleSearchTool = new RectangleSearchTool();
    }

    public void onStart() {
        cotProcessor.register();

        AtakBroadcast.getInstance().registerReceiver(
                rectangleSearchTool,
                new AtakBroadcast.DocumentedIntentFilter(Constants.RECTANGLE_SEARCH_TOOL_CALLBACK));
    }

    public void onStop() {
        cotProcessor.unregister();
        AtakBroadcast.getInstance().unregisterReceiver(rectangleSearchTool);
    }
}
