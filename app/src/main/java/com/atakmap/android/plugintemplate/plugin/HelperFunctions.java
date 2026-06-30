package com.atakmap.android.plugintemplate.plugin;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;

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

    /**
     * Returns the traffic-light colour int for a given CUAS threat level.
     * Red = CRITICAL, amber = HIGH, green = MINIMAL (default).
     */
    public static int threatColor(String threatLevel) {
        if (Constants.THREAT_CRITICAL.equals(threatLevel)) return Color.parseColor("#F44336");
        if (Constants.THREAT_HIGH.equals(threatLevel))     return Color.parseColor("#FFC107");
        return Color.parseColor("#4CAF50");
    }

}
