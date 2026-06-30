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



    private static final double RECLASSIFICATION_THRESHOLD = 0.10;
    private static final String TAG = "CUAS_PLUGIN";
    private static final String DELIMITER = "|";

    private final Context pluginContext;
    private final MapGroup cuasGroup;
    private final MapView mv;

    private CotDetailHandler dewcCuasDetailHandler;
    private ReclassificationListener reclassificationListener;

    public void setReclassificationListener(ReclassificationListener l) {
        this.reclassificationListener = l;
    }

    public CuasCotProcessor(Context pluginContext, MapGroup cuasGroup, MapView mv) {
        this.pluginContext = pluginContext;
        this.cuasGroup = cuasGroup;
        this.mv = mv;
    }

    public void register() {
        CotDetailManager.getInstance().registerHandler(dewcCuasDetailHandler = new CotDetailHandler(CUAS_COT_FIlTER_TAG) {

            @Override
            public CommsMapComponent.ImportResult toItemMetadata(MapItem mapItem, CotEvent cotEvent, CotDetail cotDetail) {
                if (cotDetail.getAttribute(UAS_ITEM) != null) {
                    mapItem.setMetaString(UAS_ITEM, cotDetail.getAttribute(UAS_ITEM));

                    CotDetail classificationResultsDetail = cotDetail.getFirstChildByName(0, CLASSIFICATION_RESULTS_COT_TAG);
                    if (classificationResultsDetail != null) {
                        ArrayList<String> serialized = new ArrayList<>();
                        for (CotDetail resultDetail : classificationResultsDetail.getChildren()) {
                            serialized.add(resultDetail.getAttribute("threatLevel") + DELIMITER
                                    + resultDetail.getAttribute("classificationMedium") + DELIMITER
                                    + resultDetail.getAttribute("confidence") + DELIMITER
                                    + resultDetail.getAttribute("type2525") + DELIMITER
                                    + resultDetail.getAttribute("typeName"));
                        }
                        mapItem.setMetaStringArrayList(CLASSIFICATION_RESULTS, serialized);
                    }

                    String selected = cotDetail.getAttribute(SELECTED_CLASSIFICATION_RESULT);
                    if (selected != null && !selected.isEmpty()) {
                        mapItem.setMetaString(SELECTED_CLASSIFICATION_RESULT, selected);
                    }

                    Log.d(TAG, "IMPORTED CUAS ITEM TO ITEM METADATA");
                }
                return null;
            }

            @Override
            public boolean toCotDetail(MapItem mapItem, CotEvent cotEvent, CotDetail cotDetail) {
                if (mapItem.hasMetaValue(UAS_ITEM)) {
                    cotDetail.setAttribute(UAS_ITEM, mapItem.getMetaString(UAS_ITEM, ""));

                    ArrayList<String> classificationResults = mapItem.getMetaStringArrayList(CLASSIFICATION_RESULTS);
                    if (classificationResults != null && !classificationResults.isEmpty()) {
                        CotDetail classificationResultsDetail = new CotDetail(CLASSIFICATION_RESULTS_COT_TAG);
                        for (String entry : classificationResults) {
                            String[] parts = entry.split("\\" + DELIMITER, 5);
                            if (parts.length >= 3) {
                                CotDetail resultDetail = new CotDetail(CLASSIFICATION_RESULT_COT_TAG);
                                resultDetail.setAttribute("threatLevel", parts[0]);
                                resultDetail.setAttribute("classificationMedium", parts[1]);
                                resultDetail.setAttribute("confidence", parts[2]);
                                resultDetail.setAttribute("type2525", parts.length >= 4 ? parts[3] : "");
                                resultDetail.setAttribute("typeName", parts.length >= 5 ? parts[4] : "");
                                classificationResultsDetail.addChild(resultDetail);
                            }
                        }
                        cotDetail.addChild(classificationResultsDetail);
                    }

                    String selected = mapItem.getMetaString(SELECTED_CLASSIFICATION_RESULT, null);
                    if (selected != null) {
                        cotDetail.setAttribute(SELECTED_CLASSIFICATION_RESULT, selected);
                    }

                    Log.d(TAG, "Exported CUAS ITEM TO COT DETAIL");
                }
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

    public void ingestDrone(Effector dto) {
        if (cuasGroup == null) return;

        GeoPoint markerLocation     = new GeoPoint(dto.lat, dto.lon, dto.altitudeMeters);
        GeoPointMetaData markerGPM  = new GeoPointMetaData(markerLocation);

        // Serialize classification results once — used by both paths
        ArrayList<String> serialized = new ArrayList<>();
        if (dto.ClassificationResultList != null && !dto.ClassificationResultList.isEmpty()) {
            for (ClassificationResult r : dto.ClassificationResultList) {
                serialized.add(r.threatLevel      + DELIMITER
                        + r.classificationMedium  + DELIMITER
                        + r.confidence            + DELIMITER
                        + (r.type2525  != null ? r.type2525  : "") + DELIMITER
                        + (r.typeName  != null ? r.typeName  : ""));
            }
        }

        // ── UPDATE PATH ───────────────────────────────────────────────────────
        MapItem existing = cuasGroup.deepFindUID(dto.uid);
        if (existing instanceof PointMapItem) {
            ((PointMapItem) existing).setPoint(markerGPM);

            // Reclassification check — only for already-classified items
            if (existing.getMetaString(SELECTED_CLASSIFICATION_RESULT, null) != null
                    && !serialized.isEmpty()) {
                ArrayList<String> oldList = existing.getMetaStringArrayList(CLASSIFICATION_RESULTS);
                String triggerMedium = confidenceDeltaTrigger(oldList, serialized);
                if (triggerMedium != null) {
                    existing.setMetaString(SELECTED_CLASSIFICATION_RESULT, null);
                    existing.setType("a-P");
                    mv.getMapEventDispatcher().dispatch(
                            new MapEvent.Builder(MapEvent.ITEM_REFRESH).setItem(existing).build());
                    if (reclassificationListener != null)
                        reclassificationListener.onReclassificationRequired(
                                existing, "confidence shift >10% on " + triggerMedium);
                }
            }

            if (!serialized.isEmpty())
                existing.setMetaStringArrayList(CLASSIFICATION_RESULTS, serialized);

            // Update the ambiguity circle in-place
            MapItem ambiguity = cuasGroup.deepFindItem(LOCATION_AMBIGUITY_UID, dto.uid);
            if (ambiguity instanceof Circle) {
                ((Circle) ambiguity).setCenterPoint(markerGPM);
                ((Circle) ambiguity).setRadius(dto.locationAmbiguity);
            }
            return;
        }

        // ── CREATE PATH ───────────────────────────────────────────────────────
        // First time we see this UID — place in pending state.
        PlacePointTool.MarkerCreator markercreationtool = new PlacePointTool.MarkerCreator(markerLocation);
        markercreationtool.setHow("h-g-i-g-o");
        markercreationtool.setUid(dto.uid);
        markercreationtool.setType("a-P");
        markercreationtool.setShowFiveLine(true);
        markercreationtool.setCallsign(dto.callsign);

        MapItem marker = markercreationtool.placePoint();
        marker.setMetaString(UAS_ITEM, "true");
        if (!serialized.isEmpty())
            marker.setMetaStringArrayList(CLASSIFICATION_RESULTS, serialized);
        marker.setMetaString(SELECTED_CLASSIFICATION_RESULT, null);

        marker.addOnVisibleChangedListener(changedItem -> {
            MapItem ambiguity = cuasGroup.deepFindItem(LOCATION_AMBIGUITY_UID, dto.uid);
            if (ambiguity != null) ambiguity.setVisible(changedItem.getVisible());
        });
        cuasGroup.addItem(marker);

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
}
