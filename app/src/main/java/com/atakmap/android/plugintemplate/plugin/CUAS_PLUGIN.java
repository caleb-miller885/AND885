package com.atakmap.android.plugintemplate.plugin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.plugintemplate.plugin.Models.ClassificationResult;
import com.atakmap.android.plugintemplate.plugin.Models.Effector;
import com.atakmap.android.plugintemplate.plugin.Panes.CUASPaneRegistry;
import com.atakmap.android.plugintemplate.plugin.Services.CUASServiceRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class CUAS_PLUGIN implements IPlugin {

    private final IServiceController serviceController;
    private final Context pluginContext;
    private final IHostUIService uiService;
    private final MapView mv;
    private final ToolbarItem toolbarItem;

    private MapGroup cuasGroup;
    private CUASServiceRegistry services;
    private CUASPaneRegistry paneRegistry;

    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;
    private final Random rng = new Random();

    private final MapGroup.OnItemListChangedListener mapListener =
            new MapGroup.OnItemListChangedListener() {
        @Override
        public void onItemAdded(MapItem item, MapGroup group) {
            if (paneRegistry != null) paneRegistry.onItemAdded(item);
        }

        @Override
        public void onItemRemoved(MapItem item, MapGroup group) {
            if (paneRegistry != null) paneRegistry.onItemRemoved(item);

            //Remove the location ambiguity area that is assigned to the uAS
            String uid = item.getUID();
            MapItem ambiguityArea = cuasGroup.deepFindItem(Constants.LOCATION_AMBIGUITY_UID,uid);
            if(ambiguityArea!=null){
                ambiguityArea.removeFromGroup();
            }
        }
    };
    private final MapEventDispatcher.MapEventDispatchListener searchAreaAddedListener = event -> {
        MapItem item = event.getItem();
        if (item != null && item.hasMetaValue(Constants.SEARCH_AREA))
            if (paneRegistry != null){

                paneRegistry.checkActiveSearchAreas();
            }
    };

    private final MapEventDispatcher.MapEventDispatchListener searchAreaRemovedListener = event -> {
        MapItem item = event.getItem();
        if (item != null && item.hasMetaValue(Constants.SEARCH_AREA))
            if (paneRegistry != null) paneRegistry.checkActiveSearchAreas();
    };


    public CUAS_PLUGIN(IServiceController serviceController) {
        this.serviceController = serviceController;

        PluginContextProvider ctxProvider =
                serviceController.getService(PluginContextProvider.class);
        pluginContext = ctxProvider.getPluginContext();
        pluginContext.setTheme(R.style.ATAKPluginTheme);

        mv         = MapView.getMapView();
        uiService  = serviceController.getService(IHostUIService.class);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        if (paneRegistry != null) paneRegistry.showLandingPane();
                    }
                })
                .setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null) return;

        cuasGroup = mv.getRootGroup().findMapGroup(Constants.CUAS_GROUP_NAME);
        if (cuasGroup == null) {
            cuasGroup = mv.getRootGroup().addGroup(Constants.CUAS_GROUP_NAME);
        }
        cuasGroup.addOnItemListChangedListener(mapListener);
        mv.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_PERSIST,  searchAreaAddedListener);
        mv.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_REMOVED, searchAreaRemovedListener);


        services = new CUASServiceRegistry(pluginContext, cuasGroup, mv);
        services.onStart();

        paneRegistry = new CUASPaneRegistry(uiService, pluginContext, services);

        uiService.addToolbarItem(toolbarItem);

        injectDummyDrones();
    }

    @Override
    public void onStop() {
        if (uiService == null) return;

        mv.getMapEventDispatcher().removeMapEventListener(MapEvent.ITEM_ADDED,   searchAreaAddedListener);
        mv.getMapEventDispatcher().removeMapEventListener(MapEvent.ITEM_REMOVED, searchAreaRemovedListener);


        if (cuasGroup != null) {
            cuasGroup.removeOnItemListChangedListener(mapListener);
            cuasGroup = null;
        }

        if (paneRegistry != null) {
            paneRegistry.onStop();
            paneRegistry = null;
        }

        if (services != null) {
            services.onStop();
            services = null;
        }

        if (updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
            updateRunnable = null;
        }

        uiService.removeToolbarItem(toolbarItem);
    }

    // ── Dummy data ────────────────────────────────────────────────────────────

    // Starting positions — mutated each tick to simulate movement
    private final double[] lats = { 38.8977, 38.9010, 38.8940, 38.9050 };
    private final double[] lons = { -77.0365, -77.0410, -77.0290, -77.0200 };
    private final double[] alts = { 120, 95, 200, 150 };

    private void injectDummyDrones() {
        services.cotProcessor.ingestDrone(makeDrone(
                "DUMMY-001", "BANDIT-01", "a-p-A-M-F-Q-r",
                lats[0], lons[0], alts[0], 80, 45,
                Arrays.asList(
                        classResult(Constants.THREAT_HIGH,     "RF-ANALYSIS", 0.92, "a-s-A-M-F-Q-r", "DJI PHANTOM 4"),
                        classResult(Constants.THREAT_HIGH,     "ACOUSTIC",    0.78, "a-s-A-M-F-Q-r", "PARROT ANAFI"))));

        services.cotProcessor.ingestDrone(makeDrone(
                "DUMMY-002", "BANDIT-02", "a-p-A-M-F-Q-r",
                lats[1], lons[1], alts[1], 50, 180,
                Arrays.asList(
                        classResult(Constants.THREAT_CRITICAL, "RF-ANALYSIS", 0.97, "a-h-A-M-F-Q-r", "DJI MATRICE 300"),
                        classResult(Constants.THREAT_CRITICAL, "ML-DETECT",   0.88, "a-h-A-M-F-Q-r", "DJI MATRICE 300"),
                        classResult(Constants.THREAT_HIGH,     "VISUAL",      0.65, "a-s-A-M-F-Q-r", "AUTEL EVO II"))));

        services.cotProcessor.ingestDrone(makeDrone(
                "DUMMY-003", "BANDIT-03", "a-p-A-M-F-Q-r",
                lats[2], lons[2], alts[2], 120, 270,
                Arrays.asList(
                        classResult(Constants.THREAT_MINIMAL,  "ACOUSTIC",    0.85, "a-u-A-M-F-Q-r", "MAVIC MINI"))));

        services.cotProcessor.ingestDrone(makeDrone(
                "DUMMY-004", "BANDIT-04", "a-p-A-M-F-Q-r",
                lats[3], lons[3], alts[3], 60, 90,
                Arrays.asList(
                        classResult(Constants.THREAT_HIGH,     "ML-DETECT",   0.83, "a-s-A-M-F-Q-r", "DJI PHANTOM 4 PRO"),
                        classResult(Constants.THREAT_MINIMAL,  "ACOUSTIC",    0.22, "a-u-A-M-F-Q-r", "UNKNOWN SMALL"))));

        startDummyUpdates();
    }

    private void startDummyUpdates() {
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (services == null) return;

                // Nudge each drone ~5-10 m in a random direction
                for (int i = 0; i < 4; i++) {
                    lats[i] += (rng.nextDouble() - 0.5) * 0.0002;
                    lons[i] += (rng.nextDouble() - 0.5) * 0.0002;
                    alts[i] += (rng.nextDouble() - 0.5) * 5;
                }

                services.cotProcessor.ingestDrone(makeDrone(
                        "DUMMY-001", "BANDIT-01", null,
                        lats[0], lons[0], alts[0], 80, 45,
                        Arrays.asList(
                                classResult(Constants.THREAT_HIGH, "RF-ANALYSIS", 0.92, "a-f-A-M-F-Q-r", "DJI PHANTOM 4"),
                                classResult(Constants.THREAT_HIGH, "ACOUSTIC",    0.78, "a-f-A-M-F-Q-r", "PARROT ANAFI"))));

                services.cotProcessor.ingestDrone(makeDrone(
                        "DUMMY-002", "BANDIT-02", null,
                        lats[1], lons[1], alts[1], 50, 180,
                        Arrays.asList(
                                classResult(Constants.THREAT_CRITICAL, "RF-ANALYSIS", 0.97, "a-h-A-M-F-Q-r", "DJI MATRICE 300"),
                                classResult(Constants.THREAT_CRITICAL, "ML-DETECT",   0.68, "a-h-A-M-F-Q-r", "DJI MATRICE 300"),
                                classResult(Constants.THREAT_HIGH,     "VISUAL",      0.65, "a-s-A-M-F-Q-r", "AUTEL EVO II"))));

                services.cotProcessor.ingestDrone(makeDrone(
                        "DUMMY-003", "BANDIT-03", null,
                        lats[2], lons[2], alts[2], 120, 270,
                        Arrays.asList(
                                classResult(Constants.THREAT_MINIMAL, "ACOUSTIC", 0.55, "a-n-A-M-F-Q-r", "MAVIC MINI"))));

                services.cotProcessor.ingestDrone(makeDrone(
                        "DUMMY-004", "BANDIT-04", null,
                        lats[3], lons[3], alts[3], 60, 90,
                        Arrays.asList(
                                classResult(Constants.THREAT_HIGH,    "ML-DETECT", 0.83, "a-f-A-M-F-Q-r", "DJI PHANTOM 4 PRO"),
                                classResult(Constants.THREAT_MINIMAL, "ACOUSTIC",  0.42, "a-f-A-M-F-Q-r", "UNKNOWN SMALL"))));

                updateHandler.postDelayed(this, 5000);
            }
        };
        updateHandler.postDelayed(updateRunnable, 5000);
    }

    private static Effector makeDrone(String uid, String callsign, String cotType,
                                      double lat, double lon, double alt,
                                      double ambiguity, double heading,
                                      List<ClassificationResult> results) {
        Effector e = new Effector();
        e.uid                    = uid;
        e.callsign               = callsign;
        e.lat                    = lat;
        e.lon                    = lon;
        e.altitudeMeters         = alt;
        e.locationAmbiguity      = ambiguity;
        e.heading                = heading;
        e.ClassificationResultList = results;
        return e;
    }

    private static ClassificationResult classResult(String threatLevel, String medium,
                                                    double confidence, String type2525, String typeName) {
        ClassificationResult r = new ClassificationResult();
        r.threatLevel          = threatLevel;
        r.classificationMedium = medium;
        r.confidence           = confidence;
        r.type2525             = type2525;
        r.typeName             = typeName;
        return r;
    }
}
