package com.feed.client;

import com.feed.Feed;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client UI ({@code feed-client.toml}). */
public final class FeedConfig {

    public static final ModConfigSpec CLIENT_SPEC;

    public static final ModConfigSpec.BooleanValue SHOW_INVENTORY_BUTTON;
    public static final ModConfigSpec.ConfigValue<String> THEME;
    public static final ModConfigSpec.IntValue INVENTORY_BUTTON_OFFSET_X;
    public static final ModConfigSpec.IntValue INVENTORY_BUTTON_OFFSET_Y;
    public static final ModConfigSpec.BooleanValue ENABLE_DEATH_HOOK;
    public static final ModConfigSpec.BooleanValue ENABLE_TOAST_NOTIFICATIONS;
    public static final ModConfigSpec.BooleanValue ENABLE_ADVANCEMENT_GG;

    public static final ModConfigSpec.BooleanValue ENABLE_CHAT_NOTIFICATIONS;
    public static final ModConfigSpec.BooleanValue ENABLE_NOTIFICATION_SOUND;
    public static final ModConfigSpec.IntValue NOTIFICATION_SOUND_VOLUME;
    public static final ModConfigSpec.IntValue NOTIFICATION_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue TOAST_DURATION_SECONDS;
    public static final ModConfigSpec.IntValue MAX_VISIBLE_TOASTS;
    public static final ModConfigSpec.BooleanValue TOAST_ON_LEFT;
    public static final ModConfigSpec.IntValue TOAST_OFFSET_X;
    public static final ModConfigSpec.IntValue TOAST_OFFSET_Y;
    public static final ModConfigSpec.ConfigValue<String> TOAST_TITLE_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<String> TOAST_BODY_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<String> CHAT_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<String> DEATH_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<String> ADVANCEMENT_TEMPLATE;

    private static final String T = "feed.configuration.";

    static {
        ModConfigSpec.Builder client = new ModConfigSpec.Builder();

        client.translation(T + "ui").push("ui");
        THEME = client
                .comment("Color theme: dark, light, nether, end, terminal")
                .translation(T + "theme")
                .define("theme", "dark");
        SHOW_INVENTORY_BUTTON = client
                .comment("Show MineFeed button to the left of the inventory screen")
                .translation(T + "showInventoryButton")
                .define("showInventoryButton", true);
        INVENTORY_BUTTON_OFFSET_X = client
                .comment("Horizontal offset of the inventory button (pixels; negative = further left)")
                .translation(T + "inventoryButtonOffsetX")
                .defineInRange("inventoryButtonOffsetX", 0, -80, 80);
        INVENTORY_BUTTON_OFFSET_Y = client
                .comment("Vertical offset of the inventory button (pixels; positive = lower)")
                .translation(T + "inventoryButtonOffsetY")
                .defineInRange("inventoryButtonOffsetY", 0, -80, 160);
        client.pop();

        client.translation(T + "features").push("features");
        ENABLE_DEATH_HOOK = client
                .comment("Offer to post death message after respawn")
                .translation(T + "enableDeathHook")
                .define("enableDeathHook", true);
        ENABLE_TOAST_NOTIFICATIONS = client
                .comment("Show on-screen toast when someone replies to your post")
                .translation(T + "enableToastNotifications")
                .define("enableToastNotifications", true);
        ENABLE_ADVANCEMENT_GG = client
                .comment("Open GG compose screen when you earn an advancement")
                .translation(T + "enableAdvancementGg")
                .define("enableAdvancementGg", false);
        client.pop();

        client.translation(T + "notifications").push("notifications");
        ENABLE_CHAT_NOTIFICATIONS = client.comment("Show reply notifications locally in chat (never sends a message)")
                .translation(T + "enableChatNotifications").define("enableChatNotifications", false);
        ENABLE_NOTIFICATION_SOUND = client.comment("Play a local sound when new replies arrive")
                .translation(T + "enableNotificationSound").define("enableNotificationSound", false);
        NOTIFICATION_SOUND_VOLUME = client.comment("Reply notification sound volume, percent")
                .translation(T + "notificationSoundVolume").defineInRange("notificationSoundVolume", 50, 0, 100);
        NOTIFICATION_INTERVAL_SECONDS = client.comment("Seconds between checks for replies")
                .translation(T + "notificationIntervalSeconds").defineInRange("intervalSeconds", 45, 15, 300);
        TOAST_DURATION_SECONDS = client.comment("Seconds a reply toast stays visible")
                .translation(T + "toastDurationSeconds").defineInRange("toastDurationSeconds", 8, 1, 30);
        MAX_VISIBLE_TOASTS = client.comment("Maximum simultaneously visible reply toasts")
                .translation(T + "maxVisibleToasts").defineInRange("maxVisibleToasts", 3, 1, 5);
        TOAST_ON_LEFT = client.comment("Place reply toasts on the left instead of the right")
                .translation(T + "toastOnLeft").define("toastOnLeft", false);
        TOAST_OFFSET_X = client.comment("Horizontal inset from the screen edge (pixels)")
                .translation(T + "toastOffsetX").defineInRange("toastOffsetX", 8, 0, 200);
        TOAST_OFFSET_Y = client.comment("Vertical inset from the top edge (pixels)")
                .translation(T + "toastOffsetY").defineInRange("toastOffsetY", 8, 0, 200);
        TOAST_TITLE_TEMPLATE = client.comment("Reply toast title. Variables: {author}, {message}, {post}")
                .translation(T + "toastTitleTemplate").define("toastTitleTemplate", "→ «{post}»");
        TOAST_BODY_TEMPLATE = client.comment("Reply toast body. Variables: {author}, {message}, {post}")
                .translation(T + "toastBodyTemplate").define("toastBodyTemplate", "{author}: {message}");
        CHAT_TEMPLATE = client.comment("Local chat notification. Variables: {author}, {message}, {post}")
                .translation(T + "chatTemplate").define("chatTemplate", "[MineFeed] {author}: {message}");
        client.pop();

        client.translation(T + "templates").push("templates");
        DEATH_TEMPLATE = client.comment("Death post prefill. Variables: {message}, {player}, {coords} (where you died)")
                .translation(T + "deathTemplate").define("deathTemplate", "{message} {coords}");
        ADVANCEMENT_TEMPLATE = client.comment("Advancement post prefill. Variables: {advancement}, {player}")
                .translation(T + "advancementTemplate").define("advancementTemplate", "{advancement}");
        client.pop();

        CLIENT_SPEC = client.build();
    }

    public static boolean showInventoryButton() {
        try {
            return SHOW_INVENTORY_BUTTON.get();
        } catch (Exception e) {
            return true;
        }
    }

    public static int inventoryButtonOffsetX() {
        try {
            return INVENTORY_BUTTON_OFFSET_X.get();
        } catch (Exception e) {
            return 0;
        }
    }

    public static int inventoryButtonOffsetY() {
        try {
            return INVENTORY_BUTTON_OFFSET_Y.get();
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean deathHookEnabled() {
        try {
            return ENABLE_DEATH_HOOK.get();
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean toastNotificationsEnabled() {
        try {
            return ENABLE_TOAST_NOTIFICATIONS.get();
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean advancementGgEnabled() {
        try {
            return ENABLE_ADVANCEMENT_GG.get();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean chatNotificationsEnabled() { return get(ENABLE_CHAT_NOTIFICATIONS, false); }
    public static boolean notificationSoundEnabled() { return get(ENABLE_NOTIFICATION_SOUND, false); }
    public static int notificationSoundVolume() { return get(NOTIFICATION_SOUND_VOLUME, 50); }
    public static int notificationIntervalSeconds() { return get(NOTIFICATION_INTERVAL_SECONDS, 45); }
    public static int toastDurationSeconds() { return get(TOAST_DURATION_SECONDS, 8); }
    public static int maxVisibleToasts() { return get(MAX_VISIBLE_TOASTS, 3); }
    public static boolean toastOnLeft() { return get(TOAST_ON_LEFT, false); }
    public static int toastOffsetX() { return get(TOAST_OFFSET_X, 8); }
    public static int toastOffsetY() { return get(TOAST_OFFSET_Y, 8); }
    public static String toastTitleTemplate() { return get(TOAST_TITLE_TEMPLATE, "→ «{post}»"); }
    public static String toastBodyTemplate() { return get(TOAST_BODY_TEMPLATE, "{author}: {message}"); }
    public static String chatTemplate() { return get(CHAT_TEMPLATE, "[MineFeed] {author}: {message}"); }
    public static String deathTemplate() { return get(DEATH_TEMPLATE, "{message} {coords}"); }
    public static String advancementTemplate() { return get(ADVANCEMENT_TEMPLATE, "{advancement}"); }

    public static String theme() { return get(THEME, "dark"); }

    public static void setTheme(String id) {
        try {
            THEME.set(id);
            THEME.save();
        } catch (Exception e) {
            Feed.LOG.warn("could not save theme", e);
        }
    }

    private static <T> T get(ModConfigSpec.ConfigValue<T> value, T fallback) {
        try { return value.get(); } catch (Exception e) { return fallback; }
    }

    public static void register(ModContainer modContainer) {
        modContainer.registerConfig(Type.CLIENT, CLIENT_SPEC);
    }

    private FeedConfig() {
    }
}
