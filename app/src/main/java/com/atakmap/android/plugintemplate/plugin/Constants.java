package com.atakmap.android.plugintemplate.plugin;

public class Constants {
    public static final double RECLASSIFICATION_THRESHOLD = 0.10;
    public static final String RECTANGLE_SEARCH_TOOL_CALLBACK = "com.atakmap.android.dewcCUAS.RECTANGLE_SEARCH_TOOL";

    public static final String CUAS_COT_FIlTER_TAG = "dewcCuas.cotprocessingFilterTag";
    // Map group name used to contain all CUAS markers
    public static final String CUAS_GROUP_NAME = "CUAS";

    // MapItem tag — set to any non-null value to mark an item as a CUAS UAS target
    public static final String UAS_ITEM = "dewcCuas.UasItem";

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
