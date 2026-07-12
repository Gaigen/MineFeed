package com.feed.client;

import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client UI ({@code feed-client.toml}). */
public final class FeedConfig {

    public static final ModConfigSpec CLIENT_SPEC;

    public static final ModConfigSpec.BooleanValue SHOW_INVENTORY_BUTTON;
    public static final ModConfigSpec.IntValue INVENTORY_BUTTON_OFFSET_X;
    public static final ModConfigSpec.IntValue INVENTORY_BUTTON_OFFSET_Y;

    private static final String T = "feed.configuration.";

    static {
        ModConfigSpec.Builder client = new ModConfigSpec.Builder();

        client.translation(T + "ui").push("ui");
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

    public static void register(ModContainer modContainer) {
        modContainer.registerConfig(Type.CLIENT, CLIENT_SPEC);
    }

    private FeedConfig() {
    }
}
