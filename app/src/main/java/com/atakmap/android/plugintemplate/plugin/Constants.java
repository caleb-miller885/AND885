package com.atakmap.android.plugintemplate.plugin;

import java.text.SimpleDateFormat;
import java.util.Locale;

public class Constants {
    public static final String RECTANGLE_SEARCH_TOOL_CALLBACK = "com.atakmap.android.dewcCUAS.RECTANGLE_SEARCH_TOOL";

    public static final String CUAS_COT_FIlTER_TAG = "dewcCuas.cotprocessingFilterTag";
    public static final String CUAS_GROUP_NAME = "CUAS";

    public static final String UAS_ITEM = "dewcCuas.UasItem";
    public static final String SENSOR_ITEM = "dewcCuas.SensorItem";
    public static final String CUAS_SENSOR_COT_FILTER_TAG = "dewcCuas.sensorFilterTag";

    public static final double RECLASSIFICATION_THRESHOLD = 0.10;
    public static final String DELIMITER = "|";

    public static final SimpleDateFormat COT_TIME_FMT;
    static {
        COT_TIME_FMT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        COT_TIME_FMT.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    }


    public static final String LOCATION_AMBIGUITY_UID = "dewcCuas.locationAmbiguityUID";

    public static final String LOCATION_AMBIGUITY_AREA = "dewcCuas.locationAmbiguityArea";

    public static final String SEARCH_AREA = "dewcCuas.searchArea";

    // Serialized ArrayList<String> of "|"-delimited ClassificationResult entries:
    // each entry is "threatLevel|classificationMedium|confidence"
    public static final String CLASSIFICATION_RESULTS = "dewcCuas.classificationResults";
    public static final String SELECTED_CLASSIFICATION_RESULT = "dewcCuas.selectedClassificationResult";


    // CoT element names for classification results serialization
    public static final String CLASSIFICATION_RESULTS_COT_TAG = "dewcCuas.classificationResults";
    public static final String CLASSIFICATION_RESULT_COT_TAG  = "dewcCuas.classificationResult";

    // Threat level values
    public static final String THREAT_CRITICAL = "CRITICAL";
    public static final String THREAT_HIGH     = "HIGH";
    public static final String THREAT_MINIMAL  = "MINIMAL";
}
