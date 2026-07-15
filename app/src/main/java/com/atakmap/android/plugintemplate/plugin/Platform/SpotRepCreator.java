package com.atakmap.android.plugintemplate.plugin.Platform;

import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.user.PlacePointTool;
import com.atakmap.android.util.AttachmentManager;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.io.File;
import java.util.List;
import java.util.UUID;

public class SpotRepCreator {


    public void createSpotRep(String name, GeoPoint point, List<File> pictures) {
        String uid = UUID.randomUUID().toString();

        PlacePointTool.MarkerCreator mc = new PlacePointTool.MarkerCreator(point);
        mc.setUid(uid);
        mc.setType("b-i-x-i");
        mc.setCallsign(name);
        mc.showCotDetails(false);
        mc.setMetaString("isSpotRep", "true");
        MapItem marker = mc.placePoint();

        if (pictures != null) {
            for (File picture : pictures) {
                if (picture != null && picture.exists())
                    AttachmentManager.addAttachment(marker, picture);
            }
        }
    }
}
