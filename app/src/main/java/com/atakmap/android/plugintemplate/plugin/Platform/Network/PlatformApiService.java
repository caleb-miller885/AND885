package com.atakmap.android.plugintemplate.plugin.Platform.Network;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Url;

/**
 * The API surface every platform's onboard server exposes. One shared interface for all
 * platforms — each call takes the target platform's full request URL directly (built from that
 * platform's PlatformEntry.endpoint) instead of relying on a fixed Retrofit baseUrl, since the
 * set of platforms and their endpoints both change at runtime.
 *
 * Add POST/PATCH methods here the same way as new onboard endpoints are needed
 * (e.g. loadPreset(@Url String url, @Body LoadPresetRequest body)).
 */
public interface PlatformApiService {

    @GET
    Call<PlatformStatusResponse> getStatus(@Url String url);
}
