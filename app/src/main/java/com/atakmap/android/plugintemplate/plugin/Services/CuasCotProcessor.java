package com.atakmap.android.plugintemplate.plugin.Services;

import static com.atakmap.android.plugintemplate.plugin.Constants.CLASSIFICATION_RESULT_COT_TAG;
import static com.atakmap.android.plugintemplate.plugin.Constants.CLASSIFICATION_RESULTS;
import static com.atakmap.android.plugintemplate.plugin.Constants.CLASSIFICATION_RESULTS_COT_TAG;
import static com.atakmap.android.plugintemplate.plugin.Constants.CUAS_COT_FIlTER_TAG;
import static com.atakmap.android.plugintemplate.plugin.Constants.LOCATION_AMBIGUITY_UID;
import static com.atakmap.android.plugintemplate.plugin.Constants.SELECTED_CLASSIFICATION_RESULT;
import static com.atakmap.android.plugintemplate.plugin.Constants.UAS_ITEM;
import static com.atakmap.android.plugintemplate.plugin.Constants.LOCATION_AMBIGUITY_AREA;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.Log;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.cot.detail.CotDetailHandler;
import com.atakmap.android.cot.detail.CotDetailManager;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.plugintemplate.plugin.Constants;
import com.atakmap.android.plugintemplate.plugin.Models.ClassificationResult;
import com.atakmap.android.plugintemplate.plugin.Models.Effector;
import com.atakmap.android.user.PlacePointTool;
import com.atakmap.android.util.Circle;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.comms.ReportingRate;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class CuasCotProcessor {

    public interface ReclassificationListener {
        void onReclassificationRequired(MapItem item, String reason);
    }

    public interface PendingItemListener {
        void onPendingItemUpdated(MapItem item);
    }

    private static final double RECLASSIFICATION_THRESHOLD = 0.10;
    private static final String TAG = "CUAS_PLUGIN";
    private static final String DELIMITER = "|";

    private final Context pluginContext;
    private final MapGroup cuasGroup;
    private final MapView mv;

    private CotDetailHandler dewcCuasDetailHandler;
    private ReclassificationListener reclassificationListener;
    private PendingItemListener pendingItemListener;

    public void setReclassificationListener(ReclassificationListener l) { this.reclassificationListener = l; }
    public void setPendingItemListener(PendingItemListener l)           { this.pendingItemListener = l; }

    public CuasCotProcessor(Context pluginContext, MapGroup cuasGroup, MapView mv) {
        this.pluginContext = pluginContext;
        this.cuasGroup = cuasGroup;
        this.mv = mv;
    }

    public void register() {
        CotDetailManager.getInstance().registerHandler(dewcCuasDetailHandler = new CotDetailHandler(CUAS_COT_FIlTER_TAG) {

            @Override
            public CommsMapComponent.ImportResult toItemMetadata(MapItem mapItem, CotEvent cotEvent, CotDetail cotDetail) {
                if (cotDetail.getAttribute(UAS_ITEM) == null)
                    return CommsMapComponent.ImportResult.SUCCESS;

                //Set type override so every item is forced set to pending
                mapItem.setType("a-p");
                mv.getMapEventDispatcher().dispatch(
                        new MapEvent.Builder(MapEvent.ITEM_REFRESH).setItem(mapItem).build());

                Effector dto = cotToEffector(mapItem, cotEvent, cotDetail);
                // Post so this runs after MarkerImporter's addToGroup completes.
                // ingestDrone finds the item via rootGroup.deepFindUID and updates it in place.
                mv.post(() -> ingestDrone(dto));
                return CommsMapComponent.ImportResult.SUCCESS;
            }

            @Override
            public boolean toCotDetail(MapItem mapItem, CotEvent cotEvent, CotDetail cotDetail) {
                if (!mapItem.hasMetaValue(UAS_ITEM)) return true;

                // All CUAS data goes inside the filter-tag wrapper so that
                // toItemMetadata receives it as the named detail element.
                CotDetail filterTag = new CotDetail(CUAS_COT_FIlTER_TAG);
                filterTag.setAttribute(UAS_ITEM, mapItem.getMetaString(UAS_ITEM, ""));

                ArrayList<String> classificationResults = mapItem.getMetaStringArrayList(CLASSIFICATION_RESULTS);
                if (classificationResults != null && !classificationResults.isEmpty()) {
                    CotDetail resultsWrapper = new CotDetail(CLASSIFICATION_RESULTS_COT_TAG);
                    for (String entry : classificationResults) {
                        String[] parts = entry.split("\\" + DELIMITER, 5);
                        if (parts.length >= 3) {
                            CotDetail r = new CotDetail(CLASSIFICATION_RESULT_COT_TAG);
                            r.setAttribute("threatLevel",          parts[0]);
                            r.setAttribute("classificationMedium", parts[1]);
                            r.setAttribute("confidence",           parts[2]);
                            r.setAttribute("type2525",  parts.length >= 4 ? parts[3] : "");
                            r.setAttribute("typeName",  parts.length >= 5 ? parts[4] : "");
                            resultsWrapper.addChild(r);
                        }
                    }
                    filterTag.addChild(resultsWrapper);
                }

                String selected = mapItem.getMetaString(SELECTED_CLASSIFICATION_RESULT, null);
                if (selected != null)
                    filterTag.setAttribute(SELECTED_CLASSIFICATION_RESULT, selected);

                cotDetail.addChild(filterTag);
                Log.d(TAG, "toCotDetail: exported CUAS detail for uid=" + mapItem.getUID());
                return true;
            }
        });
    }

    public void unregister() {
        CotDetailManager.getInstance().unregisterHandler(dewcCuasDetailHandler);
    }

    public void sendCotInternal(CotDetail detail) {
        dewcCuasDetailHandler.toItemMetadata(mv.getSelfMarker(), null, detail);
    }

    // ── Public ingestion API ──────────────────────────────────────────────────

    public void ingestDrone(Effector dto) {
        if (cuasGroup == null) return;

        GeoPoint markerLocation = new GeoPoint(dto.lat, dto.lon, dto.altitudeMeters);
        GeoPointMetaData markerGPM = new GeoPointMetaData(markerLocation);

        ArrayList<String> serialized = new ArrayList<>();
        if (dto.ClassificationResultList != null && !dto.ClassificationResultList.isEmpty()) {
            for (ClassificationResult r : dto.ClassificationResultList) {
                serialized.add(r.threatLevel      + DELIMITER
                        + r.classificationMedium  + DELIMITER
                        + r.confidence            + DELIMITER
                        + (r.type2525 != null ? r.type2525 : "") + DELIMITER
                        + (r.typeName != null ? r.typeName : ""));
            }
        }

        // Search the all map groups for existing drone with UID
        MapItem existing = mv.getRootGroup().deepFindUID(dto.uid);

        // ── UPDATE PATH ───────────────────────────────────────────────────────
        Log.d(TAG, "ingestDrone uid=" + dto.uid + " existing=" + (existing != null));
        if (existing instanceof PointMapItem && existing.hasMetaValue(UAS_ITEM)) {
            ((PointMapItem) existing).setPoint(markerGPM);
            if (existing instanceof Marker)
                ((Marker) existing).setTrack(dto.heading, 0.0);

            // ATAK resets the marker type from the COT XML on every update — restore classified type.
            String selectedType = existing.getMetaString(SELECTED_CLASSIFICATION_RESULT, null);
            if (selectedType != null) {
                String[] typeParts = selectedType.split("\\|", 5);
                if (typeParts.length >= 4 && !typeParts[3].isEmpty())
                    existing.setType(typeParts[3]);
                MapView mv = MapView.getMapView();
                if (mv != null)
                    mv.getMapEventDispatcher().dispatch(
                            new MapEvent.Builder(MapEvent.ITEM_REFRESH).setItem(existing).build());


        }
            //Check to see if incoming classification percentages have shifted by the reclassification threshold
            //Reset back to unclassified and trigger UI to remove from list of active drones
            if (existing.getMetaString(SELECTED_CLASSIFICATION_RESULT, null) != null
                    && !serialized.isEmpty()) {
                ArrayList<String> oldList = existing.getMetaStringArrayList(CLASSIFICATION_RESULTS);
                String triggerMedium = confidenceDeltaTrigger(oldList, serialized);
                if (triggerMedium != null) {
                    existing.setMetaString(SELECTED_CLASSIFICATION_RESULT, null);
                    existing.setType("a-p");
                    mv.getMapEventDispatcher().dispatch(
                            new MapEvent.Builder(MapEvent.ITEM_REFRESH).setItem(existing).build());
                    if (reclassificationListener != null)
                        reclassificationListener.onReclassificationRequired(
                                existing, "confidence shift >10% on " + triggerMedium);
                }
            }

            //If item not classified move to pending
            if (!serialized.isEmpty()) {
                existing.setMetaStringArrayList(CLASSIFICATION_RESULTS, serialized);
                if (existing.getMetaString(SELECTED_CLASSIFICATION_RESULT, null) == null
                        && pendingItemListener != null) {
                    pendingItemListener.onPendingItemUpdated(existing);
                }
            }

            //Update location ambiguity circle
            MapItem ambiguity = cuasGroup.deepFindItem(LOCATION_AMBIGUITY_UID, dto.uid);
            if (ambiguity instanceof Circle) {
                ((Circle) ambiguity).setCenterPoint(markerGPM);
                ((Circle) ambiguity).setRadius(dto.locationAmbiguity);
            }
            return;
        }

        // ── CREATE / INIT PATH ────────────────────────────────────────────────
        // COT path: existing is ATAK's freshly-placed marker (no UAS_ITEM yet), use it as-is.
        // DTO path: existing is null, create via PlacePointTool (adds to ATAK's group).
        Log.d(TAG, "ingestDrone CREATE uid=" + dto.uid);
        MapItem marker;
        if (existing != null) {
            marker = existing;
        } else {
            PlacePointTool.MarkerCreator mc = new PlacePointTool.MarkerCreator(markerLocation);
            mc.setHow("m-g");
            mc.setType("a-p");
            mc.setUid(dto.uid);
            mc.setShowFiveLine(true);
            mc.setCallsign(dto.callsign);
            marker = mc.placePoint();
        }

        marker.setMetaString(UAS_ITEM, "true");
        if (!serialized.isEmpty())
            marker.setMetaStringArrayList(CLASSIFICATION_RESULTS, serialized);
        marker.setMetaString(SELECTED_CLASSIFICATION_RESULT, null);
        if (marker instanceof Marker)
            ((Marker) marker).setTrack(dto.heading, 0.0);
        marker.addOnVisibleChangedListener(changedItem -> {
            MapItem ambiguity = cuasGroup.deepFindItem(LOCATION_AMBIGUITY_UID, dto.uid);
            if (ambiguity != null) ambiguity.setVisible(changedItem.getVisible());
        });

        // Dispatch ITEM_ADDED first so droneAddedListener initializes the landing pane
        // (via onItemAdded → getLandingPane()), then notify pending so the landing pane
        // already exists when onPendingItemUpdated checks landingPane != null.
        mv.getMapEventDispatcher().dispatch(
                new MapEvent.Builder(MapEvent.ITEM_ADDED).setItem(marker).build());

        if (!serialized.isEmpty() && pendingItemListener != null)
            pendingItemListener.onPendingItemUpdated(marker);

        Circle locationAmbiguityShape = new Circle(markerGPM, dto.locationAmbiguity);
        locationAmbiguityShape.setMetaString(LOCATION_AMBIGUITY_AREA, "true");
        locationAmbiguityShape.setMetaString(LOCATION_AMBIGUITY_UID, dto.uid);
        locationAmbiguityShape.setTitle(dto.callsign + " : Ambiguity");
        locationAmbiguityShape.setMetaString("callsign", dto.callsign + " : Ambiguity");
        locationAmbiguityShape.setMetaString("strokeStyle", "solid");
        locationAmbiguityShape.setClickable(false);
        locationAmbiguityShape.setMovable(false);
        locationAmbiguityShape.setColor(Color.BLUE);
        locationAmbiguityShape.setFillColor(Color.BLUE);
        locationAmbiguityShape.setFillAlpha(30);
        locationAmbiguityShape.setStrokeStyle(3);
        locationAmbiguityShape.setStrokeWeight(3);
        cuasGroup.addItem(locationAmbiguityShape);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────


    //Maps incoming COT to effector DTO such that it can be used in ingestDrone
    private Effector cotToEffector(MapItem mapItem, CotEvent cotEvent, CotDetail cotDetail) {
        Effector dto = new Effector();
        dto.uid      = mapItem.getUID();
        dto.callsign = mapItem.getTitle() != null ? mapItem.getTitle() : dto.uid;

        if (cotEvent != null && cotEvent.getCotPoint() != null) {
            dto.lat               = cotEvent.getCotPoint().getLat();
            dto.lon               = cotEvent.getCotPoint().getLon();
            dto.altitudeMeters    = cotEvent.getCotPoint().getHae();
            dto.locationAmbiguity = cotEvent.getCotPoint().getCe();
        }

        dto.heading = 0.0;
        if (cotEvent != null) {
            CotDetail track = cotEvent.findDetail("track");
            if (track != null) {
                String course = track.getAttribute("course");
                if (course != null) {
                    try { dto.heading = Double.parseDouble(course); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }

        dto.ClassificationResultList = new ArrayList<>();
        CotDetail resultsDetail = cotDetail.getFirstChildByName(0, CLASSIFICATION_RESULTS_COT_TAG);
        if (resultsDetail != null) {
            for (CotDetail r : resultsDetail.getChildren()) {
                ClassificationResult cr = new ClassificationResult();
                cr.threatLevel          = r.getAttribute("threatLevel");
                cr.classificationMedium = r.getAttribute("classificationMedium");
                cr.type2525             = r.getAttribute("type2525");
                cr.typeName             = r.getAttribute("typeName");
                try { cr.confidence = Double.parseDouble(r.getAttribute("confidence")); }
                catch (Exception ignored) { cr.confidence = 0.0; }
                dto.ClassificationResultList.add(cr);
            }
        }
        return dto;
    }

    private String confidenceDeltaTrigger(ArrayList<String> oldList, ArrayList<String> newList) {
        if (oldList == null || oldList.isEmpty()) return null;
        Map<String, Double> oldConf = new HashMap<>();
        for (String s : oldList) {
            String[] p = s.split("\\|", 5);
            if (p.length >= 3) {
                try { oldConf.put(p[1], Double.parseDouble(p[2])); }
                catch (NumberFormatException ignored) {}
            }
        }
        for (String s : newList) {
            String[] p = s.split("\\|", 5);
            if (p.length < 3) continue;
            Double prev = oldConf.get(p[1]);
            if (prev == null) continue;
            try {
                if (Math.abs(Double.parseDouble(p[2]) - prev) >= RECLASSIFICATION_THRESHOLD)
                    return p[1];
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public void attachDetailtoSA(CotDetail detail) {
        CotMapComponent.getInstance().addAdditionalDetail(detail.getElementName(), detail);
        AtakBroadcast.getInstance().sendBroadcast(new Intent(ReportingRate.REPORT_LOCATION).putExtra("reason", "detail update"));
    }
}
