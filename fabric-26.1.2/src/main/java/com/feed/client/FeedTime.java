package com.feed.client;

import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

public final class FeedTime {

    private FeedTime() {
    }

    public static String ago(long epochMs) {
        return ago(Instant.ofEpochMilli(epochMs).toString());
    }

    public static String ago(String iso) {
        try {
            Instant t;
            try {
                t = Instant.parse(iso);
            } catch (Exception e) {
                t = OffsetDateTime.parse(iso).toInstant();
            }
            long s = Math.max(0, Duration.between(t, Instant.now()).getSeconds());
            if (s < 60) {
                return Component.translatable("feed.time.now").getString();
            }
            if (s < 3600) {
                return Component.translatable("feed.time.minutes", s / 60).getString();
            }
            if (s < 86400) {
                return Component.translatable("feed.time.hours", s / 3600).getString();
            }
            return Component.translatable("feed.time.days", s / 86400).getString();
        } catch (Exception e) {
            return "?";
        }
    }
}
