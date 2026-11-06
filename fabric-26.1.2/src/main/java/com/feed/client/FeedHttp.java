package com.feed.client;

import java.net.http.HttpClient;
import java.time.Duration;

public final class FeedHttp {

    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private FeedHttp() {
    }
}
