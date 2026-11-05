package com.feed.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Players I chose not to see: their posts and comments are left out of my feed.
 * Local only (config/feed-hidden.json), the other player is not told.
 */
public final class HiddenPlayers {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** account id -> nick at the time of hiding (for the list). */
    private static final Map<String, String> HIDDEN = new LinkedHashMap<>();
    private static boolean loaded;

    private HiddenPlayers() {
    }

    public static synchronized boolean isHidden(String accountId) {
        load();
        return accountId != null && HIDDEN.containsKey(accountId);
    }

    public static synchronized void hide(String accountId, String nick) {
        load();
        if (accountId == null || FeedApiClient.isMine(accountId)) {
            return;
        }
        HIDDEN.put(accountId, nick == null ? "?" : nick);
        save();
    }

    public static synchronized void unhide(String accountId) {
        load();
        if (HIDDEN.remove(accountId) != null) {
            save();
        }
    }

    public static synchronized Map<String, String> all() {
        load();
        return new LinkedHashMap<>(HIDDEN);
    }

    public static synchronized int count() {
        load();
        return HIDDEN.size();
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("feed-hidden.json");
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path p = file();
            if (Files.isRegularFile(p)) {
                JsonObject o = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
                if (o != null && o.has("players") && o.get("players").isJsonObject()) {
                    for (var e : o.getAsJsonObject("players").entrySet()) {
                        HIDDEN.put(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "?");
                    }
                }
            }
        } catch (Exception ignored) {
            // a broken file just means nobody is hidden
        }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            JsonObject players = new JsonObject();
            HIDDEN.forEach(players::addProperty);
            o.add("players", players);
            Path p = file();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // best effort
        }
    }
}
