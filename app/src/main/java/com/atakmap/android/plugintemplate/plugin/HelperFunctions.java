package com.atakmap.android.plugintemplate.plugin;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.Log;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.android.maps.MapView;

import java.util.List;

public class HelperFunctions {

    public static void runOnMainThread(Runnable action) {
        MapView mv = MapView.getMapView();
        if (mv != null) {
            mv.post(action);
        }
    }


    public static void refreshOverlayManager() {
        AtakBroadcast.getInstance().sendBroadcast(
                new Intent("com.atakmap.android.maps.REFRESH_HIERARCHY"));
    }

    public static void refreshMapItem(MapItem item){
        MapView mv = MapView.getMapView();
        if(mv!=null){
            mv.getMapEventDispatcher().dispatch(
                    new MapEvent.Builder(MapEvent.ITEM_REFRESH).setItem(item).build());
        }
    }
    public static void addedMapItem(MapItem item){
        MapView mv = MapView.getMapView();
        if(mv!=null){
            mv.getMapEventDispatcher().dispatch(
                    new MapEvent.Builder(MapEvent.ITEM_ADDED).setItem(item).build());
        }
    }

    public static int threatColor(String threatLevel) {
        if (Constants.THREAT_CRITICAL.equals(threatLevel)) return Color.parseColor("#F44336");
        if (Constants.THREAT_HIGH.equals(threatLevel))     return Color.parseColor("#FFC107");
        return Color.parseColor("#4CAF50");
    }

}
