package com.atakmap.android.plugintemplate.plugin;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.plugintemplate.plugin.Models.ClassificationResult;
import com.atakmap.android.plugintemplate.plugin.Models.Effector;
import com.atakmap.android.plugintemplate.plugin.Models.Sensor;
import com.atakmap.android.plugintemplate.plugin.Services.CuasCotProcessor;
import com.atakmap.comms.CotDispatcher;
import com.atakmap.coremap.cot.event.CotEvent;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Simulates 4 drones and 4 sensors for development testing.
 *
 * Drones  — COT-TEST-001/002 via internal COT dispatch;
 *           DTO-TEST-003/004 via direct Effector DTO injection.
 * Sensors — COT-SENSOR-001/002 via internal COT dispatch;
 *           DTO-SENSOR-003/004 via direct Sensor DTO injection.
 *
 * Sensors are stationary; drones drift each update tick.
 */
public class DroneSim {

    private static final String TAG = "DroneSim";
    private static final long UPDATE_INTERVAL_MS = 5000L;

    private static final SimpleDateFormat COT_TIME_FMT;
    static {
        COT_TIME_FMT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        COT_TIME_FMT.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    }

    private final MapView mv;
    private final CuasCotProcessor cotProcessor;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rng = new Random();
    private Runnable updateRunnable;
    private int tick = 0;

    // Drone positions — drifted each tick
    private final double[] dLats = { 38.8977, 38.9010, 38.8920, 38.9055 };
    private final double[] dLons = { -77.0365, -77.0410, -77.0480, -77.0300 };
    private final double[] dAlts = { 120.0, 95.0, 80.0, 140.0 };

    // Sensor positions — fixed
    private static final double[] sLats = { 38.8940, 38.9030, 38.8900, 38.9070 };
    private static final double[] sLons = { -77.0500, -77.0250, -77.0350, -77.0440 };
    private static final double[] sAlts = { 15.0, 12.0, 20.0, 18.0 };

    public DroneSim(MapView mv, CuasCotProcessor cotProcessor) {
        this.mv           = mv;
        this.cotProcessor = cotProcessor;
    }

    public void start() {
        dispatchInitial();
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                tick++;
                driftDrones();
                dispatchUpdates();
                handler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };
        handler.postDelayed(updateRunnable, UPDATE_INTERVAL_MS);
    }

    public void stop() {
        if (updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
            updateRunnable = null;
        }
    }

    // ── Initial creation ──────────────────────────────────────────────────────

    private void dispatchInitial() {
        // Drones via COT
        dispatchCot(buildDroneCotXml(
                "COT-TEST-001", "BANDIT-01",
                dLats[0], dLons[0], dAlts[0], 80.0, 45.0,
                cotResults("HIGH",     "RF-ANALYSIS", 0.92, "a-f-A-M-F-Q-r", "DJI PHANTOM 4",
                           "HIGH",     "ACOUSTIC",    0.78, "a-f-A-M-F-Q-r", "PARROT ANAFI")));

        dispatchCot(buildDroneCotXml(
                "COT-TEST-002", "BANDIT-02",
                dLats[1], dLons[1], dAlts[1], 50.0, 180.0,
                cotResults("CRITICAL", "RF-ANALYSIS", 0.97, "a-h-A-M-F-Q-r", "DJI MATRICE 300",
                           "CRITICAL", "ML-DETECT",   0.88, "a-h-A-M-F-Q-r", "DJI MATRICE 300")));

//        // Drones via DTO
//        cotProcessor.ingestEffector(buildDroneDto(
//                "DTO-TEST-003", "GHOST-01",
//                dLats[2], dLons[2], dAlts[2], 270.0, 60.0,
//                result("MINIMAL", "ACOUSTIC",  0.65, "a-n-A-M-F-Q-r", "MAVIC MINI"),
//                result("MINIMAL", "ML-DETECT", 0.55, "a-n-A-M-F-Q-r", "DJI MINI 2")));
//
//        cotProcessor.ingestEffector(buildDroneDto(
//                "DTO-TEST-004", "GHOST-02",
//                dLats[3], dLons[3], dAlts[3], 90.0, 120.0,
//                result("HIGH", "RF-ANALYSIS", 0.83, "a-f-A-M-F-Q-r", "AUTEL EVO II"),
//                result("HIGH", "ML-DETECT",   0.76, "a-n-A-M-F-Q-r", "AUTEL EVO II")));

        // Sensors via COT
        dispatchCot(buildSensorCotXml(
                "COT-SENSOR-001", "SENTINEL-01", "a-f-G-E-S",
                sLats[0], sLons[0], sAlts[0], 90.0, 120.0, 2000.0));

        dispatchCot(buildSensorCotXml(
                "COT-SENSOR-002", "SENTINEL-02", "a-f-G-E-S",
                sLats[1], sLons[1], sAlts[1], 270.0, 180.0, 800.0));

//        // Sensors via DTO
//        cotProcessor.ingestSensor(buildSensorDto(
//                "DTO-SENSOR-003", "RADAR-01", "a-f-G-E-C-F",
//                sLats[2], sLons[2], sAlts[2], 0.0, 340, 1500.0));
//
//        cotProcessor.ingestSensor(buildSensorDto(
//                "DTO-SENSOR-004", "OPTIC-01", "a-f-G-E-S",
//                sLats[3], sLons[3], sAlts[3], 45.0, 60.0, 1500.0));
    }

    // ── Periodic updates ──────────────────────────────────────────────────────

    private void dispatchUpdates() {
        boolean shifted = (tick / 5) % 2 == 1;

        // Drone updates (position drifts, confidence shifts)
        dispatchCot(buildDroneCotXml(
                "COT-TEST-001", "BANDIT-01",
                dLats[0], dLons[0], dAlts[0], 80.0, 45.0,
                cotResults("HIGH",     "RF-ANALYSIS",  0.92, "a-f-A-M-F-Q-r", "DJI PHANTOM 4",
                           "HIGH",     "ACOUSTIC",    0.78,                   "a-f-A-M-F-Q-r", "PARROT ANAFI")));

        dispatchCot(buildDroneCotXml(
                "COT-TEST-002", "BANDIT-02",
                dLats[1], dLons[1], dAlts[1], 50.0, 180.0,
                cotResults("CRITICAL", "RF-ANALYSIS", 0.97,                  "a-h-A-M-F-Q-r", "DJI MATRICE 300",
                           "CRITICAL", "ML-DETECT",   0.88, "a-h-A-M-F-Q-r", "DJI MATRICE 300")));

//        cotProcessor.ingestEffector(buildDroneDto(
//                "DTO-TEST-003", "GHOST-01",
//                dLats[2], dLons[2], dAlts[2], 270.0, 60.0,
//                result("MINIMAL", "ACOUSTIC",   0.65, "a-n-A-M-F-Q-r", "MAVIC MINI"),
//                result("MINIMAL", "ML-DETECT", 0.55,                  "a-n-A-M-F-Q-r", "DJI MINI 2")));
//
//        cotProcessor.ingestEffector(buildDroneDto(
//                "DTO-TEST-004", "GHOST-02",
//                dLats[3], dLons[3], dAlts[3], 90.0, 120.0,
//                result("HIGH", "RF-ANALYSIS", 0.83,                  "a-f-A-M-F-Q-r", "AUTEL EVO II"),
//                result("HIGH", "ML-DETECT",    0.76, "a-n-A-M-F-Q-r", "AUTEL EVO II")));

        // Sensor updates (position fixed, just refresh stale timer)
        dispatchCot(buildSensorCotXml(
                "COT-SENSOR-001", "SENTINEL-01", "a-f-G-E-S",
                sLats[0], sLons[0], sAlts[0], 90.0, 120.0, 2000.0));

        dispatchCot(buildSensorCotXml(
                "COT-SENSOR-002", "SENTINEL-02", "a-f-G-E-S",
                sLats[1], sLons[1], sAlts[1], 270.0, 180.0, 800.0));

//        cotProcessor.ingestSensor(buildSensorDto(
//                "DTO-SENSOR-003", "RADAR-01", "a-f-G-U-C-F",
//                sLats[2], sLons[2], sAlts[2], 0.0, 340, 1500.0));
//
//        cotProcessor.ingestSensor(buildSensorDto(
//                "DTO-SENSOR-004", "OPTIC-01", "a-f-G-E-S",
//                sLats[3], sLons[3], sAlts[3], 45.0, 60.0, 1500.0));
    }

    // ── Drone builders ────────────────────────────────────────────────────────

    private Effector buildDroneDto(String uid, String callsign,
                                   double lat, double lon, double alt,
                                   double heading, double ce,
                                   ClassificationResult... results) {
        Effector dto          = new Effector();
        dto.uid               = uid;
        dto.callsign          = callsign;
        dto.lat               = lat;
        dto.lon               = lon;
        dto.altitudeMeters    = alt;
        dto.heading           = heading;
        dto.locationAmbiguity = ce;
        dto.ClassificationResultList = Arrays.asList(results);
        return dto;
    }

    private static ClassificationResult result(String threat, String medium, double conf,
                                               String type2525, String typeName) {
        ClassificationResult r  = new ClassificationResult();
        r.threatLevel           = threat;
        r.classificationMedium  = medium;
        r.confidence            = conf;
        r.type2525              = type2525;
        r.typeName              = typeName;
        return r;
    }

    private static String buildDroneCotXml(String uid, String callsign,
                                           double lat, double lon, double hae,
                                           double ce, double heading,
                                           String classificationResultsXml) {
        long now     = System.currentTimeMillis();
        String time  = COT_TIME_FMT.format(new Date(now));
        String stale = COT_TIME_FMT.format(new Date(now + 300_000L));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<event version=\"2.0\" uid=\"" + uid + "\" type=\"a-p\""
                + " how=\"m-g\" time=\"" + time + "\" start=\"" + time + "\" stale=\"" + stale + "\">"
                + "<point lat=\"" + lat + "\" lon=\"" + lon
                + "\" hae=\"" + hae + "\" ce=\"" + ce + "\" le=\"9999999\"/>"
                + "<detail>"
                + "<contact callsign=\"" + callsign + "\"/>"
                + "<track course=\"" + heading + "\" speed=\"0.0\"/>"
                + "<dewcCuas.cotprocessingFilterTag dewcCuas.UasItem=\"true\">"
                + "<dewcCuas.classificationResults>"
                + classificationResultsXml
                + "</dewcCuas.classificationResults>"
                + "</dewcCuas.cotprocessingFilterTag>"
                + "</detail></event>";
    }

    private static String cotResults(String t1, String m1, double c1, String ty1, String tn1,
                                     String t2, String m2, double c2, String ty2, String tn2) {
        return cotResult(t1, m1, c1, ty1, tn1) + cotResult(t2, m2, c2, ty2, tn2);
    }

    private static String cotResult(String threat, String medium, double conf,
                                    String type2525, String typeName) {
        return "<dewcCuas.classificationResult"
                + " threatLevel=\"" + threat + "\""
                + " classificationMedium=\"" + medium + "\""
                + " confidence=\"" + conf + "\""
                + " type2525=\"" + type2525 + "\""
                + " typeName=\"" + typeName + "\"/>";
    }

    // ── Sensor builders ───────────────────────────────────────────────────────

    private static Sensor buildSensorDto(String uid, String callsign, String cotType,
                                         double lat, double lon, double alt,
                                         double heading, double fov, double range) {
        Sensor s       = new Sensor();
        s.uid          = uid;
        s.callsign     = callsign;
        s.cotType      = cotType;
        s.lat          = lat;
        s.lon          = lon;
        s.altitudeMeters = alt;
        s.heading      = heading;
        s.FOV          = fov;
        s.range        = range;
        return s;
    }

    private static String buildSensorCotXml(String uid, String callsign, String cotType,
                                            double lat, double lon, double hae,
                                            double heading, double fov, double range) {
        long now     = System.currentTimeMillis();
        String time  = COT_TIME_FMT.format(new Date(now));
        String stale = COT_TIME_FMT.format(new Date(now + 300_000L));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<event version=\"2.0\" uid=\"" + uid + "\" type=\"" + cotType + "\""
                + " how=\"m-g\" time=\"" + time + "\" start=\"" + time + "\" stale=\"" + stale + "\">"
                + "<point lat=\"" + lat + "\" lon=\"" + lon
                + "\" hae=\"" + hae + "\" ce=\"9999999.0\" le=\"9999999.0\"/>"
                + "<detail>"
                + "<contact callsign=\"" + callsign + "\"/>"
                + "<sensor"
                + " azimuth=\"" + heading + "\""
                + " fov=\"" + fov + "\""
                + " range=\"" + range + "\""
                + " vfov=\"45\""
                + " fovRed=\"0.0\" fovGreen=\"0.8\" fovBlue=\"1.0\" fovAlpha=\"0.3\""
                + " strokeColor=\"-16777216\" strokeWeight=\"0.5\""
                + " rangeLines=\"" + (range / 2.0) + "\""
                + " rangeLineStrokeColor=\"-16777216\" rangeLineStrokeWeight=\"1.0\""
                + " elevation=\"0\" roll=\"0\" displayMagneticReference=\"0\"/>"
                + "<dewcCuas.cotprocessingFilterTag dewcCuas.SensorItem=\"true\""
                + " FOV=\"" + fov + "\" range=\"" + range + "\"/>"
                + "</detail></event>";
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private void dispatchCot(String xml) {
        CotDispatcher internal = CotMapComponent.getInternalDispatcher();
        if (internal == null) {
            Log.e(TAG, "dispatchCot: internal dispatcher null");
            return;
        }
        CotEvent event = CotEvent.parse(xml);
        if (event == null || !event.isValid()) {
            Log.e(TAG, "dispatchCot: invalid event xml=" + xml);
            return;
        }
        internal.dispatch(event);
    }

    private void driftDrones() {
        for (int i = 0; i < 4; i++) {
            dLats[i] += (rng.nextDouble() - 0.5) * 0.0002;
            dLons[i] += (rng.nextDouble() - 0.5) * 0.0002;
            dAlts[i] += (rng.nextDouble() - 0.5) * 5.0;
        }
    }
}
