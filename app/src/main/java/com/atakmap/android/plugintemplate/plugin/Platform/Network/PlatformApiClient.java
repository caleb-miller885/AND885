package com.atakmap.android.plugintemplate.plugin.Platform.Network;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * One shared OkHttp/Retrofit stack for every platform — not one client per platform. baseUrl is
 * a required-but-unused placeholder; every {@link PlatformApiService} call supplies its own
 * absolute @Url built from that platform's own endpoint, so connecting/reconnecting a platform
 * (or adding/removing one) never touches this client at all.
 */
public final class PlatformApiClient {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    private static final Retrofit RETROFIT = new Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(HTTP_CLIENT)
            .addConverterFactory(GsonConverterFactory.create())
            .build();

    public static final PlatformApiService SERVICE = RETROFIT.create(PlatformApiService.class);

    private PlatformApiClient() { }
}
