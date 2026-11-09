package com.feed.client;

import com.feed.Feed;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fabric client settings. Stored separately from NeoForge's TOML config. */
public final class FeedConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("feed-fabric.json");
    private static JsonObject values = new JsonObject();
    public static void load() {
        try {
            if (Files.exists(FILE)) {
                JsonObject read = GSON.fromJson(Files.readString(FILE), JsonObject.class);
                if (read != null) values = read;
            }
            // Populate defaults without overwriting existing settings.
            showInventoryButton(); theme(); inventoryButtonOffsetX(); inventoryButtonOffsetY();
            deathHookEnabled(); toastNotificationsEnabled(); advancementGgEnabled(); chatNotificationsEnabled();
            notificationSoundEnabled(); notificationSoundVolume(); notificationIntervalSeconds(); toastDurationSeconds();
            maxVisibleToasts(); toastOnLeft(); toastOffsetX(); toastOffsetY(); toastTitleTemplate(); toastBodyTemplate();
            chatTemplate(); deathTemplate(); advancementTemplate(); save();
        } catch (Exception e) { Feed.LOG.warn("Unable to load MineFeed Fabric settings", e); }
    }
    private static void save() {
        try { Files.createDirectories(FILE.getParent()); Files.writeString(FILE, GSON.toJson(values)); }
        catch (Exception e) { Feed.LOG.warn("Unable to save MineFeed Fabric settings", e); }
    }
    private static boolean bool(String key, boolean fallback) {
        try { if (values.has(key)) return values.get(key).getAsBoolean(); } catch (RuntimeException ignored) {}
        values.addProperty(key, fallback); return fallback;
    }
    private static int integer(String key, int fallback, int min, int max) {
        try { if (values.has(key)) return Math.max(min, Math.min(max, values.get(key).getAsInt())); } catch (RuntimeException ignored) {}
        values.addProperty(key, fallback); return fallback;
    }
    private static String string(String key, String fallback) {
        try { if (values.has(key)) return values.get(key).getAsString(); } catch (RuntimeException ignored) {}
        values.addProperty(key, fallback); return fallback;
    }
    public static boolean showInventoryButton() { return bool("showInventoryButton", true); }
    public static String theme() { return string("theme", "dark"); }
    public static void setTheme(String id) { values.addProperty("theme", id); save(); }
    public static int inventoryButtonOffsetX() { return integer("inventoryButtonOffsetX", 0, -80, 80); }
    public static int inventoryButtonOffsetY() { return integer("inventoryButtonOffsetY", 0, -80, 160); }
    public static boolean deathHookEnabled() { return bool("enableDeathHook", true); }
    public static boolean toastNotificationsEnabled() { return bool("enableToastNotifications", true); }
    public static boolean advancementGgEnabled() { return bool("enableAdvancementGg", false); }
    public static boolean chatNotificationsEnabled() { return bool("enableChatNotifications", false); }
    public static boolean notificationSoundEnabled() { return bool("enableNotificationSound", false); }
    public static int notificationSoundVolume() { return integer("notificationSoundVolume", 50, 0, 100); }
    public static int notificationIntervalSeconds() { return integer("notificationIntervalSeconds", 45, 15, 300); }
    public static int toastDurationSeconds() { return integer("toastDurationSeconds", 8, 1, 30); }
    public static int maxVisibleToasts() { return integer("maxVisibleToasts", 3, 1, 5); }
    public static boolean toastOnLeft() { return bool("toastOnLeft", false); }
    public static int toastOffsetX() { return integer("toastOffsetX", 8, 0, 200); }
    public static int toastOffsetY() { return integer("toastOffsetY", 8, 0, 200); }
    public static String toastTitleTemplate() { return string("toastTitleTemplate", "→ «{post}»"); }
    public static String toastBodyTemplate() { return string("toastBodyTemplate", "{author}: {message}"); }
    public static String chatTemplate() { return string("chatTemplate", "[MineFeed] {author}: {message}"); }
    public static String deathTemplate() { return string("deathTemplate", "{message} {coords}"); }
    public static String advancementTemplate() { return string("advancementTemplate", "{advancement}"); }
    private FeedConfig() {}
}
