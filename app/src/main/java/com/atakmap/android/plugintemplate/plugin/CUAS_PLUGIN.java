package com.atakmap.android.plugintemplate.plugin;

import android.content.Context;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.plugintemplate.plugin.Panes.CUASPaneRegistry;
import com.atakmap.android.plugintemplate.plugin.Services.CUASServiceRegistry;

import java.util.List;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

public class CUAS_PLUGIN implements IPlugin {

    private static final String TAG = "CUAS_PLUGIN";

    private final IServiceController serviceController;
    private final Context pluginContext;
    private final IHostUIService uiService;
    private final MapView mv;
    private final ToolbarItem toolbarItem;

    private MapGroup cuasGroup;
    private CUASServiceRegistry services;
    private CUASPaneRegistry paneRegistry;
    private DroneSim droneSim;

    private final MapEventDispatcher.MapEventDispatchListener droneAddedListener = event -> {
        MapItem item = event.getItem();
        if (item == null || !item.hasMetaValue(Constants.UAS_ITEM)) return;
        if (paneRegistry != null) paneRegistry.onEffectorAdded(item);
    };

    private final MapEventDispatcher.MapEventDispatchListener droneRemovedListener = event -> {
        MapItem item = event.getItem();
        if (item == null || !item.hasMetaValue(Constants.UAS_ITEM)) return;
        if (paneRegistry != null) paneRegistry.onEffectorRemoved(item);
        if (cuasGroup != null) {
            MapItem ambiguity = cuasGroup.deepFindItem(
                    Constants.LOCATION_AMBIGUITY_UID, item.getUID());
            if (ambiguity != null) ambiguity.removeFromGroup();
        }
    };

    private final MapEventDispatcher.MapEventDispatchListener sensorAddedListener = event -> {
        MapItem item = event.getItem();
        if (item == null || !item.hasMetaValue(Constants.SENSOR_ITEM)) return;
        if (paneRegistry != null) paneRegistry.onSensorAdded(item);
    };

    private final MapEventDispatcher.MapEventDispatchListener sensorRemovedListener = event -> {
        MapItem item = event.getItem();
        if (item == null || !item.hasMetaValue(Constants.SENSOR_ITEM)) return;
        if (paneRegistry != null) paneRegistry.onSensorRemoved(item);
    };

    private final MapEventDispatcher.MapEventDispatchListener searchAreaAddedListener = event -> {
        MapItem item = event.getItem();
        if (item != null && item.hasMetaValue(Constants.SEARCH_AREA) && paneRegistry != null)
            paneRegistry.checkActiveSearchAreas();
    };

    private final MapEventDispatcher.MapEventDispatchListener searchAreaRemovedListener = event -> {
        MapItem item = event.getItem();
        if (item != null && item.hasMetaValue(Constants.SEARCH_AREA) && paneRegistry != null)
            paneRegistry.checkActiveSearchAreas();
    };

    public CUAS_PLUGIN(IServiceController serviceController) {
        this.serviceController = serviceController;

        PluginContextProvider ctxProvider =
                serviceController.getService(PluginContextProvider.class);
        pluginContext = ctxProvider.getPluginContext();
        pluginContext.setTheme(R.style.ATAKPluginTheme);

        mv        = MapView.getMapView();
        uiService = serviceController.getService(IHostUIService.class);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_cuas),
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

        // cuasGroup holds only ambiguity circles — drone markers stay in ATAK's groups.
        cuasGroup = mv.getRootGroup().findMapGroup(Constants.CUAS_GROUP_NAME);
        if (cuasGroup == null)
            cuasGroup = mv.getRootGroup().addGroup(Constants.CUAS_GROUP_NAME);

        mv.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_ADDED,   droneAddedListener);
        mv.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_REMOVED, droneRemovedListener);
        mv.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_ADDED,   sensorAddedListener);
        mv.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_REMOVED, sensorRemovedListener);
        mv.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_ADDED,  searchAreaAddedListener);
        mv.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_REMOVED, searchAreaRemovedListener);

        services     = new CUASServiceRegistry(pluginContext, cuasGroup, mv);
        services.onStart();
        paneRegistry = new CUASPaneRegistry(uiService, pluginContext, services);

        uiService.addToolbarItem(toolbarItem);

        droneSim = new DroneSim(mv, services.cotProcessor);
        droneSim.start();
    }

    @Override
    public void onStop() {
        if (uiService == null) return;

        if (droneSim != null) { droneSim.stop(); droneSim = null; }

        if (services != null) { services.onStop(); services = null; }

        // Remove listeners before cleanup so item removals don't fire notifications.
        mv.getMapEventDispatcher().removeMapEventListener(MapEvent.ITEM_ADDED,   droneAddedListener);
        mv.getMapEventDispatcher().removeMapEventListener(MapEvent.ITEM_REMOVED, droneRemovedListener);
        mv.getMapEventDispatcher().removeMapEventListener(MapEvent.ITEM_ADDED,   sensorAddedListener);
        mv.getMapEventDispatcher().removeMapEventListener(MapEvent.ITEM_REMOVED, sensorRemovedListener);
        mv.getMapEventDispatcher().removeMapEventListener(MapEvent.ITEM_ADDED,  searchAreaAddedListener);
        mv.getMapEventDispatcher().removeMapEventListener(MapEvent.ITEM_REMOVED, searchAreaRemovedListener);

        List<MapItem> drones = mv.getRootGroup().deepFindItems(Constants.UAS_ITEM, "true");
        for (MapItem item : drones) item.removeFromGroup();

        List<MapItem> sensors = mv.getRootGroup().deepFindItems(Constants.SENSOR_ITEM, "true");
        for (MapItem item : sensors) item.removeFromGroup();

        // Ambiguity circles live in cuasGroup.
        if (cuasGroup != null) {
            List<MapItem> circles = cuasGroup.deepFindItems(Constants.LOCATION_AMBIGUITY_AREA, "true");
            for (MapItem c : circles) c.removeFromGroup();
            cuasGroup = null;
        }

        if (paneRegistry != null) { paneRegistry.onStop(); paneRegistry = null; }

        uiService.removeToolbarItem(toolbarItem);
    }
}
