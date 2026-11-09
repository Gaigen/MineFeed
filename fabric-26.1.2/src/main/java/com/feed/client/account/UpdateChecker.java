package com.feed.client.account;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** Asks /v2/meta once per game session whether a newer (or required) MineFeed version exists. */
public final class UpdateChecker {

    public enum State { UNKNOWN, UP_TO_DATE, UPDATE_AVAILABLE, OUTDATED }

    private static volatile State state = State.UNKNOWN;
    private static volatile String latest;
    private static volatile String downloadUrl;
    /** Fallback when /v2/meta is unreachable; the server value wins so the invite can change without an update. */
    public static final String DEFAULT_DISCORD = "https:" + "//discord.gg/FzZBQ43ujk";
    private static volatile String discordUrl;
    private static volatile boolean started;
    private static volatile boolean toastShown;

    private UpdateChecker() {
    }

    public static State state() {
        return state;
    }

    public static String latest() {
        return latest;
    }

    public static String downloadUrl() {
        return downloadUrl;
    }

    public static String discordUrl() {
        String u = discordUrl;
        return u != null && u.startsWith("https://") ? u : DEFAULT_DISCORD;
    }

    public static void checkOnce() {
        if (started) {
            return;
        }
        started = true;
        AccountManager.INSTANCE.api().meta().whenComplete((m, ex) -> {
            if (ex != null || m == null) {
                started = false; // try again next time the feed opens
                return;
            }
            String current = AccountManager.clientVersion();
            latest = m.latestClient();
            downloadUrl = m.downloadUrl();
            discordUrl = m.discordUrl();
            if (m.minClient() != null && Versions.compare(current, m.minClient()) < 0) {
                state = State.OUTDATED;
            } else if (m.latestClient() != null && Versions.compare(current, m.latestClient()) < 0) {
                state = State.UPDATE_AVAILABLE;
            } else {
                state = State.UP_TO_DATE;
            }
            if (state == State.OUTDATED || state == State.UPDATE_AVAILABLE) {
                Minecraft.getInstance().execute(UpdateChecker::toastOnce);
            }
        });
    }

    private static void toastOnce() {
        if (toastShown) {
            return;
        }
        toastShown = true;
        Minecraft mc = Minecraft.getInstance();
        SystemToast.addOrUpdate(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable(state == State.OUTDATED ? "feed.update.outdated" : "feed.update.title"),
                Component.translatable("feed.update.body", latest == null ? "?" : latest));
    }
}
