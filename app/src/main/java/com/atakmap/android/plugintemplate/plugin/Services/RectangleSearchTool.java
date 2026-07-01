package com.atakmap.android.plugintemplate.plugin.Services;

import static com.atakmap.android.plugintemplate.plugin.Constants.RECTANGLE_SEARCH_TOOL_CALLBACK;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;

import com.atakmap.android.editableShapes.Rectangle;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.plugintemplate.plugin.Constants;
import com.atakmap.android.plugintemplate.plugin.HelperFunctions;

import java.util.List;

public class RectangleSearchTool extends BroadcastReceiver {

    public void start() {
        Intent intent = new Intent("com.atakmap.android.maps.toolbar.BEGIN_TOOL");
        intent.putExtra("tool", "org.android.maps.drawing.tools.RectangleCreationTool");
        intent.putExtra("drawingGroup", "Drawing Objects");
        intent.putExtra("callback", new Intent(RECTANGLE_SEARCH_TOOL_CALLBACK));
        AtakBroadcast.getInstance().sendBroadcast(intent);
    }

    // Removes the active area from the map — the group watcher fires and handles the UI.
    public void clearActiveArea() {
        MapGroup root = MapView.getMapView().getRootGroup();
        List<MapItem> searchAreas = root.deepFindItems(Constants.SEARCH_AREA,"true");
        for(MapItem searchArea: searchAreas){
            searchArea.removeFromGroup();
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!RECTANGLE_SEARCH_TOOL_CALLBACK.equals(intent.getAction())) return;

        String uid = intent.getStringExtra("uid");
        if (uid == null || uid.isEmpty()) return; // user cancelled

        MapView mv = MapView.getMapView();
        if (mv == null) return;

        MapItem item = mv.getRootGroup().deepFindUID(uid);
        if (!(item instanceof Rectangle)) return;

        Rectangle rect = (Rectangle) item;
        rect.setTitle("Search Area");
        rect.setMetaString("callsign", "Search Area");
        rect.setStrokeColor(Color.MAGENTA);
        rect.setStrokeWeight(4);
        rect.setColor(Color.MAGENTA);
        rect.setFillAlpha(30);
        rect.setStyle(Shape.BASIC_LINE_STYLE_OUTLINED);
        rect.setStrokeStyle(1);
        rect.setMetaString(Constants.SEARCH_AREA, "true");
        rect.persist(mv.getMapEventDispatcher(), null, getClass());
        HelperFunctions.addedMapItem(rect);

    }

}
